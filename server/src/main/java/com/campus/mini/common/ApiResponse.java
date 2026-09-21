package com.campus.mini.common;

/**
 * 统一响应信封。前端约定：{@code code == 0} 表示成功。
 *
 * <pre>{@code
 * { "code": 0, "message": "ok", "data": { ... } }
 * { "code": 40401, "message": "还没绑定该平台", "data": null }
 * }</pre>
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
