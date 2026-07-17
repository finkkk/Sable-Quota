package com.finkkk.sable_quota.mixin;

import com.finkkk.sable_quota.localization.QuotaFeedback;
import com.finkkk.sable_quota.quota.QuotaCreationContext;
import com.finkkk.sable_quota.quota.QuotaService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** 检查旋转轴承的延迟装配；无法确认发起玩家的自动化装配会被拒绝。 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity", remap = false)
public abstract class SimulatedSwivelBearingMixin {

    @Unique
    private boolean sableQuota$creationContextStarted;

    @Dynamic("目标方法来自可选的 Simulated 模组")
    @Inject(method = "assemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableQuota$blockAssembly(CallbackInfo callback) {
        BlockEntity bearing = (BlockEntity) (Object) this;
        if (!(bearing.getLevel() instanceof ServerLevel level)) {
            return;
        }

        UUID ownerId = QuotaCreationContext.consumeSwivelBearing(level, bearing.getBlockPos());
        if (ownerId == null || !QuotaService.tryBeginCreation(level.getServer(), ownerId)) {
            ServerPlayer player = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
            if (player != null) {
                QuotaFeedback.sendCreationBlocked(player);
            }
            callback.cancel();
            return;
        }

        sableQuota$creationContextStarted = true;
    }

    @Dynamic("目标方法来自可选的 Simulated 模组")
    @Inject(method = "assemble", at = @At("RETURN"), remap = false)
    private void sableQuota$clearCreationContext(CallbackInfo callback) {
        if (sableQuota$creationContextStarted) {
            QuotaCreationContext.end();
            sableQuota$creationContextStarted = false;
        }
    }
}
