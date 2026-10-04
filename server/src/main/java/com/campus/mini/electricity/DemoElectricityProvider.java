package com.campus.mini.electricity;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 演示用电数据源。
 *
 * <p>数据是<b>确定性</b>的伪随机：同一间宿舍同一天查到同一个数，不同宿舍、
 * 不同天数不一样 —— 这样演示看起来像真的，又不需要维护状态。
 * 接入真实电控平台后整个类删掉或停用即可（见 {@link ElectricityProvider} 的说明）。
 */
@Component
public class DemoElectricityProvider implements ElectricityProvider {

    private static final List<String> CAMPUSES = List.of("主校区", "东校区");

    /** 每层多少间房。 */
    private static final int ROOMS_PER_FLOOR = 20;

    @Override
    public List<String> campuses() {
        return CAMPUSES;
    }

    @Override
    public List<String> buildings(String campus) {
        if ("东校区".equals(campus)) {
            return List.of("A栋", "B栋", "C栋", "D栋");
        }
        return IntStream.rangeClosed(1, 12).mapToObj(i -> i + "栋").toList();
    }

    @Override
    public List<String> floors(String campus, String building) {
        return IntStream.rangeClosed(1, 6).mapToObj(i -> i + "层").toList();
    }

    @Override
    public List<String> rooms(String campus, String building, String floor) {
        int f = floorNumber(floor);
        return IntStream.rangeClosed(1, ROOMS_PER_FLOOR)
                .mapToObj(i -> String.format("%d%02d", f, i))
                .toList();
    }

    @Override
    public Reading query(String campus, String building, String floor, String room) {
        int seed = seed(room + "@" + campus + building);
        String today = LocalDate.now().toString();

        double balance = round2(20 + (seed % 8000) / 100.0);                       // 20 ~ 99.99 元
        double remaining = round2(balance / 0.55);                                 // 按单价折算成度
        double yesterday = round2(2 + (seed(room + today) % 90) / 10.0);           // 2 ~ 10.9 度
        double month = round2(yesterday * 22 + (seed % 130));                      // 粗略的月用量

        return new Reading(balance, remaining, yesterday, month);
    }

    // ------------------------------------------------------------------

    private int floorNumber(String floor) {
        try {
            return Integer.parseInt(floor.replace("层", "").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int seed(String s) {
        int h = 7;
        for (int i = 0; i < s.length(); i++) {
            h = (h * 31 + s.charAt(i)) & 0x7fffffff;
        }
        return h;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
