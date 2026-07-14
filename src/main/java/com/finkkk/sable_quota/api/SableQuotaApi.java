package com.finkkk.sable_quota.api;

import com.finkkk.sable_quota.SableQuota;
import com.finkkk.sable_quota.quota.QuotaCreationContext;
import com.finkkk.sable_quota.quota.QuotaService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Public integration entry point for mods that create one Sable structure for a player. */
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
     * Checks quota, associates the next Sable allocation with {@code player},
     * and always clears the temporary context afterward.
     *
     * <p>The supplied action must create at most one new sub-level. An empty
     * result means the quota check was denied or the callback returned null;
     * denied players are notified.</p>
     */
    public static <T> Optional<T> createStructure(ServerPlayer player, Supplier<? extends T> creation) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(creation, "creation");

        if (!QuotaService.tryBeginCreation(player)) {
            SableQuota.sendCreationBlockedMessage(player);
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(creation.get());
        } finally {
            QuotaCreationContext.end();
        }
    }
}
