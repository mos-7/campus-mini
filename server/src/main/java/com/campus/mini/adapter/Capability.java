package com.campus.mini.adapter;

/**
 * 适配器能做的事 —— 全部是「读」。
 *
 * <p>★ 这个枚举里永远不会出现 VIDEO_PROGRESS、QUIZ_SUBMIT、CHECK_IN、RUN_SPOOF
 * 这类写入/伪造能力。
 *
 * <p>这不是「暂未实现」，是设计边界：本项目只聚合<b>你自己</b>的校园信息
 * （课表、成绩、余额、用电、跑量记录），不伪造任何学习记录或体育成绩，
 * 也不去破解平台的反爬/风控措施。
 *
 * <p>想加写入能力，得先往这个枚举里加值 —— 那时候你会很清楚自己在做什么。
 * 关于这类项目的实际下场，见 README 里引用的 2021 年辽宁朝阳案。
 */
public enum Capability {

    COURSE_LIST("课程列表"),
    SCHEDULE("课表"),
    GRADE("成绩"),
    EXAM("考试安排"),
    CARD_BALANCE("一卡通余额"),
    ELECTRICITY("宿舍用电"),
    RUN_RECORD("校园跑记录");

    private final String label;

    Capability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
