package com.campus.mini.web;

import com.campus.mini.config.CampusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 极简 JWT（HS256）。
 *
 * <p>手写而不是引 jjwt：只需要签发和校验一个整数字段，40 行就够了，
 * 少一个依赖少一份维护。要加更多声明（角色、设备）时再换库也不迟。
 *
 * <p>密钥来自 {@code campus.jwt-secret}（环境变量 {@code CAMPUS_JWT_SECRET}）。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER =
            ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    /** Token 有效期：30 天。小程序里长期免登录，符合用户预期。 */
    private static final long TTL_SECONDS = 30L * 24 * 3600;

    private final byte[] secret;

    public JwtService(CampusProperties properties) {
        String configured = properties.getJwtSecret();
        if (configured == null || configured.isBlank()) {
            configured = "dev-only-jwt-secret-please-change-in-production";
            log.warn("campus.jwt-secret 未配置，正在使用开发用固定值。"
                    + "生产环境必须通过环境变量 CAMPUS_JWT_SECRET 配置！");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(long userId) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = ENCODER.encodeToString(
                ("{\"uid\":" + userId + ",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + ENCODER.encodeToString(hmac(signingInput));
    }

    /** 校验并取出 userId。任何异常都当作无效，不抛出去。 */
    public Optional<Long> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // 常数时间比较，避免时序侧信道
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }

        try {
            JsonNode claims = JSON.readTree(DECODER.decode(parts[1]));
            if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(claims.path("uid").asLong());
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }
}
