package com.campus.mini.adapter;

import com.campus.mini.adapter.model.Models.Credential;

/**
 * 可选接口：适配器可以额外提供「把平台原始响应吐出来」的能力，用于联调。
 *
 * <p>存在的理由：新平台的响应结构只能靠真实数据确认，猜字段名是浪费时间。
 * 打开 {@code campus.debug-endpoints=true} 后，
 * {@code GET /api/debug/raw/{adapterCode}} 就能看到一个真实的响应长什么样，
 * 据此把字段映射改对。
 *
 * <p>★ 这个接口返回的是<b>未经裁剪的平台原始数据</b>，含个人信息。
 * 生产环境必须保持 {@code campus.debug-endpoints=false}。
 */
public interface RawProbe {

    /**
     * 登录后抓一次原始响应。
     *
     * @return 原始响应文本（实现方应做长度截断）
     */
    String probeRaw(Credential credential);
}
