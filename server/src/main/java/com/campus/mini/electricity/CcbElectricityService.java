package com.campus.mini.electricity;

import com.campus.mini.common.ApiException;
import com.campus.mini.electricity.CcbClient.SessionExpiredException;
import com.campus.mini.electricity.CcbSessionStore.Room;
import com.campus.mini.electricity.CcbSessionStore.Session;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 电费"会话接力"服务：保存用户扫码导入的建行会话，拿它去查余额、记历史。
 *
 * <p>和 {@link ElectricityService}（演示数据 + 手动绑定）是并列的两条通道：
 * 这边是接了建行 E码通 的真数据，接口前缀同样是 /api/electricity，方法名不冲突。
 *
 * <p>会话特性（实测）：同一会话可连续查询；银行侧约 30 分钟不用就过期，
 * 过期后 {@code /live} 会返回 EXPIRED 并保留最后一次读数，用户重新扫码导入即可。
 */
@Service
public class CcbElectricityService {

    /** 一次余额读数（给前端的视图）。 */
    public record BalanceView(Double mainFare, Double bal,
                              Double subsidyBal, Double subsidyMain,
                              String dormText, Instant queriedAt) {
    }

    /** /live 的总视图：会话状态 + 最新读数。 */
    public record LiveView(boolean imported,
                           String status,          // NONE / ACTIVE / EXPIRED
                           String dormText,
                           String lastError,
                           Instant importedAt,
                           BalanceView latest) {
    }

    private final CcbSessionStore sessionStore;
    private final ReadingStore readingStore;
    private final CcbClient ccb;

    public CcbElectricityService(CcbSessionStore sessionStore, ReadingStore readingStore, CcbClient ccb) {
        this.sessionStore = sessionStore;
        this.readingStore = readingStore;
        this.ccb = ccb;
    }

    /**
     * 导入扫码得到的会话，立即查一次余额并把读数记入历史。
     * <p>导入的会话可能已经失效（ccbParam 有效期只有几分钟），那就保存状态为
     * EXPIRED 并把平台原文带回去，让前端提示重新截留。
     */
    public BalanceView importSession(long userId, ImportPayload payload) {
        if (payload == null || isBlank(payload.skey) || isBlank(payload.uid)) {
            throw ApiException.badRequest("二维码内容不完整（缺 SKEY/USERID），重新截留一次。");
        }
        Room room = payload.room == null ? null
                : new Room(payload.room.areaid, payload.room.areaname,
                payload.room.buildingid, payload.room.buildingname,
                payload.room.floorid, payload.room.floorname,
                payload.room.roomid, payload.room.room);

        sessionStore.save(userId, payload.uid, payload.skey, payload.cookie, room);
        Session session = sessionStore.find(userId).orElseThrow();

        try {
            BalanceView view = fetchAndRecord(userId, session);
            return view;
        } catch (SessionExpiredException e) {
            sessionStore.markExpired(userId, e.getMessage());
            throw ApiException.badRequest("这份会话已经失效了（ccbParam 过期）。"
                    + "重新截留一次：断点拦住登录请求后要马上用，别放给小程序。");
        }
    }

    /**
     * 页面打开时的总查询。ACTIVE 会话直接去查一次（顺带探活）；
     * 最近 60 秒内查过就直接用缓存，避免快速切页打爆平台。
     */
    public LiveView live(long userId) {
        Optional<Session> maybe = sessionStore.find(userId);
        if (maybe.isEmpty()) {
            return new LiveView(false, "NONE", null, null, null, latestView(userId));
        }
        Session session = maybe.get();

        if (CcbSessionStore.EXPIRED.equals(session.status())) {
            return new LiveView(true, "EXPIRED", dormText(session.room()),
                    session.lastError(), toInstant(session.importedAt()), latestView(userId));
        }

        Optional<ReadingStore.Reading> fresh = readingStore.latest(userId);
        if (fresh.isPresent() && System.currentTimeMillis()
                - fresh.get().queriedAt().getTime() < 60_000L) {
            return new LiveView(true, "ACTIVE", dormText(session.room()),
                    null, toInstant(session.importedAt()), toView(fresh.get()));
        }

        try {
            BalanceView view = fetchAndRecord(userId, session);
            return new LiveView(true, "ACTIVE", dormText(session.room()),
                    null, toInstant(session.importedAt()), view);
        } catch (SessionExpiredException e) {
            sessionStore.markExpired(userId, e.getMessage());
            return new LiveView(true, "EXPIRED", dormText(session.room()),
                    "会话已过期", toInstant(session.importedAt()), latestView(userId));
        }
    }

    /** 强制刷新（下拉/点按钮）。会话死了抛业务错误，前端提示重新导入。 */
    public BalanceView refresh(long userId) {
        Session session = sessionStore.find(userId)
                .orElseThrow(() -> ApiException.badRequest("还没有导入会话，先去电费页扫码导入。"));
        try {
            return fetchAndRecord(userId, session);
        } catch (SessionExpiredException e) {
            sessionStore.markExpired(userId, e.getMessage());
            throw ApiException.badRequest("会话已过期，请重新扫码导入。");
        }
    }

    public List<ReadingStore.Reading> history(long userId, int limit) {
        return readingStore.history(userId, Math.min(Math.max(limit, 1), 100));
    }

    // ------------------------------------------------------------------

    /** 真查一次：YJF004 → 存历史 → 标记会话可用。 */
    private BalanceView fetchAndRecord(long userId, Session session) {
        JsonNode data = ccb.queryBalance(session);
        Double mainFare = doubleOf(data, "mainFare");
        Double bal = doubleOf(data, "bal");
        Double subsidyBal = doubleOf(data, "subsidy_bal");
        Double subsidyMain = doubleOf(data, "subsidy_main");

        readingStore.save(userId, mainFare, bal, subsidyBal, subsidyMain, "CCB");
        sessionStore.markOk(userId);
        return new BalanceView(mainFare, bal, subsidyBal, subsidyMain,
                dormText(session.room()), Instant.now());
    }

    private BalanceView latestView(long userId) {
        return readingStore.latest(userId).map(this::toView).orElse(null);
    }

    private BalanceView toView(ReadingStore.Reading r) {
        return new BalanceView(r.mainFare(), r.bal(), r.subsidyBal(), r.subsidyMain(),
                null, toInstant(r.queriedAt()));
    }

    private static String dormText(Room room) {
        if (room == null) {
            return null;
        }
        List<String> parts = new java.util.ArrayList<>();
        if (!isBlank(room.areaname())) parts.add(room.areaname());
        if (!isBlank(room.buildingname())) parts.add(room.buildingname());
        if (!isBlank(room.floorname())) parts.add(room.floorname());
        if (!isBlank(room.room())) parts.add(room.room());
        return String.join(" · ", parts);
    }

    private static Double doubleOf(JsonNode data, String field) {
        String v = data.path(field).asText("").trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 扫码导入的载荷（relay 二维码内容）。 */
    public static class ImportPayload {
        public String skey;
        public String uid;
        public String cookie;
        public RoomPayload room;

        /** 宿舍链。字段名和 CCB 响应保持一致，relay 直接透传。 */
        public static class RoomPayload {
            public String areaid;
            public String areaname;
            public String buildingid;
            public String buildingname;
            public String floorid;
            public String floorname;
            public String roomid;
            public String room;
        }
    }
}
