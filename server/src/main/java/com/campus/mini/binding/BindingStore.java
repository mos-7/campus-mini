package com.campus.mini.binding;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户与平台绑定的持久化。
 *
 * <p>这里存的是<b>密文</b>（{@link CredentialVault#encrypt} 的产物），
 * 明文只在 {@code BindingService} 里短暂存在。
 */
@Repository
public class BindingStore {

    private final JdbcTemplate jdbc;

    public BindingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条绑定。{@code secret} 字段是密文。 */
    public record Binding(
            long id,
            long userId,
            String adapterCode,
            String loginMode,
            String accountLabel,
            String secretEncrypted,
            String status,
            Instant lastSyncAt,
            String lastError
    ) {
        @Override
        public String toString() {
            return "Binding[adapter=" + adapterCode + ", account=" + accountLabel
                    + ", status=" + status + ", secret=***]";
        }
    }

    private static final RowMapper<Binding> MAPPER = (rs, rowNum) -> new Binding(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("adapter_code"),
            rs.getString("login_mode"),
            rs.getString("account_label"),
            rs.getString("secret_encrypted"),
            rs.getString("status"),
            rs.getTimestamp("last_sync_at") == null ? null : rs.getTimestamp("last_sync_at").toInstant(),
            rs.getString("last_error"));

    // ------------------------------------------------------------------
    // 用户
    // ------------------------------------------------------------------

    /** 按 openid 找用户，没有就建。返回本地 user id。 */
    public long upsertUser(String openid, String nickname, String avatarUrl) {
        Optional<Long> existing = findUserId(openid);
        if (existing.isPresent()) {
            return existing.get();
        }

        KeyHolder holder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO app_user (openid, nickname, avatar_url, created_at) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, openid);
                ps.setString(2, nickname);
                ps.setString(3, avatarUrl);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                return ps;
            }, holder);
        } catch (DuplicateKeyException e) {
            // 并发首次登录：另一个请求刚把同一 openid 插进去了。回查即可，不是错误。
            return findUserId(openid).orElseThrow(
                    () -> new IllegalStateException("并发创建用户后仍查不到 openid=" + openid, e));
        }

        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建用户失败，没拿到自增主键");
        }
        return key.longValue();
    }

    public Optional<Long> findUserId(String openid) {
        return jdbc.query("SELECT id FROM app_user WHERE openid = ?",
                (rs, rowNum) -> rs.getLong(1), openid).stream().findFirst();
    }

    public void updateProfile(long userId, String nickname, String avatarUrl) {
        jdbc.update("UPDATE app_user SET nickname = ?, avatar_url = ? WHERE id = ?", nickname, avatarUrl, userId);
    }

    // ------------------------------------------------------------------
    // 绑定
    // ------------------------------------------------------------------

    /** 新增或覆盖绑定（同一用户同一平台只留一条）。 */
    public void saveBinding(long userId, String adapterCode, String loginMode,
                            String accountLabel, String secretEncrypted) {
        int updated = jdbc.update(
                "UPDATE binding SET login_mode = ?, account_label = ?, secret_encrypted = ?, "
                        + "status = 'BOUND', last_error = NULL WHERE user_id = ? AND adapter_code = ?",
                loginMode, accountLabel, secretEncrypted, userId, adapterCode);

        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO binding (user_id, adapter_code, login_mode, account_label, "
                            + "secret_encrypted, status, created_at) VALUES (?, ?, ?, ?, ?, 'BOUND', ?)",
                    userId, adapterCode, loginMode, accountLabel, secretEncrypted, Timestamp.from(Instant.now()));
        }
    }

    public List<Binding> listBindings(long userId) {
        return jdbc.query("SELECT * FROM binding WHERE user_id = ? ORDER BY adapter_code", MAPPER, userId);
    }

    public Optional<Binding> findBinding(long userId, String adapterCode) {
        return jdbc.query("SELECT * FROM binding WHERE user_id = ? AND adapter_code = ?",
                MAPPER, userId, adapterCode).stream().findFirst();
    }

    public void deleteBinding(long userId, String adapterCode) {
        jdbc.update("DELETE FROM binding WHERE user_id = ? AND adapter_code = ?", userId, adapterCode);
    }

    public void markSyncResult(long userId, String adapterCode, boolean ok, String error) {
        jdbc.update("UPDATE binding SET last_sync_at = ?, status = ?, last_error = ? "
                        + "WHERE user_id = ? AND adapter_code = ?",
                Timestamp.from(Instant.now()),
                ok ? "BOUND" : "ERROR",
                error,
                userId,
                adapterCode);
    }
}
