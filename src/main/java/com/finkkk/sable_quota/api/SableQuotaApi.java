package com.finkkk.sable_quota.api;

import com.finkkk.sable_quota.localization.QuotaFeedback;
import com.finkkk.sable_quota.quota.QuotaCreationContext;
import com.finkkk.sable_quota.quota.QuotaService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 供第三方模组代表玩家创建单个 Sable 结构时使用的公开入口。 */
public final class SableQuotaApi {

    private SableQuotaApi() {
    }

    public static boolean hasCapacity(ServerPlayer player) {
        return QuotaService.hasCapacity(Objects.requireNonNull(player, "player"));
    }

    public static int getOwnedCount(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return QuotaService.getOwnedCount(player.getServer(), player.getUUID());
    }

    public static int getLimit() {
        return QuotaService.getLimit();
    }

    public static int getLimit(ServerPlayer player) {
        return QuotaService.getLimit(Objects.requireNonNull(player, "player"));
    }

    /**
     * 检查额度，将下一次 Sable 分配归属到 {@code player}，并在回调结束后清理临时上下文。
     *
     * <p>回调最多只能创建一个 Sub-Level。额度不足或回调返回 {@code null} 时返回空值；
     * 额度不足时会同时向玩家发送提示。</p>
     */
    public static <T> Optional<T> createStructure(ServerPlayer player, Supplier<? extends T> creation) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(creation, "creation");

        if (!QuotaService.tryBeginCreation(player)) {
            QuotaFeedback.sendCreationBlocked(player);
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(creation.get());
        } finally {
            QuotaCreationContext.end();
        }
    }
}
