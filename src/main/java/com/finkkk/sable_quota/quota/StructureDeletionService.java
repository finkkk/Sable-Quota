package com.finkkk.sable_quota.quota;

import com.finkkk.sable_quota.SableQuota;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 通过 Sable 的正常生命周期删除已加载或磁盘中的 Sub-Level。 */
public final class StructureDeletionService {

    private StructureDeletionService() {
    }

    public static DeleteResult delete(MinecraftServer server, UUID structureId) {
        QuotaCreationContext.end();
        List<ServerLevel> searchOrder = getSearchOrder(server, structureId);

        for (ServerLevel level : searchOrder) {
            ServerSubLevelContainer container = getContainer(level);
            if (container == null) {
                continue;
            }

            ServerSubLevel loaded = getLoaded(container, structureId);
            if (loaded != null) {
                return removeLoaded(server, level, container, loaded, true);
            }
        }

        for (ServerLevel level : searchOrder) {
            ServerSubLevelContainer container = getContainer(level);
            if (container == null) {
                continue;
            }

            SubLevelHoldingChunkMap holdingChunkMap = container.getHoldingChunkMap();
            HoldingSubLevel holding = holdingChunkMap.getHoldingSubLevel(structureId);
            if (holding != null) {
                GlobalSavedSubLevelPointer pointer = holding.pointer();
                if (pointer == null) {
                    // 新近卸载的结构可能尚未分配磁盘指针；只在这种情况下才需要完整保存。
                    holdingChunkMap.saveAll();
                    ServerSubLevel loaded = getLoaded(container, structureId);
                    if (loaded != null) {
                        return removeLoaded(server, level, container, loaded, false);
                    }
                    HoldingSubLevel savedHolding = holdingChunkMap.getHoldingSubLevel(structureId);
                    pointer = savedHolding == null ? holding.pointer() : savedHolding.pointer();
                }
                if (pointer != null) {
                    return loadAndRemove(server, level, container, holdingChunkMap,
                            pointer, structureId);
                }
            }

            GlobalSavedSubLevelPointer pointer = findStoredPointer(holdingChunkMap.getStorage(), structureId);
            if (pointer == null) {
                continue;
            }

            return loadAndRemove(server, level, container, holdingChunkMap, pointer, structureId);
        }

        return new DeleteResult(DeleteStatus.NOT_FOUND, null, false);
    }

    /** 优先搜索配额数据记录的最后维度，避免常见情况下扫描其他维度的存储文件。 */
    private static List<ServerLevel> getSearchOrder(MinecraftServer server, UUID structureId) {
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);

        QuotaSavedData.StructureLocation location =
                QuotaSavedData.get(server).getStructureLocation(structureId);
        if (location == null) {
            return levels;
        }
        levels.sort(Comparator.comparingInt(level ->
                level.dimension().location().equals(location.dimension()) ? 0 : 1));
        return levels;
    }

    private static DeleteResult loadAndRemove(MinecraftServer server, ServerLevel level,
                                              ServerSubLevelContainer container,
                                              SubLevelHoldingChunkMap holdingChunkMap,
                                              GlobalSavedSubLevelPointer pointer, UUID structureId) {
        holdingChunkMap.snatchAndLoad(pointer, structureId);
        ServerSubLevel loaded = getLoaded(container, structureId);
        if (loaded == null) {
            SableQuota.LOGGER.error("Failed to load Sable structure {} from {} in dimension {} for deletion",
                    structureId, pointer, level.dimension().location());
            return new DeleteResult(DeleteStatus.LOAD_FAILED, level.dimension().location(), false);
        }
        return removeLoaded(server, level, container, loaded, false);
    }

    private static DeleteResult removeLoaded(MinecraftServer server, ServerLevel level,
                                             ServerSubLevelContainer container, ServerSubLevel structure,
                                             boolean initiallyLoaded) {
        UUID structureId = structure.getUniqueId();
        clearForceLoadTickets(container, structure);
        container.removeSubLevel(structure, SubLevelRemovalReason.REMOVED);

        // Sable 的实体、plot、holding pointer 和结构文件均由正常删除路径处理。
        container.getHoldingChunkMap().saveAll();
        level.getChunkSource().getDataStorage().save();

        // 生命周期观察器通常已清理配额数据；再次调用用于覆盖观察器缺失等异常情况。
        QuotaSavedData.get(server).removeStructure(structureId);
        server.overworld().getDataStorage().save();

        SableQuota.LOGGER.info("Deleted Sable structure {} from dimension {} (initially loaded: {})",
                structureId, level.dimension().location(), initiallyLoaded);
        return new DeleteResult(DeleteStatus.DELETED, level.dimension().location(), initiallyLoaded);
    }

    private static void clearForceLoadTickets(ServerSubLevelContainer container, ServerSubLevel structure) {
        SubLevelTicketInfo info = container.getAllTickets().get(structure.getUniqueId());
        if (info == null) {
            return;
        }

        List<SubLevelLoadingTicket<?>> tickets = List.copyOf(info.tickets());
        for (SubLevelLoadingTicket<?> ticket : tickets) {
            removeForceLoadTicket(container, structure, ticket);
        }
    }

    private static <T> void removeForceLoadTicket(ServerSubLevelContainer container, ServerSubLevel structure,
                                                  SubLevelLoadingTicket<T> ticket) {
        container.removeForceLoadTicket(structure, ticket.getType(), ticket.getKey());
    }

    private static GlobalSavedSubLevelPointer findStoredPointer(SubLevelStorage storage, UUID structureId) {
        File[] regionFiles = storage.getFolder().toFile().listFiles(
                (directory, name) -> name.endsWith(SubLevelRegionFile.FILE_EXTENSION));
        if (regionFiles == null) {
            return null;
        }
        Arrays.sort(regionFiles, Comparator.comparing(File::getName));

        for (File regionFile : regionFiles) {
            int[] regionCoordinates = parseRegionCoordinates(regionFile.getName());
            if (regionCoordinates == null) {
                continue;
            }

            for (int localX = 0; localX < SubLevelRegionFile.SIDE_LENGTH; localX++) {
                for (int localZ = 0; localZ < SubLevelRegionFile.SIDE_LENGTH; localZ++) {
                    ChunkPos chunkPos = new ChunkPos(
                            regionCoordinates[0] * SubLevelRegionFile.SIDE_LENGTH + localX,
                            regionCoordinates[1] * SubLevelRegionFile.SIDE_LENGTH + localZ);
                    SubLevelHoldingChunk holdingChunk = storage.attemptLoadHoldingChunk(chunkPos);
                    if (holdingChunk == null) {
                        continue;
                    }

                    for (SavedSubLevelPointer pointer : holdingChunk.getSubLevelPointers()) {
                        SubLevelData data = storage.attemptLoadSubLevel(chunkPos, pointer);
                        if (data != null && structureId.equals(data.uuid())) {
                            return new GlobalSavedSubLevelPointer(
                                    chunkPos, pointer.storageIndex(), pointer.subLevelIndex());
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int[] parseRegionCoordinates(String fileName) {
        String withoutExtension = fileName.substring(
                0, fileName.length() - SubLevelRegionFile.FILE_EXTENSION.length());
        String[] parts = withoutExtension.split("\\.");
        if (parts.length != 3 || !parts[0].equals("r")) {
            return null;
        }

        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ServerSubLevelContainer getContainer(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return container instanceof ServerSubLevelContainer serverContainer ? serverContainer : null;
    }

    private static ServerSubLevel getLoaded(ServerSubLevelContainer container, UUID structureId) {
        SubLevel subLevel = container.getSubLevel(structureId);
        return subLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved()
                ? serverSubLevel
                : null;
    }

    public enum DeleteStatus {
        DELETED,
        NOT_FOUND,
        LOAD_FAILED
    }

    public record DeleteResult(DeleteStatus status, ResourceLocation dimension, boolean initiallyLoaded) {
    }
}
