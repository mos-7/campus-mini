package com.campus.mini.schedule;

import com.campus.mini.adapter.model.Models.Course;
import com.campus.mini.adapter.model.Models.CourseSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 课程 / 课表 / 公告的持久化。
 *
 * <p>前端永远读这里（缓存优先），只有用户主动点「立即同步」才去抓平台 ——
 * 打开页面时现爬既慢又容易触发风控。
 */
@Repository
public class ScheduleStore {

    private final JdbcTemplate jdbc;

    public ScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 库里的一条上课安排。周次存成 CSV，如 {@code "1,3,5,7"}。 */
    public record StoredSession(
            long id,
            String adapterCode,
            String courseExternalId,
            String courseName,
            String teacher,
            String location,
            int dayOfWeek,
            int startSection,
            int endSection,
            String weeksCsv,
            String rawText
    ) {
        public Set<Integer> weeks() {
            Set<Integer> out = new TreeSet<>();
            if (weeksCsv == null || weeksCsv.isBlank()) {
                return out;
            }
            for (String part : weeksCsv.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    out.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) {
                    // 单条脏数据不该让整个课表挂掉
                }
            }
            return out;
        }
    }

    private static final RowMapper<StoredSession> SESSION_MAPPER = (rs, rowNum) -> new StoredSession(
            rs.getLong("id"),
            rs.getString("adapter_code"),
            rs.getString("course_external_id"),
            rs.getString("course_name"),
            rs.getString("teacher"),
            rs.getString("location"),
            rs.getInt("day_of_week"),
            rs.getInt("start_section"),
            rs.getInt("end_section"),
            rs.getString("weeks"),
            rs.getString("raw_text"));

    private static final RowMapper<Course> COURSE_MAPPER = (rs, rowNum) -> new Course(
            rs.getString("external_id"),
            rs.getString("name"),
            rs.getString("teacher"),
            rs.getString("class_name"),
            rs.getString("cover_url"),
            rs.getString("raw_time_text"));

    /**
     * 用一次抓取的结果整体覆盖该用户该平台的数据。
     *
     * <p>先删后插，保证「平台上删掉的课」在我们这边也消失。整个过程在一个事务里
     * （由调用方 {@code @Transactional} 保证，或单条 SQL 的原子性兜底）。
     */
    /**
     * 删掉某用户在某平台下同步进来的全部课程与课表。
     *
     * <p>解绑时必须调它。小程序里的确认框写着「已同步的课表缓存也会一并清掉」，
     * 如果只删绑定记录不删数据，<b>解绑后那些课还会留在课表里</b> —— 文案就成了谎话。
     * （踩过：解绑「手动导入」后测试数据仍在第 3 周课表里显示。）
     */
    public void deleteAll(long userId, String adapterCode) {
        jdbc.update("DELETE FROM course WHERE user_id = ? AND adapter_code = ?", userId, adapterCode);
        jdbc.update("DELETE FROM course_session WHERE user_id = ? AND adapter_code = ?", userId, adapterCode);
    }

    public void replaceAll(long userId, String adapterCode,
                           List<Course> courses, List<CourseSession> sessions) {
        deleteAll(userId, adapterCode);

        Timestamp now = Timestamp.from(Instant.now());

        List<Object[]> courseRows = new ArrayList<>();
        for (Course c : courses) {
            courseRows.add(new Object[]{
                    userId, adapterCode,
                    nz(c.externalId()), nz(c.name()), nz(c.teacher()), nz(c.className()),
                    nz(c.coverUrl()), nz(c.rawTimeText()), now});
        }
        if (!courseRows.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO course (user_id, adapter_code, external_id, name, teacher, "
                            + "class_name, cover_url, raw_time_text, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    courseRows);
        }

        List<Object[]> sessionRows = new ArrayList<>();
        for (CourseSession s : sessions) {
            sessionRows.add(new Object[]{
                    userId, adapterCode,
                    nz(s.courseExternalId()), nz(s.courseName()), nz(s.teacher()), nz(s.location()),
                    s.dayOfWeek(), s.startSection(), s.endSection(),
                    joinWeeks(s.weeks()), nz(s.rawText()), now});
        }
        if (!sessionRows.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO course_session (user_id, adapter_code, course_external_id, course_name, "
                            + "teacher, location, day_of_week, start_section, end_section, weeks, raw_text, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sessionRows);
        }
    }

    public List<StoredSession> listSessions(long userId) {
        return jdbc.query(
                "SELECT * FROM course_session WHERE user_id = ? "
                        + "ORDER BY day_of_week, start_section, course_name",
                SESSION_MAPPER, userId);
    }

    /** 支持手动导入时"只补课表不动课程"的场景。 */
    public void insertSessions(long userId, String adapterCode, List<CourseSession> sessions) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>();
        for (CourseSession s : sessions) {
            rows.add(new Object[]{
                    userId, adapterCode,
                    nz(s.courseExternalId()), nz(s.courseName()), nz(s.teacher()), nz(s.location()),
                    s.dayOfWeek(), s.startSection(), s.endSection(),
                    joinWeeks(s.weeks()), nz(s.rawText()), now});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO course_session (user_id, adapter_code, course_external_id, course_name, "
                            + "teacher, location, day_of_week, start_section, end_section, weeks, raw_text, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    rows);
        }
    }

    public List<Course> listCourses(long userId) {
        return jdbc.query("SELECT * FROM course WHERE user_id = ? ORDER BY name", COURSE_MAPPER, userId);
    }

    public int countSessions(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_session WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------
    // 公告
    // ------------------------------------------------------------------

    public record Announcement(long id, String title, String body, String publishedAt, boolean pinned) {
    }

    public List<Announcement> listAnnouncements(int limit) {
        return jdbc.query(
                "SELECT id, title, body, published_at, pinned FROM announcement "
                        + "ORDER BY pinned DESC, published_at DESC LIMIT ?",
                (rs, rowNum) -> new Announcement(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("body"),
                        String.valueOf(rs.getDate("published_at")),
                        rs.getBoolean("pinned")),
                limit);
    }

    // ------------------------------------------------------------------

    private static String joinWeeks(Set<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int w : new TreeSet<>(weeks)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(w);
        }
        return sb.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
