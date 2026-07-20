package com.finkkk.sable_quota.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents Create's continuous OBB collider from dereferencing a missing
 * separation axis for a completely degenerate discrete collision candidate.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.collision.ContinuousOBBCollider", remap = false)
public abstract class CreateContinuousOBBColliderMixin {

    /**
     * Create 6.0.10 leaves {@code axis} null when every tested projection has
     * zero centre distance. In that case its discrete fallback cannot produce
     * a meaningful response and would otherwise dereference {@code axis.x}.
     * Treat only that candidate as unresolved so the remaining collision
     * candidates can still be evaluated normally.
     */
    @Dynamic("Target method and manifold fields belong to the optional Create mod")
    @Redirect(
            method = "collideMany",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/simibubi/create/foundation/collision/ContinuousOBBCollider$ContinuousSeparationManifold;isDiscreteCollision:Z",
                    opcode = Opcodes.GETFIELD
            ),
            remap = false,
            require = 0
    )
    private static boolean sableQuota$skipDiscreteCollisionWithoutAxis(@Coerce Object manifold) {
        CreateContinuousSeparationManifoldAccessor accessor =
                (CreateContinuousSeparationManifoldAccessor) manifold;
        return accessor.sableQuota$isDiscreteCollision() && accessor.sableQuota$getAxis() != null;
    }
}
