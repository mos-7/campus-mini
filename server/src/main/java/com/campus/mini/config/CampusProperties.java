package com.campus.mini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本项目所有可调参数。对应 {@code application.yml} 里的 {@code campus.*}。
 */
@Component
@ConfigurationProperties(prefix = "campus")
public class CampusProperties {

    private final Term term = new Term();
    private final Chaoxing chaoxing = new Chaoxing();
    private final MobileJw mobileJw = new MobileJw();
    private final Wechat wechat = new Wechat();
    private String masterKey = "";
    private String jwtSecret = "";

    /** 启动时灌一份演示课表，方便小程序直接跑通。生产环境设 false。 */
    private boolean demoData = true;

    /**
     * 打开调试接口 {@code GET /api/debug/raw/{code}}。
     *
     * <p>★ 生产必须 false —— 它会把平台的原始响应原样吐出来，
     * 里面有学生个人信息和平台内部结构。
     */
    private boolean debugEndpoints = false;

    public boolean isDebugEndpoints() {
        return debugEndpoints;
    }

    public void setDebugEndpoints(boolean debugEndpoints) {
        this.debugEndpoints = debugEndpoints;
    }

    public MobileJw getMobileJw() {
        return mobileJw;
    }

    public Wechat getWechat() {
        return wechat;
    }

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

    /**
     * 「移动教务」类厂商 SaaS 适配器。
     *
     * <p>这类系统是<b>厂商多租户产品</b>：前端由学校自行部署，后端 API 在厂商云或学校自己的
     * 服务器上，运行时由一个 {@code serverconfig.json} 静态文件下发地址。
     *
     * <p>字段命名保持<b>厂商无关</b>：不出现任何具体学校名或地址。
     * 真实取值放在 {@code application-local.yml}（已在 .gitignore 里），
     * 公开仓库只保留空占位。见 {@code docs/jwxt-adapter.md}。
     */
    public static class MobileJw {

        /** 联调通过前保持 false，前端显示「未开放」。 */
        private boolean enabled = false;

        /**
         * API 根地址。例：{@code http://<host>:<port>/<ctx>}
         *
         * <p>★ 属性名是点分的 {@code campus.mobilejw.base.url}，<b>不是</b> {@code base-url}。
         *
         * <p>踩过的坑：Spring Boot 的环境变量映射规则是「<b>点换成下划线，连字符直接删掉</b>」。
         * 所以 {@code base-url} 对应的环境变量是 {@code CAMPUS_MOBILEJW_BASEURL}
         * （BASE 和 URL 之间<b>没有下划线</b>），而 {@code base.url} 才对应
         * {@code CAMPUS_MOBILEJW_BASE_URL} —— 后者才是任何人都会写的样子。
         * 当时因为写成 {@code base-url}，云端变量明明填了却一直读不到，
         * 表现为「移动教务暂未开放」。
         */
        private final Base base = new Base();

        /**
         * AES 密钥（16 字符）。属性名同样是点分的 {@code campus.mobilejw.pwd.key}
         * → 环境变量 {@code CAMPUS_MOBILEJW_PWD_KEY}（原因见 {@link #base}）。
         */
        private final Pwd pwd = new Pwd();

        public Base getBase() {
            return base;
        }

        public Pwd getPwd() {
            return pwd;
        }

        /** 便捷访问 {@code base.url}。 */
        public String getBaseUrl() {
            return base.getUrl();
        }

        /** 便捷访问 {@code pwd.key}。 */
        public String getPwdKey() {
            return pwd.getKey();
        }

        /** {@code campus.mobilejw.base.url} */
        public static class Base {
            private String url = "";

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }
        }

        /** {@code campus.mobilejw.pwd.key} */
        public static class Pwd {
            private String key = "";

            public String getKey() {
                return key;
            }

            public void setKey(String key) {
                this.key = key;
            }
        }

        private String loginPath = "/login";
        private String curriculumPath = "/student/curriculum";
        private String gradePath = "/gradeList";
        private String termPath = "/currentTerm";
        private String studentPath = "/student/my";

        /** 响应里判定成功的 code 值（这套系统用 1，不是 0）。 */
        private int successCode = 1;

        /**
         * 调课表接口时附带的额外查询参数。
         *
         * <p>课表接口需要一个"学期"参数，但字段名各校版本不同（{@code xnxqh} / {@code semester}
         * / {@code xq}…）。做成配置是为了<b>不用改代码就能试</b>：
         * 打开调试接口看真实响应，确认字段名后直接写进 yml。
         */
        private Map<String, String> curriculumParams = new LinkedHashMap<>();

        /**
         * 是否逐周抓课表。
         *
         * <p>课表接口<b>按周返回</b>，每门课自带完整周次范围。只抓一次的话，
         * 会漏掉"只在后半学期才上"的课。所以默认逐周抓 {@code week=1..N} 再合并去重。
         *
         * <p>代价是 N 次请求（约 0.3 秒/次 + 120ms 间隔，20 周约 8 秒）。
         * 同步是异步任务，不走云托管那 15 秒限制，所以可以接受。
         */
        private boolean fetchByWeek = true;

        public boolean isFetchByWeek() {
            return fetchByWeek;
        }

        public void setFetchByWeek(boolean fetchByWeek) {
            this.fetchByWeek = fetchByWeek;
        }

        /** 看门狗：单次请求超时（秒）。 */
        private int timeoutSeconds = 20;

        public Map<String, String> getCurriculumParams() {
            return curriculumParams;
        }

        public void setCurriculumParams(Map<String, String> curriculumParams) {
            this.curriculumParams = curriculumParams == null ? new LinkedHashMap<>() : curriculumParams;
        }

        public boolean isEnabled() {
            return enabled && !base.getUrl().isBlank() && !pwd.getKey().isBlank();
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLoginPath() {
            return loginPath;
        }

        public void setLoginPath(String loginPath) {
            this.loginPath = loginPath;
        }

        public String getCurriculumPath() {
            return curriculumPath;
        }

        public void setCurriculumPath(String curriculumPath) {
            this.curriculumPath = curriculumPath;
        }

        public String getGradePath() {
            return gradePath;
        }

        public void setGradePath(String gradePath) {
            this.gradePath = gradePath;
        }

        public String getTermPath() {
            return termPath;
        }

        public void setTermPath(String termPath) {
            this.termPath = termPath;
        }

        public String getStudentPath() {
            return studentPath;
        }

        public void setStudentPath(String studentPath) {
            this.studentPath = studentPath;
        }

        public int getSuccessCode() {
            return successCode;
        }

        public void setSuccessCode(int successCode) {
            this.successCode = successCode;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /**
     * 微信侧配置。
     *
     * <p>为什么做成配置对象而不是散落的 {@code @Value}：
     * 启动自检（{@link StartupDiagnostics}）要把它打出来 —— 生产环境最容易出问题的
     * 就是"环境变量没生效"，而只看控制台的变量列表分不清是**没存**还是**没应用**。
     */
    public static class Wechat {

        /** 云托管注入 openid 的请求头名。各家环境实测下来一般是 {@code x-wx-openid}。 */
        private String openidHeader = "x-wx-openid";

        /**
         * 是否允许请求体直接传 openid 登录（本地开发用）。
         *
         * <p>★ 生产必须 false。设成 true 的话，任何人 POST 一个
         * {@code {"openid":"别人的openid"}} 就能拿到那个人的登录 token。
         */
        private boolean mockEnabled = true;

        public String getOpenidHeader() {
            return openidHeader;
        }

        public void setOpenidHeader(String openidHeader) {
            this.openidHeader = openidHeader;
        }

        public boolean isMockEnabled() {
            return mockEnabled;
        }

        public void setMockEnabled(boolean mockEnabled) {
            this.mockEnabled = mockEnabled;
        }
    }
}
