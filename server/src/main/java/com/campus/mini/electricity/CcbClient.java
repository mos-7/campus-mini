package com.campus.mini.electricity;

import com.campus.mini.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 建行 E码通（app.xiaoyuan.ccb.com）的最小客户端。
 *
 * <p>协议结论（2026-10 抓包 + 官方 H5 源码分析，详见 docs/ccb-electricity.md）：
 * 所有业务都是<b>明文表单</b> POST 到 {@code /LHECISM/B2CMainPlat_00}，固定信封 +
 * TXCODE（业务码）+ USERID + SKEY，无报文加密。会话来自用户扫码导入的
 * "接力会话"（截留 ccbParam 换出），银行侧约 30 分钟不用就过期。
 *
 * <p>错误码约定：
 * <ul>
 *   <li>{@code 0130Z1108006 历史页面} / {@code 0130Z1108007 请重新登录}
 *       —— 会话已死，抛 {@link SessionExpiredException}；</li>
 *   <li>其它 —— 统一包成业务错误，带上平台给的原文，方便排查。</li>
 * </ul>
 */
@Component
public class CcbClient {

    /** 会话过期的两种表现（同一回事：这份会话不能再用了）。 */
    private static final String ERR_HISTORY_PAGE = "0130Z1108006";
    private static final String ERR_RELOGIN = "0130Z1108007";

    /** 平台没给读数、但请求本身合法时的错误，让上层提示"稍后再试"。 */
    public static class CcbBusinessException extends RuntimeException {
        public CcbBusinessException(String message) {
            super(message);
        }
    }

    /** 会话已死。上层据此把 ccb_session 置为 EXPIRED。 */
    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String message) {
            super(message);
        }
    }

    private static final String ENDPOINT = "https://app.xiaoyuan.ccb.com/LHECISM/B2CMainPlat_00";

    /** 和官方 H5 发出去的头保持一致，减少被风控盯上的变量。 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/144.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781 NetType/WIFI MiniProgramEnv/Windows";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 发一个业务请求。
     *
     * @param txcode 业务码，如 DZ0392 / YJF004
     * @param params 业务参数（顺序无关，会按插入顺序拼表单）
     * @return 平台 JSON 的 data 节点；status != 0 时抛对应异常
     */
    public JsonNode post(String txcode, CcbSessionStore.Session session, Map<String, String> params) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("BRANCHID", "555000000");
        form.put("SERVLET_NAME", "B2CMainPlat_00");
        form.put("CCB_IBSVersion", "V6");
        form.put("PT_STYLE", "10");
        form.put("TXCODE", txcode);
        form.putAll(params);
        form.put("USERID", session.ccbUserid());
        form.put("SKEY", session.skey());

        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(urlEncode(k)).append('=').append(urlEncode(v == null ? "" : v));
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://app.xiaoyuan.ccb.com")
                .header("Referer", "https://app.xiaoyuan.ccb.com/EMTSTATIC/DZK2026091101/index2026091101.html")
                .header("User-Agent", UA)
                .header("Cookie", session.cookieHeader() == null ? "" : session.cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        String text;
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            text = resp.body();
        } catch (HttpTimeoutException e) {
            throw new CcbBusinessException("建行平台响应超时，稍后再试。");
        } catch (Exception e) {
            throw new CcbBusinessException("连不上建行平台：" + e.getMessage());
        }

        return parse(text, txcode);
    }

    /** 解析平台响应。信封是 {status, msg, data, ERRORCODE, ERRORMSG}。 */
    private JsonNode parse(String text, String txcode) {
        JsonNode root;
        try {
            root = mapper.readTree(text.trim());
        } catch (Exception e) {
            throw new CcbBusinessException("建行平台返回了非 JSON 内容（TXCODE=" + txcode + "）");
        }

        String status = root.path("status").asText("");
        if ("0".equals(status)) {
            return root.path("data");
        }

        String errorCode = root.path("ERRORCODE").asText("");
        String errorMsg = root.path("ERRORMSG").asText("");
        if (ERR_HISTORY_PAGE.equals(errorCode) || ERR_RELOGIN.equals(errorCode)) {
            throw new SessionExpiredException("会话已过期（" + errorCode + "）");
        }
        String detail = errorMsg.isEmpty() ? errorCode : errorMsg;
        throw new CcbBusinessException("建行平台报错：" + detail);
    }

    /** 查当前绑定宿舍（导入时 relay 已查过一次，这里是备用校验）。 */
    public CcbSessionStore.Room queryRoom(CcbSessionStore.Session session) {
        JsonNode data = post("DZ0392", session, Map.of("payType", "elec"));
        return new CcbSessionStore.Room(
                data.path("areaid").asText(""),
                data.path("areaname").asText(""),
                data.path("buildingid").asText(""),
                data.path("buildingname").asText(""),
                data.path("floorid").asText(""),
                data.path("floorname").asText(""),
                data.path("roomid").asText(""),
                data.path("room").asText(""));
    }

    /**
     * 查余额。必须带完整宿舍链，缺字段平台会报"第三方服务异常"。
     * 返回 mainFare（剩余金额元）/ bal（剩余电量度）/ subsidy_*（补助）。
     */
    public JsonNode queryBalance(CcbSessionStore.Session session) {
        CcbSessionStore.Room r = session.room();
        if (r == null || r.roomid() == null || r.roomid().isEmpty()) {
            throw new CcbBusinessException("会话里没有宿舍信息，请重新导入。");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("areaid", r.areaid());
        params.put("areaname", r.areaname());
        params.put("buildingid", r.buildingid());
        params.put("buildingname", r.buildingname());
        params.put("floorid", r.floorid());
        params.put("floorname", r.floorname());
        params.put("room", r.room());
        params.put("roomid", r.roomid());
        params.put("area", r.areaid());
        return post("YJF004", session, params);
    }

    /** 轻量探活：查一次校区列表，参数最便宜。成功说明会话还活着。 */
    public void ping(CcbSessionStore.Session session) {
        post("YJF006", session, Map.of("PayType", "elecdetails"));
    }

    /** ApiException 风格的业务错误。 */
    public static ApiException badRequest(String message) {
        return ApiException.badRequest(message);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
