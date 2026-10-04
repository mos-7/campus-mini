package com.campus.mini.electricity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 建行 E码通"接力会话"的持久化。一个用户一份，重新导入就覆盖（user_id 主键）。
 *
 * <p>背景见 docs/ccb-electricity.md：学校电费平台登录链绑定建行小程序的微信身份，
 * 后端无法自己登录；会话由用户在电脑上截留产生（relay 脚本），扫码交给后端代查。
 * 银行侧会话约 30 分钟不用就过期，过期后 {@link #markExpired} 置为 EXPIRED，
 * 等下一次导入覆盖。
 */
@Repository
public class CcbSessionStore {

    /** 会话状态。 */
    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";

    /** 宿舍的完整定位 —— YJF004 查余额必须带全这条链，缺一个就报"第三方服务异常"。 */
    public record Room(String areaid, String areaname,
                       String buildingid, String buildingname,
                       String floorid, String floorname,
                       String roomid, String room) {
    }

    /** 一份已导入的会话。 */
    public record Session(long userId,
                          String ccbUserid,
                          String skey,
                          String cookieHeader,
                          Room room,
                          String status,
                          Timestamp importedAt,
                          Timestamp lastOkAt,
                          String lastError) {
    }

    private static final RowMapper<Session> MAPPER = (rs, rowNum) -> new Session(
            rs.getLong("user_id"),
            rs.getString("ccb_userid"),
            rs.getString("skey"),
            rs.getString("cookie_header"),
            new Room(
                    rs.getString("areaid"), rs.getString("areaname"),
                    rs.getString("buildingid"), rs.getString("buildingname"),
                    rs.getString("floorid"), rs.getString("floorname"),
                    rs.getString("roomid"), rs.getString("room")),
            rs.getString("status"),
            rs.getTimestamp("imported_at"),
            rs.getTimestamp("last_ok_at"),
            rs.getString("last_error"));

    private final JdbcTemplate jdbc;

    public CcbSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Session> find(long userId) {
        return jdbc.query("SELECT * FROM ccb_session WHERE user_id = ?", MAPPER, userId)
                .stream().findFirst();
    }

    public void save(long userId, String ccbUserid, String skey, String cookieHeader, Room room) {
        int updated = jdbc.update(
                "UPDATE ccb_session SET ccb_userid = ?, skey = ?, cookie_header = ?, "
                        + "roomid = ?, room = ?, areaid = ?, areaname = ?, "
                        + "buildingid = ?, buildingname = ?, floorid = ?, floorname = ?, "
                        + "status = ?, imported_at = CURRENT_TIMESTAMP, last_ok_at = NULL, last_error = NULL "
                        + "WHERE user_id = ?",
                ccbUserid, skey, cookieHeader,
                room.roomid(), room.room(), room.areaid(), room.areaname(),
                room.buildingid(), room.buildingname(), room.floorid(), room.floorname(),
                ACTIVE, userId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO ccb_session (user_id, ccb_userid, skey, cookie_header, "
                            + "roomid, room, areaid, areaname, buildingid, buildingname, floorid, floorname, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    userId, ccbUserid, skey, cookieHeader,
                    room.roomid(), room.room(), room.areaid(), room.areaname(),
                    room.buildingid(), room.buildingname(), room.floorid(), room.floorname(),
                    ACTIVE);
        }
    }

    public void markOk(long userId) {
        jdbc.update("UPDATE ccb_session SET status = ?, last_ok_at = CURRENT_TIMESTAMP, last_error = NULL "
                + "WHERE user_id = ?", ACTIVE, userId);
    }

    public void markExpired(long userId, String error) {
        jdbc.update("UPDATE ccb_session SET status = ?, last_error = ? WHERE user_id = ?",
                EXPIRED, error, userId);
    }
}
