package com.finkkk.sable_quota.mixin;

import com.finkkk.sable_quota.SableQuota;
import com.finkkk.sable_quota.localization.QuotaFeedback;
import com.finkkk.sable_quota.quota.QuotaSavedData;
import com.finkkk.sable_quota.quota.QuotaService;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** 在 Sable 分配碎片结构前执行最后一道额度检查。 */
@Mixin(value = SubLevelHeatMapManager.class, remap = false)
public abstract class SubLevelHeatMapManagerMixin {

    @Shadow
    @Final
    private ServerSubLevel subLevel;

    @Inject(
            method = "split",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/companion/math/BoundingBox3i;from(Ljava/lang/Iterable;)Ldev/ryanhcode/sable/companion/math/BoundingBox3i;"
            ),
            cancellable = true,
            remap = false
    )
    private void sableQuota$stopSplittingAtQuota(CallbackInfo callback) {
        MinecraftServer server = subLevel.getLevel().getServer();
        QuotaSavedData data = QuotaSavedData.get(server);
        UUID ownerId = data.getStructureOwner(subLevel.getUniqueId());
        if (ownerId == null || QuotaService.hasCapacity(server, ownerId)) {
            return;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            QuotaFeedback.sendCreationBlocked(owner);
        }
        SableQuota.LOGGER.debug("Blocked Sable structure {} from splitting beyond player {}'s quota",
                subLevel.getUniqueId(), ownerId);
        callback.cancel();
    }
}
