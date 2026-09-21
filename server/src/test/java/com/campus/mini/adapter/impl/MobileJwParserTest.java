package com.campus.mini.adapter.impl;

import com.campus.mini.adapter.model.Models.CourseSession;
import com.campus.mini.config.CampusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用【真实响应结构】验证课表解析。
 *
 * <h2>为什么要有这个测试</h2>
 *
 * <p>第一版我按正方的字段名习惯猜（{@code kcmc}/{@code xqj}/{@code jcs}/{@code zcd}），
 * 结果对着真实响应<b>一条都匹配不上</b>，课表是空的。这类厂商接口没有文档，
 * 只能靠真实响应把字段名和编码规则钉死 —— 钉死之后必须有测试保护，
 * 否则下次改动又会悄悄退化。
 *
 * <p>夹具 {@code mobilejw-curriculum-sample.json} 的结构、字段名、编码格式
 * <b>全部来自一份真实响应</b>，只把课程名/教师名/教室名换成了示例值
 * （真实姓名和教室不适合进公开仓库）。编码格式一个字符都没改：
 *
 * <ul>
 *   <li>{@code "code": "1"} —— 成功码是<b>字符串</b></li>
 *   <li>{@code weekNoteDetail: "103,104"} —— 3 位码：1 位星期 + 2 位节次</li>
 *   <li>{@code classTime: "10304"} —— 首字符星期，其后每 2 位一节（奇数长度）</li>
 *   <li>{@code classWeekDetails: ",3,4,6,7,"} —— 首尾都带逗号</li>
 *   <li>{@code weekDay: "0"} —— <b>0 表示周日</b></li>
 *   <li>{@code item[][]} —— 与 {@code courses[]} 重复的数据，必须去重</li>
 * </ul>
 */
class MobileJwParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static MobileJwAdapter newAdapter() {
        CampusProperties props = new CampusProperties();
        props.getTerm().setTotalWeeks(20);
        return new MobileJwAdapter(props);
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream in = MobileJwParserTest.class.getClassLoader()
                .getResourceAsStream("mobilejw-curriculum-sample.json")) {
            assertNotNull(in, "找不到测试夹具 mobilejw-curriculum-sample.json");
            return JSON.readTree(in);
        }
    }

    private static CourseSession find(List<CourseSession> list, String name, int day, int startSection) {
        return list.stream()
                .filter(s -> s.courseName().equals(name)
                        && s.dayOfWeek() == day
                        && s.startSection() == startSection)
                .findFirst()
                .orElse(null);
    }

    private static Set<Integer> weeksOf(CourseSession s) {
        return new TreeSet<>(s.weeks());
    }

    @Test
    @DisplayName("真实响应能解析出全部课时，且 item[] 里的重复被去掉")
    void parsesEverySessionAndDedupes() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        // courses[] 有 7 条；item[][] 里重复了「示例课一」1 条 → 去重后仍是 7
        assertEquals(7, sessions.size(),
                "应为 7 条（courses[] 7 条，item[] 的重复项已去重），实际: " + sessions.size());
    }

    @Test
    @DisplayName("字段名映射：courseName / weekDay / weekNoteDetail / classroomName / teacherName")
    void mapsVendorFieldNames() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        CourseSession c = find(sessions, "示例课一", 1, 3);
        assertNotNull(c, "示例课一 应在周一第 3 节");
        assertEquals(4, c.endSection(), "weekNoteDetail=103,104 应解析为第 3-4 节");
        assertEquals("示例教师甲", c.teacher(), "teacherName → teacher");
        assertEquals("示例1203", c.location(), "classroomName → location");
        assertEquals("TEST0001", c.courseExternalId(), "jx0404id → externalId");
    }

    @Test
    @DisplayName("周次格式：classWeekDetails 首尾带逗号也能解析")
    void parsesCommaWrappedWeeks() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        CourseSession c = find(sessions, "示例课一", 1, 3);
        assertNotNull(c);
        assertEquals(Set.of(3, 4, 6, 7), weeksOf(c),
                "classWeekDetails=\",3,4,6,7,\" 应解析为 {3,4,6,7}");
    }

    @Test
    @DisplayName("长周次区间不漏：示例课二 到第 18 周")
    void parsesLongWeekRange() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        CourseSession c = find(sessions, "示例课二", 1, 5);
        assertNotNull(c);
        Set<Integer> w = weeksOf(c);
        assertEquals(15, w.size(), "3,4 + 6..18 共 15 周，实际: " + w);
        assertTrue(w.contains(18), "应包含第 18 周");
        assertFalse(w.contains(5), "第 5 周不在范围内（classWeekDetails 跳过 5）");
    }

    @Test
    @DisplayName("节次编码 A：weekNoteDetail 的 4 个 3 位码 → 1-4 节")
    void parsesTripletSections() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        CourseSession c = find(sessions, "示例课四", 3, 1);
        assertNotNull(c, "摄影课式的 weekNoteDetail=301,302,303,304 应解析为第 1 节起");
        assertEquals(1, c.startSection());
        assertEquals(4, c.endSection());
    }

    @Test
    @DisplayName("★ 同一门课不同时段必须保留成两条（示例课三 周二 + 周三晚）")
    void keepsSameCourseOnDifferentSlots() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        CourseSession tue = find(sessions, "示例课三", 2, 3);
        CourseSession wedEve = find(sessions, "示例课三", 3, 9);
        assertNotNull(tue, "周二第 3-4 节的示例课三 应存在");
        assertNotNull(wedEve, "周三第 9-10 节的示例课三 应存在（不能被去重掉）");
        assertEquals(10, wedEve.endSection(), "weekNoteDetail=309,310 应解析为第 9-10 节");
        assertEquals(Set.of(3), weeksOf(wedEve), "classWeekDetails=\",3,\" 应只有第 3 周");
    }

    @Test
    @DisplayName("★ weekDay=\"0\" 表示周日，要映射成 7")
    void mapsZeroToSunday() throws Exception {
        List<CourseSession> sessions = newAdapter().parseSessions(fixture());

        CourseSession sun = find(sessions, "示例课六", 7, 1);
        assertNotNull(sun, "weekDay=\"0\" 应被解析成周日(7)，而不是被丢掉或当成 0");
        assertEquals(Set.of(2, 3, 4, 5), weeksOf(sun));
    }
}
