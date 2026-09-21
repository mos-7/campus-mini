package com.campus.mini.adapter.impl;

import com.campus.mini.adapter.AdapterException;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.Capability;
import com.campus.mini.adapter.LoginMode;
import com.campus.mini.adapter.model.Models.Course;
import com.campus.mini.adapter.model.Models.CourseSession;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.FetchResult;
import com.campus.mini.adapter.model.Models.VerifyResult;
import com.campus.mini.config.CampusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 超星学习通适配器 —— <b>只读</b>：登录、拿课程列表。不碰视频进度、不碰答题、不碰签到。
 *
 * <h2>⚠️ 当前状态：结构完整，接口待你抓包确认</h2>
 *
 * <p>我没有凭空编造超星的接口细节。下面代码里凡是标了
 * {@code 【待确认】} 的地方，都需要你用<b>自己的账号</b>抓包核对一遍，
 * 步骤见 {@code docs/chaoxing.md}。
 *
 * <p>默认 {@code campus.chaoxing.enabled=false}，此时本适配器在前端显示为
 * 「未开放」，不影响其他功能。
 *
 * <h2>两种绑定方式</h2>
 * <ul>
 *   <li><b>账号密码</b>：走 {@code /fanyalogin}。<b>坑</b>：超星登录页的 JS 在部分版本里
 *       会对密码做 AES 加密再提交，而且可能弹验证码。</li>
 *   <li><b>粘贴 Cookie</b>：把浏览器里 {@code .chaoxing.com} 的 Cookie 整串粘进来，
 *       作为 secret，username 留空即可。见 {@link #looksLikeCookie}。</li>
 * </ul>
 *
 * <h2>遇到验证码怎么办</h2>
 *
 * <p><b>不绕过。</b> 本适配器检测到验证码会直接失败，并提示用户改用 Cookie 方式。
 * 用 ddddocr 之类去破解风控验证码是 MoocPass 那类项目的做法，本项目不做
 * ——即使目的只是读自己的课表，也不去拆别人的防护措施。
 */
@Component
public class ChaoxingAdapter implements CampusAdapter {

    public static final String CODE = "chaoxing";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final CampusProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public ChaoxingAdapter(CampusProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "超星学习通";
    }

    @Override
    public String description() {
        return "读取你自己账号下的课程列表。只读，不涉及任何学习进度操作。";
    }

    @Override
    public LoginMode loginMode() {
        return LoginMode.PASSWORD;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.COURSE_LIST, Capability.SCHEDULE);
    }

    @Override
    public VerifyResult verify(Credential credential) {
        try {
            Session session = open(credential);
            List<Course> courses = fetchCourses(session);
            if (courses.isEmpty()) {
                return VerifyResult.fail("登录成功，但这个账号下没查到课程。");
            }
            return VerifyResult.ok("登录成功，查到 " + courses.size() + " 门课");
        } catch (AdapterException e) {
            return VerifyResult.fail(e.getMessage());
        }
    }

    @Override
    public FetchResult fetch(Credential credential) {
        if (!properties.getChaoxing().isEnabled()) {
            throw new AdapterException(
                    "超星适配器还没完成联调，当前是关闭状态。\n"
                            + "先用「手动导入」把课表跑通，或按 docs/chaoxing.md 完成抓包确认后，"
                            + "把 campus.chaoxing.enabled 改成 true。");
        }

        Session session = open(credential);
        List<Course> courses = fetchCourses(session);

        // 超星的课程列表接口通常不带精确的上课时间/教室，
        // 所以这里大概率拿不到课表格子。不猜、不编，如实说明。
        List<CourseSession> sessions = new ArrayList<>();

        String message = "从超星取到 " + courses.size() + " 门课。";
        if (sessions.isEmpty()) {
            message += "该接口没有返回上课时间/教室，课表需要用「手动导入」补全。";
        }
        return new FetchResult(courses, sessions, message);
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /**
     * 一个已登录的会话。
     *
     * @param client       HttpClient（内含 CookieManager，账号密码登录时用）
     * @param cookieHeader 用户直接粘贴的 Cookie 串；为 null 表示走账号密码登录
     */
    private record Session(HttpClient client, String cookieHeader) {

        /** 给请求加上 Cookie 头（只有 Cookie 模式需要）。 */
        HttpRequest.Builder authorized(HttpRequest.Builder builder) {
            return cookieHeader == null ? builder : builder.header("Cookie", cookieHeader);
        }
    }

    /**
     * 建立会话。
     *
     * <p>Cookie 模式下<b>不</b>做登录请求 —— 直接把用户粘贴的 Cookie 挂到后续请求头上，
     * 这样用户永远不用把密码交出来。
     */
    private Session open(Credential credential) {
        Session session = newSession();
        if (looksLikeCookie(credential.secret())) {
            return new Session(session.client(), credential.secret().trim());
        }
        login(session.client(), credential.username(), credential.secret());
        return session;
    }

    private Session newSession() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new Session(client, null);
    }

    /**
     * 判断 secret 是不是一整串 Cookie。
     *
     * <p>启发式：含 {@code ;} 和 {@code =}，且不含空格和换行。
     * 目的是让"不想交密码"的用户能直接粘 Cookie。
     */
    static boolean looksLikeCookie(String secret) {
        if (secret == null || secret.length() < 16) {
            return false;
        }
        return secret.contains(";") && secret.contains("=")
                && !secret.contains(" ") && !secret.contains("\n") && !secret.contains("\r");
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    private void login(HttpClient client, String username, String password) {
        String url = properties.getChaoxing().getLoginUrl();

        // 【待确认 1】超星登录页在部分版本会先 GET 一次以种下 JSESSIONID / 风控 cookie。
        //             先抓包看看你的学校是不是这样；如果是，在这里补一次 GET。
        // 【待确认 2】/fanyalogin 的 password 字段：某些版本要求前端 JS 先做 AES 加密。
        //             抓包时重点看提交的 password 是明文还是 32/64 位十六进制串。
        //             如果是密文，这里有活要干（但那是"让自己的登录请求可用"，
        //             不是绕过风控 —— 请如实实现，不要引入验证码破解）。
        String form = "fid=&uname=" + enc(username)
                + "&password=" + enc(password)
                + "&refer=" + enc("https://i.chaoxing.com")
                + "&t=" + System.currentTimeMillis()
                + "&forbidotherlogin=0";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", "https://passport2.chaoxing.com/login")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        String body = send(client, request, "登录请求失败");

        // 验证码：如实报错，不绕过
        if (body.contains("验证码") || body.contains("captcha") || body.contains("9010")) {
            throw new AdapterException(
                    "超星要求输入验证码，无法自动完成。\n"
                            + "请改用 Cookie 方式绑定：浏览器登录学习通后，"
                            + "把 .chaoxing.com 域下的 Cookie 整串复制过来粘贴。");
        }

        try {
            JsonNode node = json.readTree(body);
            JsonNode status = node.get("status");
            boolean ok = status != null && status.asBoolean(false);
            if (!ok) {
                String msg = node.hasNonNull("msg") ? node.get("msg").asText() : "账号或密码不正确";
                throw new AdapterException("超星登录失败：" + msg);
            }
        } catch (IOException e) {
            // 【待确认 3】如果不是 JSON（比如返回了 HTML 登录页），说明接口形态和预期不同。
            //             抓包对比一下，然后改这里的判断。不要靠字符串猜。
            throw new AdapterException(
                    "超星登录返回的不是预期格式，接口形态可能已变化。请对照 docs/chaoxing.md 重新抓包确认。", e);
        }
    }

    // ------------------------------------------------------------------
    // 课程列表
    // ------------------------------------------------------------------

    private List<Course> fetchCourses(Session session) {
        String url = properties.getChaoxing().getCourseListUrl();

        HttpRequest request = session.authorized(HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .header("Referer", "https://i.chaoxing.com/base")
                        .header("Accept", "application/json, text/plain, */*")
                        .timeout(Duration.ofSeconds(20)))
                .GET()
                .build();

        String body = send(session.client(), request, "获取课程列表失败");

        if (body.contains("passport") || body.contains("登录")) {
            throw new AdapterException("超星 Cookie 已过期，请重新绑定。");
        }

        try {
            JsonNode root = json.readTree(body);
            List<Course> courses = new ArrayList<>();
            // 不硬编码 JSON 路径 —— 超星各校版本响应结构有差异。
            // 递归找「有 name 且有 courseid/clazzid」的对象，鲁棒得多。
            collectCourses(root, courses, new LinkedHashSet<>());
            return courses;
        } catch (IOException e) {
            throw new AdapterException("超星课程列表返回的不是合法 JSON，请重新抓包确认接口。", e);
        }
    }

    /**
     * 递归抽取课程。凡是带 {@code name} 且带 {@code courseid} 或 {@code clazzid}
     * 的对象都算一门课。
     */
    private void collectCourses(JsonNode node, List<Course> out, Set<String> seen) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode name = node.get("name");
            JsonNode courseId = node.get("courseid");
            JsonNode clazzId = node.get("clazzid");

            if (name != null && name.isTextual() && !name.asText().isBlank()
                    && (courseId != null || clazzId != null)) {
                String id = courseId != null ? courseId.asText() : clazzId.asText();
                if (seen.add(id + "|" + name.asText())) {
                    out.add(new Course(
                            id,
                            name.asText(),
                            text(node, "teacher"),
                            text(node, "clazzname"),
                            text(node, "imageurl"),
                            text(node, "coursetime")));
                }
            }
            node.fields().forEachRemaining(entry -> collectCourses(entry.getValue(), out, seen));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectCourses(child, out, seen);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() ? value.asText() : "";
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private String send(HttpClient client, HttpRequest request, String what) {
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new AdapterException(what + "：HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new AdapterException(what + "：网络错误（" + e.getClass().getSimpleName() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdapterException(what + "：被中断", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
