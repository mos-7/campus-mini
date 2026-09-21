package com.campus.mini.adapter;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册表 —— 从 MoocPass 的 {@code PlatformAdapterRegistry} 学来的结构。
 *
 * <p>Spring 会把容器里所有 {@link CampusAdapter} 实现通过构造器注入进来，
 * 于是新增一个 {@code @Component} 适配器就自动生效，不用回来改这个类。
 */
@Component
public class AdapterRegistry {

    private final Map<String, CampusAdapter> adapters = new LinkedHashMap<>();

    public AdapterRegistry(List<CampusAdapter> discovered) {
        for (CampusAdapter adapter : discovered) {
            String code = adapter.code().toLowerCase();
            if (adapters.containsKey(code)) {
                throw new IllegalStateException(
                        "适配器 code 重复: " + code + " —— 每个平台必须有唯一 code");
            }
            adapters.put(code, adapter);
        }
    }

    /** 找不到返回 null，由调用方决定报什么错。 */
    public CampusAdapter find(String code) {
        return code == null ? null : adapters.get(code.toLowerCase());
    }

    public List<CampusAdapter> all() {
        return List.copyOf(adapters.values());
    }
}
