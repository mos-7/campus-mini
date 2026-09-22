package com.campus.mini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 小粥历 · 后端。
 *
 * <p>启动后：
 * <ul>
 *   <li>{@code http://localhost:8088/api/health} —— 健康检查</li>
 *   <li>H2 控制台 {@code http://localhost:8088/h2-console}（JDBC URL 见 application.yml）</li>
 * </ul>
 */
@SpringBootApplication
public class CampusApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusApplication.class, args);
    }
}
