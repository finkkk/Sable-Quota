package com.finkkk.sable_quota.quota;

import com.finkkk.sable_quota.SableQuota;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** 将 Sable 的创建、卸载和删除生命周期同步到持久化配额数据。 */
public final class QuotaLifecycleObserver implements SubLevelObserver {

    private final ServerLevel level;

    public QuotaLifecycleObserver(ServerLevel level) {
        this.level = level;
    }

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        UUID ownerId = QuotaCreationContext.consumeCurrentOwner();
        if (ownerId == null) {
            // Sable 没有玩家 owner 字段。无法归属的系统/第三方创建必须静默放行，
            // 不能为了强行计数而误记到其他玩家或破坏结构。
            return;
        }

        QuotaSavedData data = QuotaSavedData.get(level.getServer());
        if (data.addStructure(subLevel.getUniqueId(), ownerId)) {
            var position = subLevel.logicalPose().position();
            data.updateStructureLocation(subLevel.getUniqueId(), level.dimension(),
                    position.x(), position.y(), position.z());
            SableQuota.LOGGER.debug("Assigned Sable structure {} to player {}", subLevel.getUniqueId(), ownerId);
        }
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        QuotaSavedData data = QuotaSavedData.get(level.getServer());
        if (reason == SubLevelRemovalReason.UNLOADED) {
            var position = subLevel.logicalPose().position();
            data.updateStructureLocation(subLevel.getUniqueId(), level.dimension(),
                    position.x(), position.y(), position.z());
            return;
        }
        if (reason != SubLevelRemovalReason.REMOVED) {
            return;
        }

        UUID ownerId = data.removeStructure(subLevel.getUniqueId());
        if (ownerId != null) {
            SableQuota.LOGGER.debug("Released Sable structure {} from player {}", subLevel.getUniqueId(), ownerId);
        }
    }
}
