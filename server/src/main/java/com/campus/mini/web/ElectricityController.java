package com.campus.mini.web;

import com.campus.mini.common.ApiResponse;
import com.campus.mini.electricity.DormStore;
import com.campus.mini.electricity.ElectricityService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校园用电接口：宿舍四级联动选项、绑定/解绑、电费查询。
 * 全部走 /api 拦截器，都需要登录。
 */
@RestController
@RequestMapping("/api/electricity")
public class ElectricityController {

    private final ElectricityService service;

    public ElectricityController(ElectricityService service) {
        this.service = service;
    }

    /** 绑定请求体：四级定位。 */
    public record BindDormRequest(String campus, String building, String floor, String room) {
    }

    /** 当前用户的宿舍绑定状态。 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        java.util.Optional<DormStore.Dorm> dorm = service.dormOf(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bound", dorm.isPresent());
        data.put("dorm", dorm.orElse(null));
        return ApiResponse.ok(data);
    }

    /** 级联选项。level=campus 时不带参数，其余依次带上级选中值。 */
    @GetMapping("/options")
    public ApiResponse<List<String>> options(@RequestParam String level,
                                             @RequestParam(required = false) String campus,
                                             @RequestParam(required = false) String building,
                                             @RequestParam(required = false) String floor) {
        return ApiResponse.ok(service.options(level, campus, building, floor));
    }

    @PostMapping("/bind")
    public ApiResponse<Void> bind(@RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
                                  @RequestBody BindDormRequest request) {
        service.bind(userId, request.campus(), request.building(), request.floor(), request.room());
        return ApiResponse.ok();
    }

    @DeleteMapping("/bind")
    public ApiResponse<Void> unbind(@RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        service.unbind(userId);
        return ApiResponse.ok();
    }

    /** 电费读数。未绑定宿舍时返回业务错误。 */
    @GetMapping("/balance")
    public ApiResponse<ElectricityService.BalanceView> balance(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        return ApiResponse.ok(service.balance(userId));
    }
}
