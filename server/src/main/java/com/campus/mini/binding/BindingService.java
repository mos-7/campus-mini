package com.campus.mini.binding;

import com.campus.mini.adapter.AdapterRegistry;
import com.campus.mini.adapter.CampusAdapter;
import com.campus.mini.adapter.impl.ChaoxingAdapter;
import com.campus.mini.adapter.model.Models.Credential;
import com.campus.mini.adapter.model.Models.VerifyResult;
import com.campus.mini.common.ApiException;
import com.campus.mini.config.CampusProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 账号绑定。
 *
 * <p>绑定流程：<b>先验证，再落库</b> —— 凭据不对就不要存进去，免得留一堆废记录。
 */
@Service
public class BindingService {

    private final AdapterRegistry registry;
    private final BindingStore store;
    private final CredentialVault vault;
    private final CampusProperties properties;

    public BindingService(AdapterRegistry registry,
                          BindingStore store,
                          CredentialVault vault,
                          CampusProperties properties) {
        this.registry = registry;
        this.store = store;
        this.vault = vault;
        this.properties = properties;
    }

    /** 前端「服务绑定中心」的一张卡片。 */
    public record AdapterCard(
            String code,
            String name,
            String description,
            String loginMode,
            String loginModeLabel,
            boolean enabled,
            boolean bound,
            String accountLabel,
            String lastSyncAt,
            String lastError,
            List<CapabilityView> capabilities
    ) {
    }

    /** 一个能力项的展示状态。 */
    public record CapabilityView(String code, String label, boolean available) {
    }

    /**
     * 列出所有平台 + 当前用户的绑定状态。
     *
     * <p>对应参考截图里那个「服务绑定中心 0/3 已连接」。
     */
    public List<AdapterCard> catalog(long userId) {
        Map<String, BindingStore.Binding> bound = store.listBindings(userId).stream()
                .collect(Collectors.toMap(BindingStore.Binding::adapterCode, b -> b, (a, b) -> a));

        List<AdapterCard> cards = new ArrayList<>();
        for (CampusAdapter adapter : registry.all()) {
            BindingStore.Binding binding = bound.get(adapter.code());
            boolean isBound = binding != null && "BOUND".equals(binding.status());

            List<CapabilityView> capabilities = adapter.capabilities().stream()
                    .sorted(Comparator.comparing(capability -> capability.name()))
                    .map(c -> new CapabilityView(c.name(), c.label(), isBound))
                    .toList();

            cards.add(new AdapterCard(
                    adapter.code(),
                    adapter.name(),
                    adapter.description(),
                    adapter.loginMode().name(),
                    adapter.loginMode().label(),
                    isEnabled(adapter.code()),
                    isBound,
                    binding == null ? null : binding.accountLabel(),
                    binding == null || binding.lastSyncAt() == null
                            ? null : binding.lastSyncAt().toString(),
                    binding == null ? null : binding.lastError(),
                    capabilities));
        }
        return cards;
    }

    /** 已连接 / 总数，给「0 / 3 已连接」用。 */
    public int[] progress(long userId) {
        List<AdapterCard> cards = catalog(userId);
        long bound = cards.stream().filter(AdapterCard::bound).count();
        return new int[]{(int) bound, cards.size()};
    }

    /**
     * 绑定。先调适配器校验，通过了才加密落库。
     */
    public VerifyResult bind(long userId, String adapterCode, String username, String secret) {
        CampusAdapter adapter = require(adapterCode);

        if (secret == null || secret.isBlank()) {
            throw ApiException.badRequest("请填写密码、Token 或课表内容。");
        }

        VerifyResult result = adapter.verify(new Credential(username, secret));
        if (!result.ok()) {
            return result;
        }

        String label = result.displayName() != null && !result.displayName().isBlank()
                ? result.displayName()
                : (username == null || username.isBlank() ? adapter.name() : username);

        store.saveBinding(userId, adapter.code(), adapter.loginMode().name(),
                label, vault.encrypt(secret));
        return result;
    }

    public void unbind(long userId, String adapterCode) {
        require(adapterCode);
        store.deleteBinding(userId, adapterCode);
    }

    /** 取出明文凭据。只有 {@code SyncService} 该调它。 */
    public Credential resolveCredential(long userId, String adapterCode) {
        BindingStore.Binding binding = store.findBinding(userId, adapterCode)
                .orElseThrow(() -> ApiException.badRequest("还没绑定该平台"));
        return new Credential(binding.accountLabel(), vault.decrypt(binding.secretEncrypted()));
    }

    public boolean isBound(long userId, String adapterCode) {
        return store.findBinding(userId, adapterCode)
                .map(b -> "BOUND".equals(b.status()))
                .orElse(false);
    }

    public Optional<BindingStore.Binding> find(long userId, String adapterCode) {
        return store.findBinding(userId, adapterCode);
    }

    // ------------------------------------------------------------------

    private CampusAdapter require(String adapterCode) {
        CampusAdapter adapter = registry.find(adapterCode);
        if (adapter == null) {
            throw ApiException.notFound("没有这个平台：" + adapterCode);
        }
        return adapter;
    }

    /**
     * 平台是否已开放。
     *
     * <p>对应参考项目里的 {@code status: PENDING_VERIFICATION}：过抓包联调的适配器才开放，
     * 这样前端能把卡片显示成「暂未开放」，而不是让用户绑了半天发现根本不能用。
     */
    private boolean isEnabled(String adapterCode) {
        return switch (adapterCode) {
            case ChaoxingAdapter.CODE -> properties.getChaoxing().isEnabled();
            default -> true;   // manual 等本地适配器永远可用
        };
    }
}
