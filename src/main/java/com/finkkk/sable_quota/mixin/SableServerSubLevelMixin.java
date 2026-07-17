package com.finkkk.sable_quota.mixin;

import com.finkkk.sable_quota.SableQuota;
import com.finkkk.sable_quota.localization.QuotaFeedback;
import com.finkkk.sable_quota.quota.QuotaSavedData;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Sable 拆分物理结构时，让新结构继承来源结构的配额所有者。 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableServerSubLevelMixin {

    @Inject(method = "setSplitFrom", at = @At("RETURN"), remap = false)
    private void sableQuota$inheritSplitOwner(ServerSubLevel source, Pose3d originalPose, CallbackInfo callback) {
        ServerSubLevel split = (ServerSubLevel) (Object) this;
        QuotaSavedData data = QuotaSavedData.get(split.getLevel().getServer());
        UUID ownerId = data.inheritStructure(split.getUniqueId(), source.getUniqueId());
        if (ownerId != null) {
            var position = split.logicalPose().position();
            data.updateStructureLocation(split.getUniqueId(), split.getLevel().dimension(),
                    position.x(), position.y(), position.z());
            SableQuota.LOGGER.debug("Inherited Sable structure {} ownership from {}",
                    split.getUniqueId(), source.getUniqueId());
            QuotaFeedback.sendProgress(split.getLevel().getServer(), ownerId);
        }
    }
}
