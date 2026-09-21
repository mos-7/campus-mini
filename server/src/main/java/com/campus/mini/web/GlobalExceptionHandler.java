package com.campus.mini.web;

import com.campus.mini.adapter.AdapterException;
import com.campus.mini.common.ApiException;
import com.campus.mini.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。保证前端拿到的永远是 {@link ApiResponse} 信封，
 * 而且 {@code message} 是可以直接显示给用户的中文。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(ApiResponse.error(httpCode(e.status()), e.getMessage()));
    }

    @ExceptionHandler(AdapterException.class)
    public ResponseEntity<ApiResponse<Void>> handleAdapter(AdapterException e) {
        // 适配器抛出的 message 是写给用户看的，直接透出去
        log.info("适配器错误：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(50201, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        // 典型场景：主密钥变了导致解密失败 → 提示重新绑定
        log.warn("状态异常：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(40901, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        // 兜底：内部细节只进日志，不给前端
        log.error("未预期的错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50000, "服务出了点问题，稍后再试。"));
    }

    private static int httpCode(HttpStatus status) {
        return status.value() * 100;
    }
}
