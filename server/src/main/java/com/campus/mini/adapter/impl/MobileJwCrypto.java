package com.campus.mini.adapter.impl;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 「移动教务」类厂商 SaaS 的登录口令编码。
 *
 * <h2>算法</h2>
 * <pre>
 * pwd = Base64( Base64( AES-128-ECB/PKCS7( JSON.stringify(密码), key ) ) )
 * </pre>
 *
 * <h2>★ 这一段是逐字节验证过的，别凭直觉改</h2>
 *
 * <p>验证方法：用 Node 加载原始前端 bundle 里<b>未经修改</b>的压缩函数
 * （配 CryptoJS），跑出基准值；再用 Node 内置 {@code crypto} 独立重写一遍；
 * 最后与本类比对。7 个用例（含中文、逗号、引号、反斜杠、空串）三者密文完全一致。
 *
 * <h2>踩过的坑（改之前先读这段）</h2>
 *
 * <p>我最初以为 CryptoJS 的 {@code AES.encrypt(...).toString()} 返回<b>十六进制</b>，
 * 于是按 hex 实现——<b>结果全错</b>。
 *
 * <p>裸 {@code .toString()} 走的是 OpenSSL 格式化器，返回的是 <b>Base64</b>。
 * 所以最终结果里套了<b>两层 base64</b>（外层是 JS 的 {@code window.btoa}）。
 *
 * <p>佐证：原项目在另一处刻意写成 {@code .ciphertext.toString()} 才拿到 hex
 * （那个函数用 hex 是因为要和时间戳拼接）。作者自己是区分这两者的——我们没区分，
 * 第一次就错在这里，靠跑对照实验才发现。
 *
 * <h2>安全性说明</h2>
 *
 * <p>这套东西<b>不是加密，是混淆</b>：密钥是写死在前端 bundle 里的常量，
 * 每个访问者都能看到。它唯一的作用是让密码不以明文出现在请求里。
 * 在 HTTP（非 HTTPS）上这意味着密码仍可被同一网络内的人解出——
 * 好消息是这套系统的主 API 通常也在校内网。
 */
public final class MobileJwCrypto {

    private MobileJwCrypto() {
    }

    /**
     * 编码登录口令。
     *
     * @param password 明文密码
     * @param pwdKey   厂商的产品级 AES 密钥（16 字符）。由配置传入，
     *                 不硬编码 —— 公开仓库里不出现具体值。
     */
    public static String encodePassword(String password, String pwdKey) {
        if (pwdKey == null || pwdKey.getBytes(StandardCharsets.UTF_8).length != 16) {
            throw new IllegalStateException(
                    "mobileJw.pwd-key 必须是 16 字节（AES-128）。当前无效，检查 application-local.yml。");
        }

        byte[] plain = jsonStringify(password).getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding"); // PKCS5 == PKCS7 for AES
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(pwdKey.getBytes(StandardCharsets.UTF_8), "AES"));
            ciphertext = cipher.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("口令编码失败", e);
        }

        // 内层：CryptoJS 裸 .toString() -> Base64
        String inner = Base64.getEncoder().encodeToString(ciphertext);
        // 外层：window.btoa(inner)
        return Base64.getEncoder().encodeToString(inner.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 与 JS {@code JSON.stringify(字符串)} 逐字节一致的复刻。
     *
     * <p>为什么不用 Jackson：目标是<b>和 JS 完全一样</b>，不是"生成合法 JSON"。
     * 两者在转义细节上可能不同（例如 JS 不转义 {@code /}，对 U+2028/U+2029 也原样输出），
     * 而这里差一个字节就会导致服务端解密失败。
     */
    static String jsonStringify(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        // JS 对非 ASCII 原样输出（不转成 Unicode 转义序列），保持一致
                        // ★ 注意：这里别在注释里写反斜杠加 u 的形式 —— Java 在词法阶段
                        //    就会处理 Unicode 转义，注释里写了非法序列一样编译失败（踩过）。
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
