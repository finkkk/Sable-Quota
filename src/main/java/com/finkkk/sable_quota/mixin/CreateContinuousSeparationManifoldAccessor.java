package com.finkkk.sable_quota.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the two values used to decide whether Create has a resolvable
 * discrete collision. The target belongs to the optional Create dependency.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.collision.ContinuousOBBCollider$ContinuousSeparationManifold", remap = false)
public interface CreateContinuousSeparationManifoldAccessor {

    @Accessor(value = "isDiscreteCollision", remap = false)
    boolean sableQuota$isDiscreteCollision();

    @Accessor(value = "axis", remap = false)
    Vec3 sableQuota$getAxis();
}
