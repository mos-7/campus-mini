package com.campus.mini.adapter;

/**
 * 适配器抓取失败。抛出它 = 同步任务落到 FAILED，把 message 透给前端。
 *
 * <p>message 会被用户看到，所以写人话，不要塞堆栈。
 */
public class AdapterException extends RuntimeException {

    public AdapterException(String message) {
        super(message);
    }

    public AdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
