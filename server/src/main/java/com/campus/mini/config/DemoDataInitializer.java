package com.campus.mini.config;

import com.campus.mini.adapter.AdapterRegistry;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.impl.ManualAdapter;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.FetchResult;
import com.campus.mini.adapter.model.Models.VerifyResult;
import com.campus.mini.binding.BindingStore;
import com.campus.mini.binding.CredentialVault;
import com.campus.mini.schedule.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 灌一份演示课表，让小程序一打开就有东西看。
 *
 * <p>用的是 {@link ManualAdapter#SAMPLE}，走的是<b>和真实导入完全一样</b>的解析路径 ——
 * 所以它同时也是 ManualAdapter 解析器的一个冒烟测试：如果启动日志里
 * 「已灌入演示课表」的条数不是 3，说明解析器坏了。
 *
 * <p>用演示账号登录：请求体传 {@code {"openid":"demo"}}（mock 模式下）。
 * 生产环境把 {@code campus.demo-data} 设为 false。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    /** 演示账号的 openid。 */
    public static final String DEMO_OPENID = "demo";

    private final CampusProperties properties;
    private final AdapterRegistry registry;
    private final BindingStore bindingStore;
    private final ScheduleStore scheduleStore;
    private final CredentialVault vault;

    public DemoDataInitializer(CampusProperties properties,
                               AdapterRegistry registry,
                               BindingStore bindingStore,
                               ScheduleStore scheduleStore,
                               CredentialVault vault) {
        this.properties = properties;
        this.registry = registry;
        this.bindingStore = bindingStore;
        this.scheduleStore = scheduleStore;
        this.vault = vault;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isDemoData()) {
            return;
        }

        long userId = bindingStore.upsertUser(DEMO_OPENID, "演示同学", null);

        if (scheduleStore.countSessions(userId) > 0) {
            log.info("演示课表已存在，跳过灌入");
            return;
        }

        CampusAdapter manual = registry.find(ManualAdapter.CODE);
        if (manual == null) {
            log.warn("找不到 manual 适配器，跳过演示数据");
            return;
        }

        Credential credential = new Credential("demo", ManualAdapter.SAMPLE);

        VerifyResult verify = manual.verify(credential);
        if (!verify.ok()) {
            log.error("演示课表解析失败，ManualAdapter 或 WeekTextParser 有问题：{}", verify.message());
            return;
        }

        FetchResult result = manual.fetch(credential);
        scheduleStore.replaceAll(userId, manual.code(), result.courses(), result.sessions());
        bindingStore.saveBinding(userId, manual.code(), manual.loginMode().name(),
                "演示课表", vault.encrypt(ManualAdapter.SAMPLE));

        log.info("已灌入演示课表：{} 门课 / {} 条上课安排", 
                result.courses().size(), result.sessions().size());
    }
}
