package com.campus.mini.web;

import com.campus.mini.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器 —— 对应 MoocPass 的 {@code LoginInterceptor}。
 *
 * <p>校验 {@code Authorization: Bearer <jwt>}，把 userId 塞进 request attribute，
 * 控制器用 {@code @RequestAttribute("userId")} 取。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 控制器里取当前用户的 key。 */
    public static final String USER_ID_ATTR = "userId";

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;

    public AuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw ApiException.unauthorized("请先登录");
        }

        Long userId = jwt.verify(header.substring(BEARER.length()).trim())
                .orElseThrow(() -> ApiException.unauthorized("登录已过期，请重新进入小程序"));

        request.setAttribute(USER_ID_ATTR, userId);
        return true;
    }
}
