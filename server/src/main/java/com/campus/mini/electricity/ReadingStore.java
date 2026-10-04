package com.campus.mini.electricity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 电费读数历史。自动查询成功后记一笔，用来画趋势、算变化量。
 */
@Repository
public class ReadingStore {

    /** 一条读数。金额元、电量度；字段都可能为空（平台没给就不硬造）。 */
    public record Reading(long id, Double mainFare, Double bal,
                          Double subsidyBal, Double subsidyMain,
                          String source, Timestamp queriedAt) {
    }

    private static final RowMapper<Reading> MAPPER = (rs, rowNum) -> new Reading(
            rs.getLong("id"),
            decimal(rs, "main_fare"),
            decimal(rs, "bal"),
            decimal(rs, "subsidy_bal"),
            decimal(rs, "subsidy_main"),
            rs.getString("source"),
            rs.getTimestamp("queried_at"));

    /** DECIMAL 列在 H2/MySQL 里回来的是 BigDecimal，直接强转 Double 会炸。 */
    private static Double decimal(java.sql.ResultSet rs, String col) {
        try {
            java.math.BigDecimal v = rs.getBigDecimal(col);
            return v == null ? null : v.doubleValue();
        } catch (Exception e) {
            return null;
        }
    }

    private final JdbcTemplate jdbc;

    public ReadingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long userId, Double mainFare, Double bal,
                     Double subsidyBal, Double subsidyMain, String source) {
        jdbc.update("INSERT INTO electricity_reading "
                        + "(user_id, main_fare, bal, subsidy_bal, subsidy_main, source) VALUES (?, ?, ?, ?, ?, ?)",
                userId, mainFare, bal, subsidyBal, subsidyMain, source);
    }

    /** 最新一条。 */
    public java.util.Optional<Reading> latest(long userId) {
        return jdbc.query("SELECT * FROM electricity_reading WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1", MAPPER, userId)
                .stream().findFirst();
    }

    /** 历史，新的在前。 */
    public List<Reading> history(long userId, int limit) {
        return jdbc.query("SELECT * FROM electricity_reading WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT ?", MAPPER, userId, limit);
    }
}
