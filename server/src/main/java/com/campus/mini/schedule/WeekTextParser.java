package com.campus.mini.schedule;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把课表里的时间文本解析成结构化数据。
 *
 * <p>这是课表功能最容易做错的地方，所以集中在一个类里，方便写测试、也方便
 * 按你学校的实际格式扩充（不同学校的教务系统导出的文本格式差别很大）。
 */
public final class WeekTextParser {

    private WeekTextParser() {
    }

    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*[-~—]\\s*(\\d+)");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern PAREN = Pattern.compile("[（(][^）)]*[）)]");

    /** 中文数字，覆盖 1-20 够课表用了。 */
    private static final String[] CN_DIGITS = {
            "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"
    };

    /**
     * 解析周次文本。
     *
     * <p>支持：{@code 1-16周}、{@code 1-16周(单)}、{@code 1-16周(双)}、
     * {@code 3-5,8,10-12周}、{@code 1-16}、{@code 全周}
     *
     * @param text       原始文本
     * @param totalWeeks 学期总周数（来自校历配置），用于「全周」和越界裁剪
     * @return 生效周次，升序；解析不出来返回空集合
     */
    public static Set<Integer> parseWeeks(String text, int totalWeeks) {
        Set<Integer> weeks = new TreeSet<>();
        if (text == null || text.isBlank()) {
            return weeks;
        }

        if (text.contains("全")) {
            for (int i = 1; i <= totalWeeks; i++) {
                weeks.add(i);
            }
            return weeks;
        }

        // 单双周必须先于括号剥离判断，否则 "(单)" 会被一起删掉
        boolean oddOnly = text.contains("单");
        boolean evenOnly = text.contains("双");

        String core = PAREN.matcher(text).replaceAll(",")
                .replace("周", "")
                .replace("，", ",")
                .replaceAll("\\s", "");

        for (String token : core.split("[,，;；/、]+")) {
            if (token.isBlank()) {
                continue;
            }
            Matcher range = RANGE.matcher(token);
            if (range.matches()) {
                int from = Integer.parseInt(range.group(1));
                int to = Integer.parseInt(range.group(2));
                if (from > to) {
                    int swap = from;
                    from = to;
                    to = swap;
                }
                for (int i = from; i <= to; i++) {
                    weeks.add(i);
                }
            } else {
                Matcher num = NUMBER.matcher(token);
                while (num.find()) {
                    weeks.add(Integer.parseInt(num.group(1)));
                }
            }
        }

        if (oddOnly || evenOnly) {
            Set<Integer> filtered = new TreeSet<>();
            for (int w : weeks) {
                if (oddOnly && w % 2 == 1) {
                    filtered.add(w);
                }
                if (evenOnly && w % 2 == 0) {
                    filtered.add(w);
                }
            }
            weeks = filtered;
        }

        Set<Integer> clipped = new TreeSet<>();
        for (int w : weeks) {
            if (w >= 1 && w <= totalWeeks) {
                clipped.add(w);
            }
        }
        return clipped;
    }

    /**
     * 解析星期几。
     *
     * @return 1=周一 … 7=周日；解析不出来返回 {@code -1}
     */
    public static int parseDayOfWeek(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String s = text.replace("星期", "").replace("礼拜", "").replace("周", "");
        // 周日 / 周天 都要认
        if (s.contains("日") || s.contains("天") || s.contains("7") || s.contains("末")) {
            return 7;
        }
        for (int d = 1; d <= 6; d++) {
            if (s.contains(String.valueOf(d)) || s.contains(CN_DIGITS[d])) {
                return d;
            }
        }
        return -1;
    }

    /**
     * 解析节次。
     *
     * <p>支持：{@code 1-2节}、{@code 第1-2节}、{@code 3~4}、{@code 5}
     *
     * @return {@code {start, end}}；解析不出来返回 {@code null}
     */
    public static int[] parseSections(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String core = PAREN.matcher(text).replaceAll(",").replace("节", "");

        Matcher range = RANGE.matcher(core);
        if (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            return new int[]{Math.min(a, b), Math.max(a, b)};
        }

        Set<Integer> nums = new LinkedHashSet<>();
        Matcher num = NUMBER.matcher(core);
        while (num.find()) {
            nums.add(Integer.parseInt(num.group(1)));
        }
        if (nums.isEmpty()) {
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
}
