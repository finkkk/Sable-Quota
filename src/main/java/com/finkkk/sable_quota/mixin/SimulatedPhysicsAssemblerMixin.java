package com.finkkk.sable_quota.mixin;

import com.finkkk.sable_quota.quota.QuotaCreationContext;
import dev.ryanhcode.sable.Sable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Simulated 物理装配器的服务端执行边界阻止无归属创建。
 *
 * <p>目标是可选模组，因此使用伪 Mixin 保证未安装 Simulated 时仍能加载。
 * 已经位于 Sub-Level 内的装配器属于拆卸操作，必须放行以便玩家回收结构。</p>
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity", remap = false)
public abstract class SimulatedPhysicsAssemblerMixin {

    @Dynamic("目标方法来自可选的 Simulated 模组")
    @Inject(method = "assembleOrDisassemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableQuota$blockAssembly(CallbackInfo callback) {
        BlockEntity assembler = (BlockEntity) (Object) this;
        if (!(assembler.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // 位于 Sub-Level 内表示正在拆卸现有结构，而不是创建新结构。
        if (Sable.HELPER.getContaining(level, assembler.getBlockPos()) == null
                && !QuotaCreationContext.isActive()) {
            callback.cancel();
        }
    }

    @Dynamic("目标方法来自可选的 Simulated 模组")
    @Inject(method = "assembleOrDisassemble", at = @At("RETURN"), remap = false)
    private void sableQuota$clearCreationContext(CallbackInfo callback) {
        if (QuotaCreationContext.isActive()) {
            QuotaCreationContext.end();
        }
    }
}
