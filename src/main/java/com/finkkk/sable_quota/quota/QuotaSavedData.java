package com.finkkk.sable_quota.quota;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 全服唯一的配额数据源。
 *
 * <p>正向表负责持久化，反向表仅作为运行时索引：计数为 O(1)，列出某位玩家的结构时
 * 只复制该玩家自己的条目，不需要扫描全服结构。</p>
 */
public final class QuotaSavedData extends SavedData {

    private static final String FILE_ID = "sable_quota_structures";
    private static final String STRUCTURE_OWNERS_TAG = "StructureOwners";
    private static final String PLAYER_LIMIT_OVERRIDES_TAG = "PlayerLimitOverrides";
    private static final String STRUCTURE_LOCATIONS_TAG = "StructureLocations";

    private final Map<UUID, UUID> structureOwners = new HashMap<>();
    private final Map<UUID, Set<UUID>> ownerStructures = new HashMap<>();
    private final Map<UUID, Integer> playerLimitOverrides = new HashMap<>();
    private final Map<UUID, StructureLocation> structureLocations = new HashMap<>();

    public static QuotaSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(QuotaSavedData::new, QuotaSavedData::load, DataFixTypes.LEVEL),
                FILE_ID
        );
    }

    private static QuotaSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        QuotaSavedData data = new QuotaSavedData();
        loadStructureOwners(root, data);
        loadPlayerLimitOverrides(root, data);
        loadStructureLocations(root, data);
        return data;
    }

    private static void loadStructureOwners(CompoundTag root, QuotaSavedData data) {
        if (root.contains(STRUCTURE_OWNERS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag owners = root.getCompound(STRUCTURE_OWNERS_TAG);
            for (String structureId : owners.getAllKeys()) {
                try {
                    UUID ownerId = owners.getUUID(structureId);
                    UUID parsedStructureId = UUID.fromString(structureId);
                    data.indexOwnership(parsedStructureId, ownerId);
                } catch (IllegalArgumentException ignored) {
                    // 手改或旧版产生的坏条目不应阻止世界加载。
                }
            }
        }
    }

    private static void loadPlayerLimitOverrides(CompoundTag root, QuotaSavedData data) {
        if (root.contains(PLAYER_LIMIT_OVERRIDES_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag overrides = root.getCompound(PLAYER_LIMIT_OVERRIDES_TAG);
            for (String playerId : overrides.getAllKeys()) {
                try {
                    if (!overrides.contains(playerId, Tag.TAG_INT)) {
                        continue;
                    }
                    int limit = overrides.getInt(playerId);
                    if (limit >= -1) {
                        data.playerLimitOverrides.put(UUID.fromString(playerId), limit);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 忽略损坏的个人配置，回退到身份默认额度。
                }
            }
        }
    }

    private static void loadStructureLocations(CompoundTag root, QuotaSavedData data) {
        if (root.contains(STRUCTURE_LOCATIONS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag locations = root.getCompound(STRUCTURE_LOCATIONS_TAG);
            for (String structureId : locations.getAllKeys()) {
                try {
                    CompoundTag location = locations.getCompound(structureId);
                    UUID parsedStructureId = UUID.fromString(structureId);
                    if (!data.structureOwners.containsKey(parsedStructureId)) {
                        continue;
                    }
                    ResourceLocation dimension = ResourceLocation.tryParse(location.getString("Dimension"));
                    double x = location.getDouble("X");
                    double y = location.getDouble("Y");
                    double z = location.getDouble("Z");
                    if (dimension != null && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                        data.structureLocations.put(parsedStructureId,
                                new StructureLocation(dimension, x, y, z));
                    }
                } catch (IllegalArgumentException ignored) {
                    // 坐标只用于辅助查找，损坏时直接等待结构下次加载后重建。
                }
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        CompoundTag owners = new CompoundTag();
        structureOwners.forEach((structureId, ownerId) -> owners.putUUID(structureId.toString(), ownerId));
        root.put(STRUCTURE_OWNERS_TAG, owners);

        CompoundTag overrides = new CompoundTag();
        playerLimitOverrides.forEach((playerId, limit) -> overrides.putInt(playerId.toString(), limit));
        root.put(PLAYER_LIMIT_OVERRIDES_TAG, overrides);

        CompoundTag locations = new CompoundTag();
        structureLocations.forEach((structureId, location) -> {
            CompoundTag locationTag = new CompoundTag();
            locationTag.putString("Dimension", location.dimension().toString());
            locationTag.putDouble("X", location.x());
            locationTag.putDouble("Y", location.y());
            locationTag.putDouble("Z", location.z());
            locations.put(structureId.toString(), locationTag);
        });
        root.put(STRUCTURE_LOCATIONS_TAG, locations);
        return root;
    }

    public int countOwnedBy(UUID playerId) {
        Set<UUID> structures = ownerStructures.get(playerId);
        return structures == null ? 0 : structures.size();
    }

    public List<UUID> getStructuresOwnedBy(UUID playerId) {
        Set<UUID> structures = ownerStructures.get(playerId);
        return structures == null ? List.of() : List.copyOf(structures);
    }

    public StructureLocation getStructureLocation(UUID structureId) {
        return structureLocations.get(structureId);
    }

    public void updateStructureLocation(UUID structureId, ResourceKey<Level> dimension,
                                        double x, double y, double z) {
        if (!structureOwners.containsKey(structureId)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        StructureLocation location = new StructureLocation(dimension.location(), x, y, z);
        if (!location.equals(structureLocations.put(structureId, location))) {
            setDirty();
        }
    }

    public Integer getPlayerLimitOverride(UUID playerId) {
        return playerLimitOverrides.get(playerId);
    }

    public void setPlayerLimitOverride(UUID playerId, int limit) {
        if (limit < -1) {
            throw new IllegalArgumentException("Player structure limit must be -1 or greater");
        }
        if (!Integer.valueOf(limit).equals(playerLimitOverrides.put(playerId, limit))) {
            setDirty();
        }
    }

    public boolean clearPlayerLimitOverride(UUID playerId) {
        if (playerLimitOverrides.remove(playerId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean addStructure(UUID structureId, UUID ownerId) {
        UUID previousOwner = structureOwners.putIfAbsent(structureId, ownerId);
        if (previousOwner == null) {
            ownerStructures.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(structureId);
            setDirty();
            return true;
        }
        return false;
    }

    /** Copies ownership when Sable splits a new physical structure from an existing one. */
    public boolean inheritStructure(UUID structureId, UUID sourceStructureId) {
        UUID sourceOwner = structureOwners.get(sourceStructureId);
        return sourceOwner != null && addStructure(structureId, sourceOwner);
    }

    public UUID removeStructure(UUID structureId) {
        UUID ownerId = structureOwners.remove(structureId);
        if (ownerId != null) {
            ownerStructures.computeIfPresent(ownerId, (ignored, structures) -> {
                structures.remove(structureId);
                return structures.isEmpty() ? null : structures;
            });
            structureLocations.remove(structureId);
            setDirty();
        }
        return ownerId;
    }

    private void indexOwnership(UUID structureId, UUID ownerId) {
        structureOwners.put(structureId, ownerId);
        ownerStructures.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(structureId);
    }

    public record StructureLocation(ResourceLocation dimension, double x, double y, double z) {
    }
}
