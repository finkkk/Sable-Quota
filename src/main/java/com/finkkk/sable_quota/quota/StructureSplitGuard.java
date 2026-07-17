package com.finkkk.sable_quota.quota;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 在方块被移除前，按 Sable 的连接规则预测剩余连通分量。 */
public final class StructureSplitGuard {

    /** Sable 连接共享面或共享边的方块，仅接触顶点不算连接。 */
    private static final BlockPos[] NEIGHBOR_OFFSETS = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0), new BlockPos(-1, -1, 0),
            new BlockPos(1, -1, 0), new BlockPos(-1, 1, 0),
            new BlockPos(1, 0, 1), new BlockPos(-1, 0, -1),
            new BlockPos(1, 0, -1), new BlockPos(-1, 0, 1),
            new BlockPos(0, 1, 1), new BlockPos(0, -1, -1),
            new BlockPos(0, -1, 1), new BlockPos(0, 1, -1)
    };

    private StructureSplitGuard() {
    }

    /**
     * 判断移除 {@code removedPos} 后，新物理结构数量是否会超过可用名额。
     * 原结构会保留一个连通分量，因此其余每个分量各消耗一个新名额。
     */
    public static boolean wouldExceedAvailableSlots(ServerLevel level, ServerSubLevel structure,
                                                    BlockPos removedPos, int availableSlots) {
        if (availableSlots < 0) {
            return false;
        }

        long removed = removedPos.asLong();
        LongOpenHashSet neighborSeeds = new LongOpenHashSet(NEIGHBOR_OFFSETS.length);
        LevelPlot plot = structure.getPlot();
        BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();
        for (BlockPos offset : NEIGHBOR_OFFSETS) {
            long neighbor = BlockPos.asLong(
                    removedPos.getX() + offset.getX(),
                    removedPos.getY() + offset.getY(),
                    removedPos.getZ() + offset.getZ());
            if (isStructureBlock(level, plot, neighbor, removed, probePos)) {
                neighborSeeds.add(neighbor);
            }
        }

        if (neighborSeeds.size() <= 1) {
            return false;
        }

        // 移除单个方块后，连通分量数不可能超过它原本的邻居数。
        if (availableSlots >= neighborSeeds.size() - 1) {
            return false;
        }

        int maximumComponents = availableSlots + 1;
        LongOpenHashSet examined = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        int components = 0;

        // 只需确认“被移除方块周围的种子”能否互相到达；全部种子连通后立即返回，
        // 不必继续遍历结构剩余部分。普通非承重点通常只扫描很小的局部区域。
        while (!neighborSeeds.isEmpty()) {
            components++;
            if (components > maximumComponents) {
                return true;
            }

            long seed = neighborSeeds.iterator().nextLong();
            neighborSeeds.remove(seed);
            examined.add(seed);
            queue.enqueue(seed);
            while (!queue.isEmpty()) {
                long current = queue.dequeueLong();
                currentPos.set(current);

                for (BlockPos offset : NEIGHBOR_OFFSETS) {
                    long neighbor = BlockPos.asLong(
                            currentPos.getX() + offset.getX(),
                            currentPos.getY() + offset.getY(),
                            currentPos.getZ() + offset.getZ());
                    if (neighbor != removed
                            && examined.add(neighbor)
                            && isStructureBlock(level, plot, neighbor, removed, probePos)) {
                        neighborSeeds.remove(neighbor);
                        if (neighborSeeds.isEmpty()) {
                            return false;
                        }
                        queue.enqueue(neighbor);
                    }
                }
            }
        }

        return false;
    }

    private static boolean isStructureBlock(ServerLevel level, LevelPlot plot,
                                            long packedPos, long removed,
                                            BlockPos.MutableBlockPos probePos) {
        if (packedPos == removed) {
            return false;
        }
        probePos.set(packedPos);
        // 每个 Plot 的空间互不重叠；直接检查 Plot 边界比为每个节点查询容器归属更便宜。
        return plot.contains(probePos.getX(), probePos.getZ())
                && !level.getBlockState(probePos).isAir();
    }
}
