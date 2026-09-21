package com.campus.mini.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查。不需要登录 —— {@code /api/health} 在 {@code WebConfig} 里放行了。
 *
 * <p>云托管部署后用它确认服务起来了（容器端口、启动是否成功）。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "campus-api");
        body.put("time", java.time.Instant.now().toString());
        return ApiResponse.ok(body);
    }
}
