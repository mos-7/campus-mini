package com.campus.mini.web;

import com.campus.mini.binding.BindingStore;
import com.campus.mini.common.ApiException;
import com.campus.mini.common.ApiResponse;
import com.campus.mini.config.CampusProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录。
 *
 * <h2>为什么没有 {@code jscode2session}</h2>
 *
 * <p>在微信云托管里，网关会自动把调用方的身份放进请求头
 * （{@code X-WX-OPENID} / {@code X-WX-APPID} 等），所以后端<b>不需要</b>再拿
 * {@code wx.login} 的 code 去换 openid，也不用配 AppSecret。
 * 这是云托管相对自建服务器的一个实打实的好处。
 *
 * <p><b>待你验证</b>：请求头的确切名字以部署后的实际值为准 ——
 * 在云托管控制台的「服务日志」里打印一次请求头就能看到。
 * 如果名字不是默认的 {@code x-wx-openid}，改配置
 * {@code campus.wechat.openid-header} 即可，不用动代码。
 *
 * <h2>本地怎么测</h2>
 *
 * <p>把 {@code campus.wechat.mock-enabled} 设为 true（默认就是），
 * 就可以直接传 openid 登录，不需要真 AppID。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final BindingStore store;
    private final JwtService jwt;
    private final CampusProperties properties;

    public AuthController(BindingStore store, JwtService jwt, CampusProperties properties) {
        this.store = store;
        this.jwt = jwt;
        this.properties = properties;
    }

    /** 登录请求体。云托管环境下这些字段都不需要；本地 mock 时用 openid。 */
    public record LoginRequest(String openid, String nickname, String avatarUrl) {
    }

    public record LoginResponse(String token, long userId, String nickname, String avatarUrl) {
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(HttpServletRequest request,
                                            @RequestBody(required = false) LoginRequest body) {
        String openid = resolveOpenid(request, body);

        String nickname = (body == null || body.nickname() == null || body.nickname().isBlank())
                ? "同学" : body.nickname();
        String avatarUrl = body == null ? null : body.avatarUrl();

        long userId = store.upsertUser(openid, nickname, avatarUrl);

        return ApiResponse.ok(new LoginResponse(jwt.issue(userId), userId, nickname, avatarUrl));
    }

    /**
     * 优先用云托管注入的请求头；取不到时，如果 mock 开着就用请求体里的 openid。
     */
    private String resolveOpenid(HttpServletRequest request, LoginRequest body) {
        String openid = request.getHeader(properties.getWechat().getOpenidHeader());
        if (openid == null || openid.isBlank()) {
            // 有些环境大小写或前缀不同，再兜一次
            openid = request.getHeader("x-wx-openid");
        }
        if (openid != null && !openid.isBlank()) {
            return openid;
        }

        if (properties.getWechat().isMockEnabled()
                && body != null && body.openid() != null && !body.openid().isBlank()) {
            log.info("mock 登录：openid={}（生产环境请把 CAMPUS_WECHAT_MOCK_ENABLED 设为 false）",
                    body.openid());
            return body.openid();
        }

        throw ApiException.unauthorized(
                "拿不到微信身份。请在小程序内打开；本地开发时把 CAMPUS_WECHAT_MOCK_ENABLED "
                        + "设为 true 并在请求体里传 openid。");
    }
}
