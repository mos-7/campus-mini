package com.campus.mini.web;

import com.campus.mini.common.ApiResponse;
import com.campus.mini.electricity.CcbElectricityService;
import com.campus.mini.electricity.CcbElectricityService.LiveView;
import com.campus.mini.electricity.ReadingStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 电费"会话接力"接口。和 {@link ElectricityController} 共用 /api/electricity 前缀，
 * 方法名不重叠：这边是接了建行 E码通 的真数据通道，那边（options/bind/balance）保留。
 */
@RestController
@RequestMapping("/api/electricity")
public class CcbElectricityController {

    private final CcbElectricityService service;

    public CcbElectricityController(CcbElectricityService service) {
        this.service = service;
    }

    /**
     * 扫码导入会话。请求体就是 relay 二维码里的 JSON 原样：
     * {@code {skey, uid, cookie, room: {areaid, areaname, buildingid, buildingname, floorid, floorname, roomid, room}}}
     */
    @PostMapping("/import")
    public ApiResponse<CcbElectricityService.BalanceView> importSession(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
            @RequestBody CcbElectricityService.ImportPayload payload) {
        return ApiResponse.ok(service.importSession(userId, payload));
    }

    /** 页面总查询：会话状态 + 最新读数（ACTIVE 会话顺带真查一次，60 秒内用缓存）。 */
    @GetMapping("/live")
    public ApiResponse<LiveView> live(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        return ApiResponse.ok(service.live(userId));
    }

    /** 强制刷新一次余额。 */
    @PostMapping("/refresh")
    public ApiResponse<CcbElectricityService.BalanceView> refresh(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        return ApiResponse.ok(service.refresh(userId));
    }

    /** 读数历史，新的在前。 */
    @GetMapping("/history")
    public ApiResponse<List<ReadingStore.Reading>> history(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
            @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(service.history(userId, limit));
    }
}
