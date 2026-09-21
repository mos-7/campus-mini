package com.campus.mini.adapter;

import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.FetchResult;
import com.campus.mini.adapter.model.Models.VerifyResult;

import java.util.Set;

/**
 * 校园平台适配器。
 *
 * <p>每个学校 / 每个系统（教务、一卡通、校园跑…）写一个 {@code @Component} 实现，
 * {@link AdapterRegistry} 会自动收集。加平台不需要改任何既有代码。
 *
 * <p>接口只声明<b>读取</b>行为。{@link #readOnly()} 恒为 true。
 */
public interface CampusAdapter {

    /** 稳定标识，如 {@code chaoxing}。前端用它拼请求，改了会断。 */
    String code();

    /** 展示名，如「超星学习通」。 */
    String name();

    /** 卡片上的一句话说明。 */
    String description();

    /** 需要什么凭据 —— 决定绑定表单的形态。 */
    LoginMode loginMode();

    /** 声明能读什么。前端据此决定哪些功能入口可点。 */
    Set<Capability> capabilities();

    /**
     * 恒为 {@code true}。
     *
     * <p>保留这个方法不是为了将来可能改成 false，而是让「只读」这件事在运行时
     * 也能被断言 —— 可以写一个测试遍历所有适配器，断言没有一个返回 false。
     */
    default boolean readOnly() {
        return true;
    }

    /**
     * 校验凭据是否有效。
     *
     * <p>实现方不要把凭据写进日志，也不要把凭据缓存到静态字段。
     */
    VerifyResult verify(Credential credential);

    /**
     * 抓取课程 + 课表。
     *
     * <p>调用时机：在异步线程里（见 {@code SyncService}），所以云托管的
     * CallContainer 15 秒超时不约束本方法。但单次抓取仍建议控制在 30 秒内，
     * 超时请抛 {@link AdapterException}，让任务落到 FAILED 而不是卡死。
     */
    FetchResult fetch(Credential credential);
}
