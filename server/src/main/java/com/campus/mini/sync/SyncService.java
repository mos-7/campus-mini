package com.campus.mini.sync;

import com.campus.mini.adapter.AdapterException;
import com.campus.mini.adapter.AdapterRegistry;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.FetchResult;
import com.campus.mini.binding.BindingStore;
import com.campus.mini.binding.CredentialVault;
import com.campus.mini.common.ApiException;
import com.campus.mini.schedule.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 异步同步服务。
 *
 * <h2>为什么必须异步</h2>
 *
 * <p>云托管的 {@code CallContainer} 超时上限是 <b>15 秒</b>，而登录 + 抓课表动辄十几秒。
 * 所以：接口<b>立即</b>返回一个 taskId，真正干活在后台线程，前端轮询进度。
 * 这就是 MoocPass 里 {@code TaskQueue} / {@code TaskWorker} 那套东西的正当版本。
 *
 * <h2>云托管的两个坑</h2>
 *
 * <ol>
 *   <li>容器缩容/重启会杀掉后台线程 → 任务永远停在 RUNNING。
 *       对策：状态落库 + 启动时 {@link SyncTaskStore#failStale} 收拾僵尸任务。</li>
 *   <li>实例最小副本如果是 0，服务会在无请求时缩到零 → 后台任务必被杀。
 *       对策：把最小副本设为 1（见 docs/deploy-cloudrun.md）。</li>
 * </ol>
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** 超过这个分钟数还停在 PENDING/RUNNING 的任务，认为已经死了。 */
    private static final int STALE_MINUTES = 5;

    private final AdapterRegistry registry;
    private final BindingStore bindingStore;
    private final CredentialVault vault;
    private final ScheduleStore scheduleStore;
    private final SyncTaskStore taskStore;
    private final ExecutorService pool;

    public SyncService(AdapterRegistry registry,
                       BindingStore bindingStore,
                       CredentialVault vault,
                       ScheduleStore scheduleStore,
                       SyncTaskStore taskStore) {
        this.registry = registry;
        this.bindingStore = bindingStore;
        this.vault = vault;
        this.scheduleStore = scheduleStore;
        this.taskStore = taskStore;
        this.pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "campus-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动时清理僵尸任务。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        try {
            int cleaned = taskStore.failStale(STALE_MINUTES);
            if (cleaned > 0) {
                log.warn("清理了 {} 个卡死的同步任务（服务重启或缩容导致）", cleaned);
            }
        } catch (Exception e) {
            log.warn("清理僵尸同步任务失败：{}", e.getMessage());
        }
    }

    /**
     * 发起同步。立刻返回 taskId，不阻塞。
     *
     * @throws ApiException 没绑定 / 平台不存在时抛出
     */
    public String start(long userId, String adapterCode) {
        CampusAdapter adapter = registry.find(adapterCode);
        if (adapter == null) {
            throw ApiException.notFound("没有这个平台：" + adapterCode);
        }

        BindingStore.Binding binding = bindingStore.findBinding(userId, adapterCode)
                .orElseThrow(() -> ApiException.badRequest(
                        "还没绑定「" + adapter.name() + "」，请先绑定账号。"));

        String taskId = UUID.randomUUID().toString().replace("-", "");
        taskStore.create(taskId, userId, adapterCode);

        pool.submit(() -> run(taskId, userId, adapter, binding));
        return taskId;
    }

    /** 查任务状态。只能查自己的任务。 */
    public SyncTaskStore.Task status(long userId, String taskId) {
        SyncTaskStore.Task task = taskStore.find(taskId)
                .orElseThrow(() -> ApiException.notFound("没有这个同步任务"));
        if (task.userId() != userId) {
            // 不区分"不存在"和"不是你的"，避免探测别人的任务 id
            throw ApiException.notFound("没有这个同步任务");
        }
        return task;
    }

    // ------------------------------------------------------------------

    private void run(String taskId, long userId, CampusAdapter adapter, BindingStore.Binding binding) {
        try {
            taskStore.update(taskId, 15, "正在解密凭据…");
            String secret = vault.decrypt(binding.secretEncrypted());

            taskStore.update(taskId, 35, "正在登录 " + adapter.name() + "…");
            Credential credential = new Credential(binding.accountLabel(), secret);

            taskStore.update(taskId, 60, "正在抓取课程与课表…");
            FetchResult result = adapter.fetch(credential);

            taskStore.update(taskId, 85, "正在写入本地缓存…");
            scheduleStore.replaceAll(userId, adapter.code(), result.courses(), result.sessions());

            bindingStore.markSyncResult(userId, adapter.code(), true, null);
            taskStore.succeed(taskId, result.message(),
                    result.courses().size(), result.sessions().size());

            log.info("同步完成 user={} adapter={} 课程={} 课表格子={}",
                    userId, adapter.code(), result.courses().size(), result.sessions().size());

        } catch (Exception e) {
            String message = e instanceof AdapterException
                    ? e.getMessage()
                    : "同步失败：" + e.getMessage();
            log.warn("同步失败 user={} adapter={} : {}", userId, adapter.code(), message);

            taskStore.fail(taskId, message);
            bindingStore.markSyncResult(userId, adapter.code(), false, message);
        }
    }

    /** 优雅停机，给正在跑的任务一点时间。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
