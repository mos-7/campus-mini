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
import com.campus.mini.schedule.WeekTextParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 手动导入适配器 —— 零风控风险的兜底方案。
 *
 * <p>用户把课表按行粘贴进来，不需要交出任何账号密码。建议<b>先用它把整个界面跑通</b>，
 * 再去啃超星/教务系统的接口。
 *
 * <p>每行一个上课安排，字段用逗号（半角或全角）、制表符分隔，<b>顺序无所谓</b> ——
 * 程序按内容识别哪段是星期、哪段是节次、哪段是周次。示例见 {@link #SAMPLE}。
 */
@Component
public class ManualAdapter implements CampusAdapter {

    public static final String CODE = "manual";

    /**
     * 演示课表 —— <b>刻意使用虚构数据</b>。
     *
     * <p>这个常量是测试夹具，不是你的真实课表。真实课表请通过小程序的
     * 「我的 → 服务绑定中心 → 手动导入课表」粘贴进去，存到数据库里。
     *
     * <p>为什么不放真实数据：这个仓库是公开的。真实教师姓名 + 教室 + 你所在校区
     * 组合起来是可识别的第三方个人信息，不适合进公开源码。而且夹具本来就该用虚构数据
     * ——真实数据会随学期变化，夹具应该稳定。
     *
     * <p>三行分别覆盖周次解析的三个分支：
     * {@code 1-16周}（连续）、{@code 1-16周(单)}（单周）、{@code 1-9周}（短周期）。
     * 解析器哪天改坏了，这三行会先出问题。
     */
    public static final String SAMPLE = """
            高等数学,张老师,周一,1-2节,1-16周,教三201
            线性代数,李老师,周三,3-4节,1-16周(单),教二105
            大学物理,王老师,周五,5-6节,1-9周,实验楼B302""";

    /** 只含数字和范围符号，看起来像周次的范围。 */
    private static final Pattern WEEK_LIKE = Pattern.compile("^\\d+\\s*[-~—]\\s*\\d+.*$");

    private final CampusProperties properties;

    public ManualAdapter(CampusProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "手动导入课表";
    }

    @Override
    public String description() {
        return "把课表按行粘贴进来，不用交出账号密码。建议先用它把界面跑通。";
    }

    @Override
    public LoginMode loginMode() {
        return LoginMode.MANUAL;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.COURSE_LIST, Capability.SCHEDULE);
    }

    @Override
    public VerifyResult verify(Credential credential) {
        Parsed parsed = parse(credential.secret());
        if (parsed.sessions().isEmpty()) {
            return VerifyResult.fail("没解析出任何上课安排。每行格式示例：\n" + SAMPLE);
        }
        return VerifyResult.ok("解析到 " + parsed.sessions().size() + " 条上课安排");
    }

    @Override
    public FetchResult fetch(Credential credential) {
        Parsed parsed = parse(credential.secret());
        if (parsed.sessions().isEmpty()) {
            throw new AdapterException("课表文本里没有解析出任何上课安排，请检查格式。示例：\n" + SAMPLE);
        }
        String message = "解析 " + parsed.sessions().size() + " 条上课安排";
        if (!parsed.warnings().isEmpty()) {
            message += "；" + parsed.warnings().size() + " 行有问题已跳过";
        }
        return new FetchResult(parsed.courses(), parsed.sessions(), message);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private record Parsed(List<Course> courses, List<CourseSession> sessions, List<String> warnings) {
    }

    private Parsed parse(String rawText) {
        List<Course> courses = new ArrayList<>();
        List<CourseSession> sessions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seenCourses = new LinkedHashSet<>();

        if (rawText == null || rawText.isBlank()) {
            return new Parsed(courses, sessions, warnings);
        }

        int lineNo = 0;
        for (String rawLine : rawText.split("\\R")) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            String[] tokens = line.split("[,，\\t]+");
            Integer dayOfWeek = null;
            int[] sections = null;
            Set<Integer> weeks = null;
            List<String> freeText = new ArrayList<>();

            for (String rawToken : tokens) {
                String token = rawToken.trim();
                if (token.isEmpty()) {
                    continue;
                }

                // 1) 星期几？必须在周次之前判断，因为两者都含「周」
                if (dayOfWeek == null && isDayToken(token)) {
                    int d = WeekTextParser.parseDayOfWeek(token);
                    if (d > 0) {
                        dayOfWeek = d;
                        continue;
                    }
                }

                // 2) 节次？
                if (sections == null && token.contains("节")) {
                    int[] s = WeekTextParser.parseSections(token);
                    if (s != null) {
                        sections = s;
                        continue;
                    }
                }

                // 3) 周次？要求含「周」，或是纯数字区间
                if (weeks == null && (token.contains("周") || WEEK_LIKE.matcher(token).matches())) {
                    Set<Integer> w = WeekTextParser.parseWeeks(token, properties.getTerm().getTotalWeeks());
                    if (!w.isEmpty()) {
                        weeks = w;
                        continue;
                    }
                }

                // 4) 剩下的是课程名 / 教师 / 教室
                freeText.add(token);
            }

            if (freeText.isEmpty() || dayOfWeek == null || sections == null) {
                warnings.add("第 " + lineNo + " 行：缺少课程名、星期或节次，已跳过");
                continue;
            }

            String courseName = freeText.get(0);
            String teacher = freeText.size() > 1 ? freeText.get(1) : "";
            String location = freeText.size() > 2 ? freeText.get(2) : "";

            if (weeks == null) {
                // 没写周次 = 全周
                Set<Integer> all = new LinkedHashSet<>();
                for (int i = 1; i <= properties.getTerm().getTotalWeeks(); i++) {
                    all.add(i);
                }
                weeks = all;
            }

            String externalId = "manual:" + courseName;
            if (seenCourses.add(courseName)) {
                courses.add(new Course(externalId, courseName, teacher, "", "", ""));
            }

            sessions.add(new CourseSession(
                    externalId,
                    courseName,
                    teacher,
                    location,
                    dayOfWeek,
                    sections[0],
                    sections[1],
                    weeks,
                    line
            ));
        }

        return new Parsed(courses, sessions, warnings);
    }

    /**
     * 判断一个 token 是不是「星期几」而不是「第几周」。
     *
     * <p>{@code 周一} / {@code 星期三} / {@code 周天} → true；
     * {@code 1-16周} / {@code 全周} → false。
     */
    private boolean isDayToken(String token) {
        if (token.contains("星期") || token.contains("礼拜")) {
            return true;
        }
        // 「周一」「周天」「周7」这种：周后面紧跟一个单字符
        if (token.matches("^周\\s*[一二三四五六日天1-7]$")) {
            return true;
        }
        // 纯数字 1-7 也可能是星期，但太容易和节次混淆，不认
        return false;
    }
}
