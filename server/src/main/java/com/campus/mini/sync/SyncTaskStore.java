package com.campus.mini.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 同步任务的状态机持久化。
 *
 * <p>为什么一定要落库：云托管的 CallContainer 有 15 秒超时，真正的抓取必须在
 * 异步线程里做，而异步线程随时可能因为实例缩容而消失。状态存库之后，
 * 前端轮询能看到「卡住了」，启动时的 {@link #failStale} 也能把它收拾掉，
 * 不会永远停在 RUNNING。
 */
@Repository
public class SyncTaskStore {

    /** 任务状态。 */
    public enum Status {
        PENDING, RUNNING, SUCCESS, FAILED
    }

    public record Task(
            String id,
            long userId,
            String adapterCode,
            String status,
            int progress,
            String message,
            int courseCount,
            int sessionCount,
            Instant createdAt,
            Instant finishedAt
    ) {
    }

    private static final RowMapper<Task> MAPPER = (rs, rowNum) -> new Task(
            rs.getString("id"),
            rs.getLong("user_id"),
            rs.getString("adapter_code"),
            rs.getString("status"),
            rs.getInt("progress"),
            rs.getString("message"),
            rs.getInt("course_count"),
            rs.getInt("session_count"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());

    private final JdbcTemplate jdbc;

    public SyncTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String id, long userId, String adapterCode) {
        jdbc.update("INSERT INTO sync_task (id, user_id, adapter_code, status, progress, message, created_at) "
                        + "VALUES (?, ?, ?, 'PENDING', 0, '排队中', ?)",
                id, userId, adapterCode, Timestamp.from(Instant.now()));
    }

    public void update(String id, int progress, String message) {
        jdbc.update("UPDATE sync_task SET status = 'RUNNING', progress = ?, message = ? WHERE id = ?",
                progress, message, id);
    }

    public void succeed(String id, String message, int courseCount, int sessionCount) {
        jdbc.update("UPDATE sync_task SET status = 'SUCCESS', progress = 100, message = ?, "
                        + "course_count = ?, session_count = ?, finished_at = ? WHERE id = ?",
                message, courseCount, sessionCount, Timestamp.from(Instant.now()), id);
    }

    public void fail(String id, String message) {
        jdbc.update("UPDATE sync_task SET status = 'FAILED', message = ?, finished_at = ? WHERE id = ?",
                message, Timestamp.from(Instant.now()), id);
    }

    public Optional<Task> find(String id) {
        return jdbc.query("SELECT * FROM sync_task WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 把卡死的任务标成失败。
     *
     * <p>容器缩容或重启会让跑在异步线程里的任务凭空消失，状态就永远停在 RUNNING。
     * 应用启动时调一次，把这些"僵尸任务"收掉。
     *
     * @return 收拾掉的任务数
     */
    public int failStale(int olderThanMinutes) {
        return jdbc.update(
                "UPDATE sync_task SET status = 'FAILED', message = '任务中断（服务重启或缩容），请重试', "
                        + "finished_at = ? WHERE status IN ('PENDING', 'RUNNING') AND created_at < ?",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().minusSeconds(olderThanMinutes * 60L)));
    }
}
