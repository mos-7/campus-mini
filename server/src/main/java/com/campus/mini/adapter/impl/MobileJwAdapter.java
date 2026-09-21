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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 「移动教务」类厂商 SaaS 适配器 —— <b>只读</b>：登录 + 读课表。
 *
 * <h2>这类系统的形态</h2>
 *
 * <p>厂商的多租户产品：前端 SPA 由学校自行部署，后端 API 在厂商云或学校服务器上，
 * 地址由一个公开的 {@code serverconfig.json} 下发。认证是<b>自定义请求头 {@code token}</b>。
 *
 * <h2>★ 三个已用真实数据确认的坑</h2>
 *
 * <ol>
 *   <li><b>成功码是 {@code "1"}，而且是<b>字符串</b></b>不是数字
 *       （{@code {"code":"1","Msg":"success~"}}）。所以判断要同时兼容两种类型。</li>
 *   <li><b>字段名和正方那套完全没有关系。</b>这家厂商用的是全拼：
 *       {@code courseName} / {@code weekDay} / {@code weekNoteDetail} /
 *       {@code classWeekDetails} / {@code classroomName} / {@code jx0404id}。
 *       一开始按正方的 {@code kcmc}/{@code xqj}/{@code jcs}/{@code zcd} 猜，
 *       结果一条都匹配不上 —— 这就是为什么必须看真实响应，不能猜。</li>
 *   <li><b>节次是编码的，不是"第几节"：</b>
 *       <pre>
 *       weekNoteDetail: "401,402,403,404"  → 每 3 位 = 1 位星期 + 2 位节次 → 周四 1,2,3,4 节
 *       classTime:      "401020304"        → 首字符=星期，其后每 2 位=节次  → 同上
 *       </pre>
 *       两者都和 {@code startTime/endTIme} 吻合。（注意厂商把 endTime 拼成了
 *       {@code endTIme}，大写 I。）</li>
 * </ol>
 *
 * <h2>为什么逐周抓</h2>
 *
 * <p>课表接口按周返回（不带参数时给当前周），每门课自带它的完整周次范围。
 * 原前端传的是 {@code {week: <周次>, kbjcmsid: <节次模式>}}。
 * 所以这里遍历 {@code week=1..N} 再按"课程+星期+节次"去重合并，
 * 这样不会漏掉"只在第 10 周以后才上"的课。
 *
 * <h2>不含任何具体学校信息</h2>
 *
 * <p>端点、密钥、开关都在 {@code campus.mobilejw.*}，取值放 {@code application-local.yml}
 * （已 gitignore）。要接你自己的学校，见 {@code docs/jwxt-adapter.md}。
 */
@Component
public class MobileJwAdapter implements CampusAdapter, RawProbe {

    private static final Logger log = LoggerFactory.getLogger(MobileJwAdapter.class);

    public static final String CODE = "mobilejw";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 看起来像一整个 token 的串（token 模式下用它做识别）。 */
    private static final Pattern TOKEN_LIKE = Pattern.compile("^[A-Za-z0-9_.\\-]{20,}$");

    /** 周次位图，如 {@code 11111111111111110000}。 */
    private static final Pattern WEEK_BITMAP = Pattern.compile("^[01]{10,}$");

    /** 紧凑节次 4 位（{@code 0102}）或 6 位以上。 */
    private static final Pattern SECTION_COMPACT = Pattern.compile("^\\d{4}$|^\\d{6,}$");

    /** 本厂商格式 A：逗号分隔的 3 位码，如 {@code 103,104}。 */
    private static final Pattern SECTION_TRIPLET = Pattern.compile("^\\d{3}(,\\d{3})*$");

    // ------------------------------------------------------------------
    // 字段名候选。全部小写比较。
    // 第一组是这家厂商实际用的（已用真实响应确认），后面是别的版本可能用的。
    // ------------------------------------------------------------------

    private static final String[] F_NAME =
            {"coursename", "kcmc", "kcname", "kcmcbz", "course", "name"};
    private static final String[] F_TEACHER =
            {"teachername", "jsxm", "xm", "jsmc", "teacher", "skjs"};
    private static final String[] F_ROOM =
            {"classroomname", "location", "cdmc", "jsmc", "roomname", "jxdd", "skdd"};
    private static final String[] F_DAY =
            {"weekday", "xqj", "xingqi", "xq", "xqjmc"};
    private static final String[] F_SECTIONS =
            {"weeknotedetail", "classtime", "jcs", "jcor", "jcsmc", "sections", "section", "jc"};
    private static final String[] F_WEEKS =
            {"classweekdetails", "classweek", "zcd", "zcmc", "weeks", "zc"};
    private static final String[] F_COURSE_ID =
            {"jx0404id", "courseid", "kch", "kch_id", "kcid", "kcbh", "id"};

    private static final List<String> CAPTCHA_HINTS =
            List.of("验证码", "captcha", "图形码", "verifycode", "checkcode");

    /** 逐周抓之间的小停顿，别把人家服务器当压测目标。 */
    private static final long WEEK_FETCH_GAP_MS = 120;

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
            // displayName 传 null：账号标识由用户填的用户名定（见 BindingService.bind），
            // 这里只给人类提示，别把消息塞进 displayName（踩过）。
            if (sessions.isEmpty()) {
                return VerifyResult.ok(null, "登录成功，但课表没解析出条目，可能字段映射还需调整。");
            }
            // ★ 类型必须是 TreeSet/NavigableSet，不能声明成 Set ——
            //   Set 接口没有 first()/last()，编译不过（刚踩）。
            TreeSet<Integer> weeksSorted = new TreeSet<>();
            for (CourseSession s : sessions) {
                weeksSorted.addAll(s.weeks());
            }
            return VerifyResult.ok(null, "登录成功，解析到 " + sessions.size() + " 条上课安排，"
                    + "覆盖第 " + weeksSorted.first() + "-" + weeksSorted.last() + " 周");
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
            message += "。接口通了但没解析出条目 —— 字段映射可能还需按你的版本调整。";
        }
        return new FetchResult(courses, sessions, message);
    }

    /**
     * 联调用：把课表接口的原始响应吐出来，用来确认字段名。
     *
     * <p><b>不抛异常</b> —— 失败时把错误当成"内容"返回。否则异常会走全局异常处理，
     * 响应信封的 {@code data} 变成 null，调用方只能看到空 data 看不到原因。<b>踩过。</b>
     */
    @Override
    public String probeRaw(Credential credential) {
        try {
            Session session = open(credential);
            // 探测默认只拉当前周（不带参数），响应小、看得清
            String raw = callApi(cfg().getCurriculumPath(), curriculumParams(), session.token(), false);
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
            throw new AdapterException("请填写学号。（或：学号留空，把 token 粘到密码框里走 token 模式）");
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
        // captchaData / codeVal：页面上没有验证码时前端不会带这两个字段，
        // 这里保持一致 —— 硬塞空串可能被服务端当成"你提交了空验证码"。

        String body = callApi(cfg().getLoginPath(), params, null, true);

        if (containsCaptcha(body)) {
            throw new AdapterException(
                    "该平台这次要求输入验证码，无法自动完成。\n"
                            + "本项目不做验证码识别。请改用 token 方式绑定："
                            + "先在浏览器登录，把会话里的 token 复制出来，学号留空、粘到密码框。");
        }

        JsonNode root = read(body);
        int code = codeOf(root);
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
    // 课表抓取
    // ------------------------------------------------------------------

    private Map<String, String> curriculumParams() {
        Map<String, String> params = new LinkedHashMap<>(cfg().getCurriculumParams());
        params.values().removeIf(v -> v == null || v.isBlank());
        return params;
    }

    /**
     * 抓课表。
     *
     * <p>课表接口按周返回，所以默认逐周抓 {@code week=1..N} 再合并。
     * 某一周失败不影响整体（学校服务器偶尔抽风很正常）。
     * 逐周全部失败时退回单次请求（至少拿到当前周）。
     */
    private List<CourseSession> fetchSessions(Session session) {
        List<CourseSession> all = new ArrayList<>();

        if (cfg().isFetchByWeek()) {
            int total = Math.max(1, properties.getTerm().getTotalWeeks());
            Map<String, CourseSession> merged = new LinkedHashMap<>();
            int okWeeks = 0;

            for (int week = 1; week <= total; week++) {
                Map<String, String> params = new LinkedHashMap<>(curriculumParams());
                params.put("week", String.valueOf(week));
                try {
                    JsonNode root = callCurriculum(session, params);
                    for (CourseSession s : parseSessions(root)) {
                        merged.putIfAbsent(dedupeKey(s), s);
                    }
                    okWeeks++;
                } catch (RuntimeException e) {
                    log.warn("抓第 {} 周课表失败，跳过：{}", week, e.getMessage());
                }
                sleepQuietly(WEEK_FETCH_GAP_MS);
            }

            log.info("逐周抓课表完成：成功 {} / {} 周，合并得到 {} 条上课安排",
                    okWeeks, total, merged.size());
            all = new ArrayList<>(merged.values());
        }

        if (all.isEmpty()) {
            // 退回单次请求（不带 week 参数）—— 各校默认返回当前周
            all = parseSessions(callCurriculum(session, curriculumParams()));
        }
        return all;
    }

    private JsonNode callCurriculum(Session session, Map<String, String> params) {
        String body = callApi(cfg().getCurriculumPath(), params, session.token(), true);
        JsonNode root = read(body);
        int code = codeOf(root);
        if (code != cfg().getSuccessCode()) {
            String msg = firstText(root, "Msg", "msg", "message", "errorMessage", "errmsg");
            throw new AdapterException("取课表失败：" + (msg.isBlank() ? "code=" + code : msg));
        }
        return root;
    }

    /** 去重键：同一门课在同一星期同一节次只留一条（跨周抓回来的是同一门课）。 */
    private static String dedupeKey(CourseSession s) {
        return s.courseExternalId() + "|" + s.dayOfWeek() + "|" + s.startSection() + "-" + s.endSection();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    /**
     * 把课表响应解析成上课安排。
     *
     * <p>包级可见（不是 private）是为了让 {@code MobileJwParserTest} 能直接喂真实响应进来测。
     */
    List<CourseSession> parseSessions(JsonNode root) {
        // 不硬编码 JSON 路径：凡是"同时含课程名 + 星期 + 节次"的对象都当一条记录。
        // 真实响应里它们在 data[0].courses[]，而同样的对象在 data[0].item[][] 里又出现一次，
        // 所以最后按"课程+星期+节次"去重。
        List<Map<String, String>> rows = new ArrayList<>();
        collectRows(root, rows);

        int totalWeeks = properties.getTerm().getTotalWeeks();
        Map<String, CourseSession> byKey = new LinkedHashMap<>();

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

            Set<Integer> weeks = parseWeeks(first(row, F_WEEKS), totalWeeks);
            if (weeks == null || weeks.isEmpty()) {
                weeks = new TreeSet<>();
                for (int w = 1; w <= totalWeeks; w++) {
                    weeks.add(w);
                }
            }

            String id = first(row, F_COURSE_ID);
            if (id == null || id.isBlank()) {
                id = "mobilejw:" + name;
            }

            CourseSession session = new CourseSession(
                    id, name,
                    orEmpty(first(row, F_TEACHER)),
                    orEmpty(first(row, F_ROOM)),
                    day, sec[0], sec[1], weeks,
                    // 原始文本留几个关键字段，排查时一眼能看出解析对不对
                    "weekNoteDetail=" + secRaw
                            + " classWeek=" + orEmpty(row.get("classweek"))
                            + " time=" + orEmpty(row.get("starttime")) + "-" + orEmpty(row.get("endtime")));
            byKey.putIfAbsent(dedupeKey(session), session);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * 递归找出「像一行课表记录」的对象。
     *
     * <p>判据：该对象的<b>直接</b>字段里同时能匹配到课程名、星期、节次三类候选字段。
     * 真实响应里 {@code courses[]} 和 {@code item[][]} 都满足，会被重复收集 ——
     * 交给 {@link #dedupeKey} 去重。
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

    /**
     * 星期几 → 1..7。
     *
     * <p>★ 这家厂商用 <b>{@code "0"} 表示周日</b>（见响应里 {@code date[].xqid}
     * 是 1,2,3,4,5,6,<b>0</b>）。所以 0 要映射成 7。
     */
    private static int parseDay(String raw) {
        String s = raw.trim();
        if (s.matches("[1-7]")) {
            return Integer.parseInt(s);
        }
        if ("0".equals(s)) {
            return 7;
        }
        int fromText = WeekTextParser.parseDayOfWeek(s);
        if (fromText > 0) {
            return fromText;
        }
        if (s.matches("\\d+")) {
            int n = Integer.parseInt(s);
            return n == 0 ? 7 : (n >= 1 && n <= 7 ? n : -1);
        }
        return -1;
    }

    /**
     * 节次解析。按格式依次尝试：
     *
     * <ol>
     *   <li><b>本厂商格式 A</b>：{@code "401,402,403,404"} —— 逗号分隔 3 位码，
     *       第 1 位是星期，后 2 位是节次</li>
     *   <li><b>本厂商格式 B</b>：{@code "401020304"} —— 首字符星期，其后每 2 位一节。
     *       长度必为<b>奇数</b>，靠这点和格式 C 区分开</li>
     *   <li><b>格式 C</b>：{@code "0102"} —— 纯 2 位一节，从 0 开始切</li>
     *   <li>交给 {@link WeekTextParser#parseSections}（处理 {@code "1-2节"} 这种）</li>
     * </ol>
     */
    private static int[] parseSections(String raw) {
        String s = raw.trim();

        // 格式 A
        if (SECTION_TRIPLET.matcher(s).matches()) {
            List<Integer> nums = new ArrayList<>();
            for (String tok : s.split(",")) {
                nums.add(Integer.parseInt(tok.substring(1)));
            }
            return minMax(nums);
        }

        String digits = s.replaceAll("[^0-9]", "");

        // 格式 B：奇数长度（1 位星期 + 偶数个节次位）
        if (s.matches("\\d+") && digits.length() >= 5 && digits.length() % 2 == 1) {
            List<Integer> nums = new ArrayList<>();
            for (int i = 1; i + 2 <= digits.length(); i += 2) {
                nums.add(Integer.parseInt(digits.substring(i, i + 2)));
            }
            if (!nums.isEmpty()) {
                return minMax(nums);
            }
        }

        // 格式 C
        if (SECTION_COMPACT.matcher(digits).matches()) {
            List<Integer> nums = new ArrayList<>();
            for (int i = 0; i + 2 <= digits.length(); i += 2) {
                nums.add(Integer.parseInt(digits.substring(i, i + 2)));
            }
            if (!nums.isEmpty()) {
                return minMax(nums);
            }
        }

        return WeekTextParser.parseSections(raw);
    }

    private static int[] minMax(List<Integer> nums) {
        if (nums == null || nums.isEmpty()) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int n : nums) {
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        return new int[]{min, max};
    }

    /** 周次：支持位图 {@code 11111111111111110000}、区间 {@code 3-4,6-7}、明细 {@code ,3,4,6,7,}。 */
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
     * @param token       非 null 时带上 {@code token} 请求头
     * @param throwOnHttp 非 2xx 时是否抛异常。逐周抓时传 true 便于把失败周记进日志
     */
    private String callApi(String path, Map<String, String> params, String token, boolean throwOnHttp) {
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
                String hint = response.statusCode() == 401 || response.statusCode() == 403
                        ? "（token 可能已过期，重新绑定即可）"
                        : "（检查 base-url 是否正确、是否需要校园网）";
                throw new AdapterException("请求失败：HTTP " + response.statusCode() + hint);
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

    /**
     * 取响应里的 {@code code}。
     *
     * <p>★ 这套系统的 {@code code} 是<b>字符串</b>（{@code {"code":"1"}}），
     * 但别的接口可能是数字。两种都要认。
     */
    private static int codeOf(JsonNode root) {
        JsonNode n = root.path("code");
        if (n.isNumber()) {
            return n.asInt();
        }
        String s = n.asText("").trim();
        if (s.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
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
