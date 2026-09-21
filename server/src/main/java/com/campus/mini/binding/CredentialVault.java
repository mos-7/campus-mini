package com.campus.mini.binding;

import com.campus.mini.config.CampusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据保险箱 —— 唯一接触明文密码的类。
 *
 * <p>要支持「课表自动更新」（静默同步），就必须能重复登录，所以密码必须
 * <b>可逆加密</b>存储，不能只存哈希。
 *
 * <p>方案：AES-256-GCM，每条记录独立随机 IV，密文与 IV 拼接后 Base64 存一列。
 *
 * <p><b>说清楚这个取舍</b>：只要服务端能解密，运维方（也就是你）技术上就能拿到密码。
 * 自己用没问题；给别人用必须在隐私政策里写明，并且不要把明文写进日志。
 * 另一种选择是只存 Cookie/Token，代价是过期后要重新绑定 ——
 * {@code ChaoxingAdapter} 支持粘贴 Cookie，就是为这个准备的。
 *
 * <p>主密钥来自 {@code campus.master-key}（对应环境变量 {@code CAMPUS_MASTER_KEY}），
 * <b>绝不进代码库</b>。
 */
@Component
public class CredentialVault {

    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 仅用于本地开发。明显是占位符，避免有人误以为它能上生产。 */
    private static final byte[] DEV_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CredentialVault(CampusProperties properties) {
        String configured = properties.getMasterKey();
        byte[] material;

        if (configured == null || configured.isBlank()) {
            material = DEV_KEY;
            log.warn("campus.master-key 未配置，正在使用开发用固定密钥。"
                    + "生产环境必须通过环境变量 CAMPUS_MASTER_KEY 配置 32 字节 Base64 密钥！");
        } else {
            try {
                material = Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("campus.master-key 不是合法的 Base64", e);
            }
            if (material.length != 32) {
                throw new IllegalStateException(
                        "campus.master-key 必须是 32 字节（Base64 后 44 字符），当前 " + material.length + " 字节。"
                                + "生成方法：openssl rand -base64 32");
            }
        }
        this.key = new SecretKeySpec(material, "AES");
    }

    /** 加密。返回 Base64(IV ‖ 密文‖Tag)。 */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密凭据失败", e);
        }
    }

    /** 解密。密文损坏或换过主密钥会抛异常 —— 此时应提示用户重新绑定。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalStateException("密文长度不合法");
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "解密凭据失败，通常是主密钥变了。请让用户重新绑定该平台。", e);
        }
    }
}
