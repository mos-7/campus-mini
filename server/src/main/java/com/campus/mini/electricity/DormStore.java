package com.campus.mini.electricity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 宿舍绑定的持久化。一个用户只绑一间（user_id 主键），换宿舍直接覆盖。
 */
@Repository
public class DormStore {

    /** 一间宿舍的四级定位。 */
    public record Dorm(String campus, String building, String floor, String room) {
    }

    private static final RowMapper<Dorm> MAPPER = (rs, rowNum) -> new Dorm(
            rs.getString("campus"),
            rs.getString("building"),
            rs.getString("floor"),
            rs.getString("room"));

    private final JdbcTemplate jdbc;

    public DormStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Dorm> find(long userId) {
        return jdbc.query("SELECT campus, building, floor, room FROM dorm_binding WHERE user_id = ?",
                MAPPER, userId).stream().findFirst();
    }

    public void save(long userId, Dorm dorm) {
        int updated = jdbc.update(
                "UPDATE dorm_binding SET campus = ?, building = ?, floor = ?, room = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ?",
                dorm.campus(), dorm.building(), dorm.floor(), dorm.room(), userId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO dorm_binding (user_id, campus, building, floor, room) VALUES (?, ?, ?, ?, ?)",
                    userId, dorm.campus(), dorm.building(), dorm.floor(), dorm.room());
        }
    }

    public void delete(long userId) {
        jdbc.update("DELETE FROM dorm_binding WHERE user_id = ?", userId);
    }
}
