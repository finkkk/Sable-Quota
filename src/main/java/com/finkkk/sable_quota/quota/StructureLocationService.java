package com.finkkk.sable_quota.quota;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 按需定位结构；查询过程不会加载区块或唤醒未加载的 Sub-Level。 */
public final class StructureLocationService {

    private StructureLocationService() {
    }

    public static List<UUID> getOwnedStructureIds(MinecraftServer server, UUID playerId) {
        return QuotaSavedData.get(server).getStructuresOwnedBy(playerId);
    }

    public static Optional<LocatedStructure> locate(MinecraftServer server, UUID structureId) {
        QuotaSavedData data = QuotaSavedData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }

            SubLevel subLevel = container.getSubLevel(structureId);
            if (subLevel != null && !subLevel.isRemoved()) {
                var position = subLevel.logicalPose().position();
                data.updateStructureLocation(structureId, level.dimension(),
                        position.x(), position.y(), position.z());
                return Optional.of(new LocatedStructure(
                        structureId,
                        level.dimension().location(),
                        BlockPos.containing(position.x(), position.y(), position.z()),
                        true));
            }
        }

        QuotaSavedData.StructureLocation lastKnown = data.getStructureLocation(structureId);
        if (lastKnown == null) {
            return Optional.empty();
        }
        return Optional.of(new LocatedStructure(
                structureId,
                lastKnown.dimension(),
                BlockPos.containing(lastKnown.x(), lastKnown.y(), lastKnown.z()),
                false));
    }

    public record LocatedStructure(UUID structureId, ResourceLocation dimension,
                                   BlockPos blockPos, boolean loaded) {
    }
}
