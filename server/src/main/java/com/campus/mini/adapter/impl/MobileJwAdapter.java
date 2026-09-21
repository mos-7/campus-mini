package com.campus.mini.adapter.impl;

import com.campus.mini.adapter.AdapterException;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.Capability;
import com.campus.mini.adapter.LoginMode;
import com.campus.mini.adapter.RawProbe;
import com.campus.mini.adapter.model.Models.Course;
import com.campus.mini.adapter.model.Models.CourseSession;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.FetchResult;
import com.campus.mini.adapter.model.Models.VerifyResult;
import com.campus.mini.config.CampusProperties;
import com.campus.mini.schedule.WeekTextParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 「移动教务」类厂商 SaaS 适配器 —— <b>只读</b>：登录 + 读课表 + 读成绩。
 *
 * <h2>这类系统的形态</h2>
 *
 * <p>厂商的多租户产品：前端 SPA 由学校自行部署，后端 API 在厂商云或学校服务器上，
 * 地址由一个公开的 {@code serverconfig.json} 下发。认证是<b>自定义请求头 {@code token}</b>，
 * 成功码是 <b>{@code code == 1}</b>（不是 0）。
 *
 * <h2>不含任何具体学校信息</h2>
 *
 * <p>所有端点、密钥、开关都在 {@code campus.mobilejw.*} 配置里，取值放
 * {@code application-local.yml}（已 gitignore）。公开仓库里只有这个通用骨架。
 * 联调过程见 {@code docs/jwxt-adapter.md}。
 *
 * <h2>两种绑定方式</h2>
 * <ul>
 *   <li><b>学号 + 密码</b>：口令编码见 {@link MobileJwCrypto}（已逐字节验证）</li>
 *   <li><b>粘贴 token</b>：学号留空，密码框里粘 token。用于平台要求验证码、
 *       或密码登录因任何原因不可用时。<b>不做验证码识别。</b></li>
 * </ul>
 */
@Component
public class MobileJwAdapter implements CampusAdapter, RawProbe {

    public static final String CODE = "mobilejw";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 看起来像一整个 token 的串（token 模式下用它做识别）。 */
    private static final Pattern TOKEN_LIKE = Pattern.compile("^[A-Za-z0-9_.\\-]{20,}$");

    /** 课表接口返回的周次可能是位图，如 {@code 11111111111111110000}。 */
    private static final Pattern WEEK_BITMAP = Pattern.compile("^[01]{10,}$");

    /** 节次可能是 4 位或 6 位以上的紧凑写法，如 {@code 0102} 表示第 1-2 节。 */
    private static final Pattern SECTION_COMPACT = Pattern.compile("^\\d{4}$|^\\d{6,}$");

    // 字段名候选：各校厂商版本命名不一，宁可多列几个。全部小写比较。
    private static final String[] F_NAME = {"kcmc", "coursename", "kcname", "kcmcbz", "course", "name", "kcmc_bz"};
    private static final String[] F_TEACHER = {"jsxm", "teachername", "xm", "jsmc", "teacher", "skjs", "jsxm_mc"};
    private static final String[] F_ROOM = {"cdmc", "jsmc", "roomname", "classroom", "jxdd", "skdd", "jsmc_mc", "cdmc_mc"};
    private static final String[] F_DAY = {"xqj", "xingqi", "weekday", "xq", "xqjmc", "week"};
    private static final String[] F_SECTIONS = {"jcs", "jcor", "jcsmc", "sections", "section", "jc", "jcs_mc"};
    private static final String[] F_WEEKS = {"zcd", "zcmc", "weeks", "zc", "zcd_mc"};
    private static final String[] F_COURSE_ID = {"courseid", "kch", "kch_id", "kcid", "kcbh", "id"};

    private static final List<String> CAPTCHA_HINTS =
            List.of("验证码", "captcha", "图形码", "verifycode", "checkcode");

    private final CampusProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public MobileJwAdapter(CampusProperties properties) {
        this.properties = properties;
    }

    private CampusProperties.MobileJw cfg() {
        return properties.getMobileJw();
    }

    // ------------------------------------------------------------------
    // CampusAdapter
    // ------------------------------------------------------------------

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "移动教务";
    }

    @Override
    public String description() {
        return "读取你自己账号下的课表与成绩。只读，不涉及任何选课/退课等写操作。";
    }

    @Override
    public LoginMode loginMode() {
        return LoginMode.PASSWORD;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.COURSE_LIST, Capability.SCHEDULE, Capability.GRADE);
    }

    @Override
    public VerifyResult verify(Credential credential) {
        try {
            Session session = open(credential);
            List<CourseSession> sessions = fetchSessions(session);
            // displayName 传 null：账号标识由用户填的学号来定（见 BindingService.bind）。
            // 这里只给人类提示，别把消息塞进 displayName。
            if (sessions.isEmpty()) {
                return VerifyResult.ok(null,
                        "登录成功，但课表没解析出条目 —— 字段映射可能还没对上。"
                                + "可以先绑定，然后用调试接口看原始响应。");
            }
            return VerifyResult.ok(null, "登录成功，解析到 " + sessions.size() + " 条上课安排");
        } catch (AdapterException e) {
            return VerifyResult.fail(e.getMessage());
        }
    }

    @Override
    public FetchResult fetch(Credential credential) {
        Session session = open(credential);
        List<CourseSession> sessions = fetchSessions(session);

        List<Course> courses = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CourseSession s : sessions) {
            if (seen.add(s.courseExternalId())) {
                courses.add(new Course(s.courseExternalId(), s.courseName(), s.teacher(),
                        "", "", s.rawText()));
            }
        }

        String message = "读到 " + courses.size() + " 门课 / " + sessions.size() + " 条上课安排";
        if (sessions.isEmpty()) {
            message += "。接口通了但没解析出课表条目 —— 字段映射可能需要按你的版本调整，"
                    + "打开 campus.debug-endpoints 看原始响应（见 docs/jwxt-adapter.md）。";
        }
        return new FetchResult(courses, sessions, message);
    }

    /**
     * 联调用：把课表接口的原始响应吐出来，用来确认字段名。
     *
     * <p><b>这个方法不抛异常</b> —— 探测失败时把错误当成"内容"返回。
     * 否则异常会走全局异常处理，响应信封的 {@code data} 变成 null，
     * 调用方（比如联调脚本）只能看到一个空 data，看不到原因。<b>踩过。</b>
     */
    @Override
    public String probeRaw(Credential credential) {
        try {
            Session session = open(credential);
            String raw = callApi(cfg().getCurriculumPath(), curriculumParams(), session.token());
            return raw.length() > 6000
                    ? raw.substring(0, 6000) + "\n…（已截断，共 " + raw.length() + " 字符）"
                    : raw;
        } catch (RuntimeException e) {
            return "[探测失败] " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // 会话与登录
    // ------------------------------------------------------------------

    private record Session(String token) {
    }

    private Session open(Credential credential) {
        if (!cfg().isEnabled()) {
            throw new AdapterException(
                    "「移动教务」适配器未启用或未配置。\n"
                            + "需要在 application-local.yml 里填 campus.mobilejw.base-url 和 pwd-key，"
                            + "并把 enabled 设为 true。见 docs/jwxt-adapter.md。");
        }

        String userNo = credential.username() == null ? "" : credential.username().trim();
        String secret = credential.secret();

        // token 模式：学号留空 + 密码框里是一整串 token
        if (userNo.isEmpty() && secret != null && TOKEN_LIKE.matcher(secret.trim()).matches()) {
            return new Session(secret.trim());
        }

        if (userNo.isEmpty()) {
            throw new AdapterException("请填写学号。（或者：学号留空、把 token 粘到密码框里走 token 模式）");
        }
        return new Session(login(userNo, secret));
    }

    private String login(String userNo, String password) {
        if (password == null || password.isEmpty()) {
            throw new AdapterException("请填写密码。");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("userNo", userNo);
        params.put("pwd", MobileJwCrypto.encodePassword(password, cfg().getPwdKey()));
        params.put("encode", "1");
        // captchaData / codeVal：页面上没有验证码时前端不会带这两个字段（axios 省略 undefined），
        // 这里保持一致 —— 硬塞空串反而可能被服务端当成"你提交了空验证码"。

        String body = callApi(cfg().getLoginPath(), params, null);

        if (containsCaptcha(body)) {
            throw new AdapterException(
                    "该平台这次要求输入验证码，无法自动完成。\n"
                            + "本项目不做验证码识别。请改用 token 方式绑定："
                            + "先在浏览器登录，把会话里的 token 复制出来，学号留空、粘到密码框。");
        }

        JsonNode root = read(body);
        int code = root.path("code").asInt(Integer.MIN_VALUE);
        String msg = firstText(root, "Msg", "msg", "message", "errorMessage", "errmsg");

        if (code != cfg().getSuccessCode()) {
            throw new AdapterException("登录失败：" + (msg.isBlank() ? "学号或密码不正确" : msg));
        }

        String token = firstText(root.path("data"), "token", "Token", "accessToken");
        if (token.isBlank()) {
            throw new AdapterException("登录返回成功但没拿到 token，接口结构可能已变化。");
        }
        return token;
    }

    // ------------------------------------------------------------------
    // 课表抓取与解析
    // ------------------------------------------------------------------

    private Map<String, String> curriculumParams() {
        Map<String, String> params = new LinkedHashMap<>(cfg().getCurriculumParams());
        params.values().removeIf(v -> v == null || v.isBlank());
        return params;
    }

    private List<CourseSession> fetchSessions(Session session) {
        String body = callApi(cfg().getCurriculumPath(), curriculumParams(), session.token());
        JsonNode root = read(body);

        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != cfg().getSuccessCode()) {
            throw new AdapterException("取课表失败：" + firstText(root, "Msg", "msg", "message", "errorMessage"));
        }

        // 不硬编码 JSON 路径：凡是"同时含课程名 + 星期 + 节次"的对象都当一条记录。
        List<Map<String, String>> rows = new ArrayList<>();
        collectRows(root, rows);

        int totalWeeks = properties.getTerm().getTotalWeeks();
        List<CourseSession> out = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String name = first(row, F_NAME);
            String dayRaw = first(row, F_DAY);
            String secRaw = first(row, F_SECTIONS);
            if (name == null || dayRaw == null || secRaw == null) {
                continue;
            }
            int day = parseDay(dayRaw);
            int[] sec = parseSections(secRaw);
            if (day < 1 || sec == null) {
                continue;
            }

            String weekRaw = first(row, F_WEEKS);
            Set<Integer> weeks = parseWeeks(weekRaw, totalWeeks);
            if (weeks == null || weeks.isEmpty()) {
                // 没给周次就按全周处理，而不是丢掉这条课
                weeks = new TreeSet<>();
                for (int w = 1; w <= totalWeeks; w++) {
                    weeks.add(w);
                }
            }

            String id = first(row, F_COURSE_ID);
            if (id == null || id.isBlank()) {
                id = "mobilejw:" + name;
            }

            out.add(new CourseSession(
                    id, name,
                    orEmpty(first(row, F_TEACHER)),
                    orEmpty(first(row, F_ROOM)),
                    day, sec[0], sec[1], weeks,
                    row.toString()));
        }
        return out;
    }

    /**
     * 递归找出「像一行课表记录」的对象。
     *
     * <p>判据：该对象的<b>直接</b>字段里同时能匹配到课程名、星期、节次三类候选字段之一。
     * 这样既不需要知道确切的 JSON 路径，也不会把每一条都当记录。
     */
    private void collectRows(JsonNode node, List<Map<String, String>> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Map<String, String> flat = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (v != null && v.isValueNode() && !v.isNull()) {
                    flat.put(e.getKey().toLowerCase(), v.asText());
                }
            });

            if (first(flat, F_NAME) != null && first(flat, F_DAY) != null && first(flat, F_SECTIONS) != null) {
                out.add(flat);
            }
            node.fields().forEachRemaining(e -> collectRows(e.getValue(), out));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectRows(child, out);
            }
        }
    }

    private static int parseDay(String raw) {
        String s = raw.trim();
        if (s.matches("[1-7]")) {
            return Integer.parseInt(s);
        }
        return WeekTextParser.parseDayOfWeek(s);
    }

    /** 节次：支持 {@code 0102}（紧凑两位一节）、{@code 1-2}、{@code 第1-2节}。 */
    private static int[] parseSections(String raw) {
        String compact = raw.replaceAll("[^0-9]", "");
        if (SECTION_COMPACT.matcher(compact).matches()) {
            List<Integer> nums = new ArrayList<>();
            for (int i = 0; i + 2 <= compact.length(); i += 2) {
                nums.add(Integer.parseInt(compact.substring(i, i + 2)));
            }
            if (!nums.isEmpty()) {
                int min = nums.stream().mapToInt(Integer::intValue).min().orElse(1);
                int max = nums.stream().mapToInt(Integer::intValue).max().orElse(min);
                return new int[]{min, max};
            }
        }
        return WeekTextParser.parseSections(raw);
    }

    /** 周次：支持位图 {@code 11111111111111110000} 和区间 {@code 1-16}。 */
    private static Set<Integer> parseWeeks(String raw, int totalWeeks) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (WEEK_BITMAP.matcher(s).matches()) {
            Set<Integer> out = new TreeSet<>();
            for (int i = 0; i < s.length() && i < totalWeeks; i++) {
                if (s.charAt(i) == '1') {
                    out.add(i + 1);
                }
            }
            return out;
        }
        Set<Integer> parsed = WeekTextParser.parseWeeks(s, totalWeeks);
        return parsed.isEmpty() ? null : parsed;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /**
     * 调一个接口。
     *
     * <p>注意参数放在 <b>query string</b> 里 —— 原前端用的是 axios 的 {@code params}
     * （进 URL），不是 {@code data}（进 body）。搞错这个会一直 401。
     *
     * @param token 非 null 时带上 {@code token} 请求头
     */
    private String callApi(String path, Map<String, String> params, String token) {
        String base = cfg().getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + path + (params.isEmpty() ? "" : "?" + queryString(params));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .timeout(Duration.ofSeconds(cfg().getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.noBody());

        if (token != null && !token.isBlank()) {
            builder.header("token", token);   // ★ 这套系统的认证头就叫 token
        }

        try {
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new AdapterException("请求失败：HTTP " + response.statusCode()
                        + "（检查 base-url 是否正确、是否需要校园网）");
            }
            return response.body();
        } catch (IOException e) {
            throw new AdapterException(
                    "连不上教务系统（" + e.getClass().getSimpleName() + "）。"
                            + "如果这个地址只有校园网能访问，云托管后端也连不上，请改用手动导入。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdapterException("请求被中断", e);
        }
    }

    private static String queryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new AdapterException(
                    "接口返回的不是 JSON（可能被重定向到登录页，或需要校园网）。", e);
        }
    }

    private static boolean containsCaptcha(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String hint : CAPTCHA_HINTS) {
            if (lower.contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String first(Map<String, String> row, String[] candidates) {
        for (String key : candidates) {
            String v = row.get(key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isValueNode() && !v.isNull() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
