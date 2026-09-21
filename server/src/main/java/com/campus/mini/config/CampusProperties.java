package com.campus.mini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 本项目所有可调参数。对应 {@code application.yml} 里的 {@code campus.*}。
 */
@Component
@ConfigurationProperties(prefix = "campus")
public class CampusProperties {

    private final Term term = new Term();
    private final Chaoxing chaoxing = new Chaoxing();
    private String masterKey = "";
    private String jwtSecret = "";

    /** 启动时灌一份演示课表，方便小程序直接跑通。生产环境设 false。 */
    private boolean demoData = true;

    public boolean isDemoData() {
        return demoData;
    }

    public void setDemoData(boolean demoData) {
        this.demoData = demoData;
    }

    public Term getTerm() {
        return term;
    }

    public Chaoxing getChaoxing() {
        return chaoxing;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    /** 校历。★ 这两个值必须按你学校的实际校历改，否则整个课表都是错的。 */
    public static class Term {
        /** 第一周周一。 */
        private LocalDate startDate = LocalDate.of(2026, 3, 2);
        /** 总周数。 */
        private int totalWeeks = 20;
        /** 每天几节课 —— 决定课表网格有几行。 */
        private int sectionsPerDay = 12;
        /** 上午 / 下午 / 晚上的分界节次，用于给网格分组上色。 */
        private int noonSection = 4;
        private int eveningSection = 8;

        public LocalDate getStartDate() {
            return startDate;
        }

        public void setStartDate(LocalDate startDate) {
            this.startDate = startDate;
        }

        public int getTotalWeeks() {
            return totalWeeks;
        }

        public void setTotalWeeks(int totalWeeks) {
            this.totalWeeks = totalWeeks;
        }

        public int getSectionsPerDay() {
            return sectionsPerDay;
        }

        public void setSectionsPerDay(int sectionsPerDay) {
            this.sectionsPerDay = sectionsPerDay;
        }

        public int getNoonSection() {
            return noonSection;
        }

        public void setNoonSection(int noonSection) {
            this.noonSection = noonSection;
        }

        public int getEveningSection() {
            return eveningSection;
        }

        public void setEveningSection(int eveningSection) {
            this.eveningSection = eveningSection;
        }
    }

    /** 超星适配器开关。联调通过前保持 false，前端会显示为「未开放」。 */
    public static class Chaoxing {
        private boolean enabled = false;
        private String loginUrl = "https://passport2.chaoxing.com/fanyalogin";
        private String courseListUrl = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLoginUrl() {
            return loginUrl;
        }

        public void setLoginUrl(String loginUrl) {
            this.loginUrl = loginUrl;
        }

        public String getCourseListUrl() {
            return courseListUrl;
        }

        public void setCourseListUrl(String courseListUrl) {
            this.courseListUrl = courseListUrl;
        }
    }
}
