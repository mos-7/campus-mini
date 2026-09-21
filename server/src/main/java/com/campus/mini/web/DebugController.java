package com.campus.mini.web;

import com.campus.mini.adapter.AdapterRegistry;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.RawProbe;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.binding.BindingService;
import com.campus.mini.common.ApiException;
import com.campus.mini.common.ApiResponse;
import com.campus.mini.config.CampusProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联调用的调试接口。
 *
 * <p>存在的理由：新平台的响应结构只能靠<b>真实数据</b>确认，猜字段名纯属浪费时间。
 * 这个接口把平台原始响应（截断后）原样返回，用来核对字段映射。
 *
 * <h2>★ 默认关闭，且必须保持关闭</h2>
 *
 * <p>它返回未裁剪的平台数据，含个人信息。只有 {@code campus.debug-endpoints=true} 时才可用，
 * 默认 false，生产环境绝不要打开。
 *
 * <p>凭据<b>不从请求参数取</b>（那样密码会进 URL、进访问日志），
 * 而是复用已绑定的那条记录 —— 所以顺序是：先绑定成功，再探测。
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final AdapterRegistry registry;
    private final BindingService bindings;
    private final CampusProperties properties;

    public DebugController(AdapterRegistry registry,
                           BindingService bindings,
                           CampusProperties properties) {
        this.registry = registry;
        this.bindings = bindings;
        this.properties = properties;
    }

    @GetMapping("/raw/{adapterCode}")
    public ApiResponse<Map<String, Object>> raw(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
            @PathVariable String adapterCode) {

        if (!properties.isDebugEndpoints()) {
            throw ApiException.notFound("接口不存在"); // 不暴露"调试开关"的存在
        }

        CampusAdapter adapter = registry.find(adapterCode);
        if (adapter == null) {
            throw ApiException.notFound("没有这个平台：" + adapterCode);
        }
        if (!(adapter instanceof RawProbe probe)) {
            throw ApiException.badRequest("「" + adapter.name() + "」不支持原始响应探测。");
        }

        Credential credential = bindings.resolveCredential(userId, adapter.code());
        String raw = probe.probeRaw(credential);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("adapter", adapter.code());
        data.put("warning", "这是平台原始响应，含个人信息，别外传；用完请把 campus.debug-endpoints 关掉。");
        data.put("raw", raw);
        return ApiResponse.ok(data);
    }
}
