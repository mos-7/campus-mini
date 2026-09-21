package com.campus.mini.schedule;

import com.campus.mini.adapter.model.Models.CourseSession;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 课表视图组装：把库里扁平的「上课安排」，变成前端能直接渲染的结构。
 *
 * <p>这一层不碰任何外部平台，纯计算 —— 所以它是最该写单元测试的部分。
 *
 * <h2>演示数据回退</h2>
 *
 * <p>用户<b>一条课表数据都没有</b>时，如果演示数据开着，就返回
 * {@link DemoScheduleProvider} 的内容，并把 {@code demo} 标成 true，
 * 前端会显示「示例数据」横幅。
 *
 * <p>这么做主要是为了过微信审核：审核员打开就是全新用户，拿不到"测试账号"，
 * 空白页会被判「功能不完整」。详见 {@code docs/publish-miniprogram.md}。
 */
@Service
public class ScheduleService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] WEEKDAY_LABELS = {
            "", "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    };

    private final ScheduleStore store;
    private final WeekCalculator weeks;
    private final DemoScheduleProvider demo;

    public ScheduleService(ScheduleStore store, WeekCalculator weeks, DemoScheduleProvider demo) {
        this.store = store;
        this.weeks = weeks;
        this.demo = demo;
    }

    /** 课表里的一格。 */
    public record SessionView(
            String courseName,
            String teacher,
            String location,
            int dayOfWeek,
            String weekdayLabel,
            int startSection,
            int endSection,
            List<Integer> weeks,
            String rawText
    ) {
        static SessionView of(ScheduleStore.StoredSession s) {
            return new SessionView(
                    s.courseName(), s.teacher(), s.location(),
                    s.dayOfWeek(), labelOf(s.dayOfWeek()),
                    s.startSection(), s.endSection(),
                    new ArrayList<>(s.weeks()), s.rawText());
        }

        static SessionView of(CourseSession s) {
            return new SessionView(
                    s.courseName(), s.teacher(), s.location(),
                    s.dayOfWeek(), labelOf(s.dayOfWeek()),
                    s.startSection(), s.endSection(),
                    new ArrayList<>(s.weeks()), s.rawText());
        }
    }

    /**
     * 某一周的完整课表。
     *
     * @param demo sessions 是否来自演示数据 —— 前端据此显示「示例」横幅
     */
    public record WeekView(
            int week,
            int totalWeeks,
            String mondayDate,
            String sundayDate,
            int sectionsPerDay,
            int noonSection,
            int eveningSection,
            boolean demo,
            List<SessionView> sessions
    ) {
    }

    /** 今日课程。{@code demo} 含义同上。 */
    public record TodayView(
            String date,
            int week,
            int dayOfWeek,
            String weekdayLabel,
            boolean inTerm,
            boolean demo,
            List<SessionView> sessions
    ) {
    }

    // ------------------------------------------------------------------

    /** 取第 {@code week} 周的课表。{@code week <= 0} 时取当前周。 */
    public WeekView week(long userId, int week) {
        int target = week > 0 ? week : weeks.currentWeek();
        int total = weeks.totalWeeks();
        int clamped = Math.max(1, Math.min(target, total));

        LocalDate monday = weeks.mondayOfWeek(clamped);
        LocalDate sunday = monday.plusDays(6);

        boolean usingDemo = shouldUseDemo(userId);

        List<SessionView> sessions = usingDemo
                ? demo.sessions().stream()
                        .filter(s -> s.weeks().contains(clamped))
                        .sorted(Comparator.comparingInt(CourseSession::dayOfWeek)
                                .thenComparingInt(CourseSession::startSection))
                        .map(SessionView::of)
                        .toList()
                : store.listSessions(userId).stream()
                        .filter(s -> s.weeks().contains(clamped))
                        .sorted(Comparator.comparingInt(ScheduleStore.StoredSession::dayOfWeek)
                                .thenComparingInt(ScheduleStore.StoredSession::startSection))
                        .map(SessionView::of)
                        .toList();

        return new WeekView(
                clamped, total,
                monday.format(DATE), sunday.format(DATE),
                weeks.sectionsPerDay(), weeks.noonSection(), weeks.eveningSection(),
                usingDemo && !sessions.isEmpty(),
                sessions);
    }

    /** 今日课程。 */
    public TodayView today(long userId) {
        LocalDate now = LocalDate.now();
        int week = weeks.weekOf(now);
        int day = WeekCalculator.isoDay(now);
        boolean inTerm = week >= 1 && week <= weeks.totalWeeks();
        boolean usingDemo = shouldUseDemo(userId);

        List<SessionView> sessions;
        if (!inTerm) {
            sessions = List.of();
        } else if (usingDemo) {
            sessions = demo.sessions().stream()
                    .filter(s -> s.dayOfWeek() == day && s.weeks().contains(week))
                    .sorted(Comparator.comparingInt(CourseSession::startSection))
                    .map(SessionView::of)
                    .toList();
        } else {
            sessions = store.listSessions(userId).stream()
                    .filter(s -> s.dayOfWeek() == day && s.weeks().contains(week))
                    .sorted(Comparator.comparingInt(ScheduleStore.StoredSession::startSection))
                    .map(SessionView::of)
                    .toList();
        }

        return new TodayView(now.format(DATE), week, day, labelOf(day), inTerm,
                usingDemo && !sessions.isEmpty(), sessions);
    }

    /** 有课的所有周次，用于前端周次选择器高亮。 */
    public Set<Integer> weeksWithClasses(long userId) {
        Set<Integer> out = new TreeSet<>();
        if (shouldUseDemo(userId)) {
            for (CourseSession s : demo.sessions()) {
                out.addAll(s.weeks());
            }
            return out;
        }
        for (ScheduleStore.StoredSession s : store.listSessions(userId)) {
            out.addAll(s.weeks());
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** 这个用户一条真实课表都没有，且演示数据可用。 */
    private boolean shouldUseDemo(long userId) {
        return demo.enabled() && store.countSessions(userId) == 0;
    }

    /**
     * 星期几 → 「周一」。
     *
     * <p>★ 注意别把它改名成 {@code weekdayLabel}：{@link SessionView} 有个同名 record 组件，
     * 记录类会自动生成无参访问器 {@code weekdayLabel()}，把外层的同名静态方法遮蔽掉，
     * 于是 {@code weekdayLabel(int)} 就调不到了（这是真踩过的编译错误）。
     */
    private static String labelOf(int dayOfWeek) {
        return dayOfWeek >= 1 && dayOfWeek <= 7 ? WEEKDAY_LABELS[dayOfWeek] : "";
    }
}
