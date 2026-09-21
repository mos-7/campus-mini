package com.campus.mini.adapter.model;

import java.util.List;
import java.util.Set;

/**
 * 适配器层的数据模型。全部是 record —— 不可变、没有 setter。
 *
 * <p>放在一个文件里是为了控制文件数量；用的时候按需 import 嵌套类型，例如：
 * <pre>{@code import com.campus.mini.adapter.model.Models.CourseSession; }</pre>
 */
public final class Models {

    private Models() {
    }

    /** 登录凭据。只在 CredentialVault 解密后短暂存在，不要往日志里写。 */
    public record Credential(String username, String secret) {
        @Override
        public String toString() {
            // 防止手滑打日志把密码带出去
            return "Credential[username=" + username + ", secret=***]";
        }
    }

    /** 凭据校验结果。 */
    public record VerifyResult(boolean ok, String displayName, String message) {

        public static VerifyResult ok(String displayName) {
            return new VerifyResult(true, displayName, "验证通过");
        }

        public static VerifyResult fail(String message) {
            return new VerifyResult(false, null, message);
        }
    }

    /** 一门课（课程级信息，不含具体上课时间）。 */
    public record Course(
            String externalId,
            String name,
            String teacher,
            String className,
            String coverUrl,
            String rawTimeText
    ) {
    }

    /** 一次具体的上课安排 —— 课表网格里的一个格子。 */
    public record CourseSession(
            String courseExternalId,
            String courseName,
            String teacher,
            String location,
            int dayOfWeek,          // 1=周一 ... 7=周日
            int startSection,       // 起始节次，从 1 开始
            int endSection,         // 结束节次（含）
            Set<Integer> weeks,     // 生效周次，如 {1,3,5,...,15}
            String rawText          // 原始文本，解析存疑时用于人工核对
    ) {
    }

    /** 一次抓取的完整结果。 */
    public record FetchResult(
            List<Course> courses,
            List<CourseSession> sessions,
            String message
    ) {
        public static FetchResult empty(String message) {
            return new FetchResult(List.of(), List.of(), message);
        }
    }
}
