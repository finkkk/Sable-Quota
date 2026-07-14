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
 * Stops Simulated's physics assembler at its server-side action boundary.
 *
 * <p>The optional target keeps Sable Quota loadable when Simulated is not
 * installed. Existing sub-levels are deliberately allowed through so players
 * can still disassemble and recover them.</p>
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity", remap = false)
public abstract class SimulatedPhysicsAssemblerMixin {

    @Dynamic("Method belongs to the optional Simulated mod and is not on the compile classpath")
    @Inject(method = "assembleOrDisassemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableQuota$blockAssembly(CallbackInfo callback) {
        BlockEntity assembler = (BlockEntity) (Object) this;
        if (!(assembler.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // An assembler already inside a sub-level is requesting disassembly,
        // not the creation of a new physical structure.
        if (Sable.HELPER.getContaining(level, assembler.getBlockPos()) == null
                && !QuotaCreationContext.isActive()) {
            callback.cancel();
        }
    }

    @Dynamic("Method belongs to the optional Simulated mod and is not on the compile classpath")
    @Inject(method = "assembleOrDisassemble", at = @At("RETURN"), remap = false)
    private void sableQuota$clearCreationContext(CallbackInfo callback) {
        if (QuotaCreationContext.isActive()) {
            QuotaCreationContext.end();
        }
    }
}
