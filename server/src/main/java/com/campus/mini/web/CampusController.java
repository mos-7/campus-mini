package com.campus.mini.web;

import com.campus.mini.adapter.model.Models.Course;
import com.campus.mini.adapter.model.Models.VerifyResult;
import com.campus.mini.binding.BindingService;
import com.campus.mini.common.ApiResponse;
import com.campus.mini.schedule.ScheduleService;
import com.campus.mini.schedule.ScheduleStore;
import com.campus.mini.sync.SyncService;
import com.campus.mini.sync.SyncTaskStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 主 API。所有接口都需要登录（{@link AuthInterceptor} 注入 {@code userId}）。
 *
 * <h2>为什么同步接口要拆成"发起 + 轮询"</h2>
 *
 * <p>云托管 {@code CallContainer} 超时 15 秒。抓课表要十几秒，所以
 * {@code POST /api/sync/{code}} 只负责建任务并<b>立刻</b>返回 taskId，
 * 前端再轮询 {@code GET /api/sync/tasks/{id}}。
 */
@RestController
@RequestMapping("/api")
public class CampusController {

    private final BindingService bindings;
    private final SyncService sync;
    private final ScheduleService schedule;
    private final ScheduleStore store;

    public CampusController(BindingService bindings,
                            SyncService sync,
                            ScheduleService schedule,
                            ScheduleStore store) {
        this.bindings = bindings;
        this.sync = sync;
        this.schedule = schedule;
        this.store = store;
    }

    // ------------------------------------------------------------------
    // 服务绑定中心
    // ------------------------------------------------------------------

    /** 平台卡片列表 + 「0 / 3 已连接」进度。 */
    @GetMapping("/adapters")
    public ApiResponse<Map<String, Object>> adapters(@RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        int[] progress = bindings.progress(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("boundCount", progress[0]);
        data.put("totalCount", progress[1]);
        data.put("cards", bindings.catalog(userId));
        return ApiResponse.ok(data);
    }

    public record BindRequest(String adapterCode, String username, String secret) {
    }

    /** 绑定。先验证凭据，通过了才落库。 */
    @PostMapping("/bindings")
    public ApiResponse<Map<String, Object>> bind(@RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
                                                 @RequestBody BindRequest request) {
        VerifyResult result = bindings.bind(userId, request.adapterCode(), request.username(), request.secret());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", result.ok());
        data.put("message", result.message());
        // ★ 返回【实际存下来的】accountLabel，而不是 result.displayName()。
        //   accountLabel 决定后续同步用哪个用户名登录，把它回显出来便于一眼确认；
        //   适配器的 displayName 现在可能是 null（它只该放可选的可读名）。
        if (result.ok()) {
            data.put("accountLabel", bindings.find(userId, request.adapterCode())
                    .map(b -> b.accountLabel())
                    .orElse(null));
        }
        return ApiResponse.ok(data);
    }

    @DeleteMapping("/bindings/{adapterCode}")
    public ApiResponse<Void> unbind(@RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
                                    @PathVariable String adapterCode) {
        bindings.unbind(userId, adapterCode);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /** 发起同步，立刻返回 taskId。 */
    @PostMapping("/sync/{adapterCode}")
    public ApiResponse<Map<String, Object>> startSync(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
            @PathVariable String adapterCode) {
        String taskId = sync.start(userId, adapterCode);
        return ApiResponse.ok(Map.<String, Object>of("taskId", taskId));
    }

    /** 轮询任务进度。前端建议 1.5 秒一次，别更快。 */
    @GetMapping("/sync/tasks/{taskId}")
    public ApiResponse<SyncTaskStore.Task> taskStatus(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
            @PathVariable String taskId) {
        return ApiResponse.ok(sync.status(userId, taskId));
    }

    // ------------------------------------------------------------------
    // 课表
    // ------------------------------------------------------------------

    /** 某一周的课表。{@code week} 省略时取当前周。 */
    @GetMapping("/schedule")
    public ApiResponse<ScheduleService.WeekView> week(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId,
            @RequestParam(defaultValue = "0") int week) {
        return ApiResponse.ok(schedule.week(userId, week));
    }

    /** 今日课程 —— 首页那张卡片。 */
    @GetMapping("/schedule/today")
    public ApiResponse<ScheduleService.TodayView> today(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        return ApiResponse.ok(schedule.today(userId));
    }

    @GetMapping("/courses")
    public ApiResponse<List<Course>> courses(@RequestAttribute(AuthInterceptor.USER_ID_ATTR) long userId) {
        return ApiResponse.ok(store.listCourses(userId));
    }

    // ------------------------------------------------------------------
    // 公告
    // ------------------------------------------------------------------

    @GetMapping("/announcements")
    public ApiResponse<List<ScheduleStore.Announcement>> announcements() {
        return ApiResponse.ok(store.listAnnouncements(10));
    }
}
