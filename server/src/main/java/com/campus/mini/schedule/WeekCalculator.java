package com.campus.mini.schedule;

import com.campus.mini.config.CampusProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 周次计算。课表功能的地基，必须先按你学校的校历配置对。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code campus.term.start-date} —— 学期<b>第一周周一</b>的日期</li>
 *   <li>{@code campus.term.total-weeks} —— 学期总周数</li>
 * </ul>
 */
@Component
public class WeekCalculator {

    private final CampusProperties properties;

    public WeekCalculator(CampusProperties properties) {
        this.properties = properties;
    }

    /** 学期第一周周一。 */
    public LocalDate termStart() {
        return properties.getTerm().getStartDate();
    }

    public int totalWeeks() {
        return properties.getTerm().getTotalWeeks();
    }

    /** 每天几节课 —— 课表网格的行数。 */
    public int sectionsPerDay() {
        return properties.getTerm().getSectionsPerDay();
    }

    /** 上午 / 下午的分界节次。 */
    public int noonSection() {
        return properties.getTerm().getNoonSection();
    }

    /** 下午 / 晚上的分界节次。 */
    public int eveningSection() {
        return properties.getTerm().getEveningSection();
    }

    /**
     * 某一天是第几周。
     *
     * <p>开学前返回 {@code 0} 或负数（前端应显示"假期"）；超过总周数返回超出的值，
     * 由调用方决定是否裁剪 —— 不在这里静默夹断，否则排查问题时会很迷惑。
     */
    public int weekOf(LocalDate date) {
        long days = ChronoUnit.DAYS.between(termStart(), date);
        // floorDiv 对负数也向负无穷取整，所以开学前的日期会得到 0 或负数，符合预期
        return (int) (Math.floorDiv(days, 7L) + 1);
    }

    /** 今天第几周。 */
    public int currentWeek() {
        return weekOf(LocalDate.now());
    }

    /** 第 N 周周一的日期。 */
    public LocalDate mondayOfWeek(int week) {
        return termStart().plusWeeks(week - 1L);
    }

    /** 把 {@link DayOfWeek} 转成 1=周一 … 7=周日。 */
    public static int isoDay(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }

    /** 今天是星期几（1=周一 … 7=周日）。 */
    public static int todayDayOfWeek() {
        return isoDay(LocalDate.now());
    }
}
