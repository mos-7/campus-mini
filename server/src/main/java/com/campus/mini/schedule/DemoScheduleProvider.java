package com.campus.mini.schedule;

import com.campus.mini.adapter.AdapterRegistry;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.impl.ManualAdapter;
import com.campus.mini.adapter.model.Models.CourseSession;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.FetchResult;
import com.campus.mini.config.CampusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新用户的演示课表。
 *
 * <h2>为什么需要这个东西</h2>
 *
 * <p>小程序靠微信 openid 登录，<b>没法给微信审核员一个"测试账号"</b> ——
 * 他点开就是全新用户。如果新用户看到的是空白页，审核大概率判「功能不完整」驳回。
 * 这是校园工具类小程序最常见的死法。
 *
 * <p>所以：用户<b>还没绑定任何平台</b>时，返回这份演示课表，并在响应里带
 * {@code demo: true}。前端据此显示一条"这是示例数据"的横幅。
 *
 * <p>设计上守住了两条线：
 * <ul>
 *   <li>审核员一打开就有内容可看 → 不会因"空壳"被驳</li>
 *   <li>真实用户不会被误导 → 横幅明确写着"示例，绑定后换成你自己的"</li>
 * </ul>
 *
 * <p>数据来源是 {@link ManualAdapter#SAMPLE}，在启动时解析一次。它走的是和真实导入
 * <b>完全相同</b>的解析路径，所以顺带也是 {@link WeekTextParser} 的冒烟测试 ——
 * 如果启动日志里没有「演示课表已就绪」，说明解析器坏了。
 */
@Component
public class DemoScheduleProvider {

    private static final Logger log = LoggerFactory.getLogger(DemoScheduleProvider.class);

    private final boolean enabled;
    private final List<CourseSession> sessions;

    public DemoScheduleProvider(CampusProperties properties, AdapterRegistry registry) {
        this.enabled = properties.isDemoData();

        // 注意：这里先算到一个【局部变量】再赋值给 final 字段。
        // 直接在 try 里给 final 字段赋值、又在 catch 里再赋一次，javac 会报
        // "variable sessions might already have been assigned" ——
        // 因为 try 块里的赋值成功后如果 log 抛异常，catch 就会赋第二次，编译器无法排除这种路径。
        List<CourseSession> parsed;

        if (!enabled) {
            parsed = List.of();
        } else {
            CampusAdapter manual = registry.find(ManualAdapter.CODE);
            if (manual == null) {
                parsed = List.of();
                log.warn("找不到 manual 适配器，演示课表不可用");
            } else {
                parsed = parseSample(manual);
            }
        }

        this.sessions = parsed;
    }

    private static List<CourseSession> parseSample(CampusAdapter manual) {
        Credential credential = new Credential("demo", ManualAdapter.SAMPLE);
        try {
            FetchResult result = manual.fetch(credential);
            List<CourseSession> parsed = List.copyOf(result.sessions());
            log.info("演示课表已就绪：{} 条上课安排（未绑定平台的用户会看到这份数据，带 demo 标记）",
                    parsed.size());
            return parsed;
        } catch (RuntimeException e) {
            // 演示数据坏掉不该拖垮整个应用
            log.error("演示课表解析失败，未绑定用户将看到空课表：{}", e.getMessage());
            return List.of();
        }
    }

    public boolean enabled() {
        return enabled && !sessions.isEmpty();
    }

    public List<CourseSession> sessions() {
        return sessions;
    }
}
