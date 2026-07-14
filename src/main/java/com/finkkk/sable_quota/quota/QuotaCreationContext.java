package com.finkkk.sable_quota.quota;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 将一次同步的 Sable 分配与发起玩家关联。
 *
 * <p>主创建上下文使用 ThreadLocal，不进入全局锁；旋转轴承的延迟交互使用有界队列，
 * 防止恶意交互导致临时记录无限增长。</p>
 */
public final class QuotaCreationContext {

    private static final long PENDING_LIFETIME_TICKS = 20;
    private static final int MAX_PENDING_PER_SERVER = 4096;
    private static final ThreadLocal<UUID> CURRENT_OWNER = new ThreadLocal<>();
    private static final Map<MinecraftServer, PendingServerState> PENDING_SWIVEL_BEARINGS = new HashMap<>();

    private QuotaCreationContext() {
    }

    public static void begin(UUID ownerId) {
        CURRENT_OWNER.set(ownerId);
    }

    /** 只允许下一次分配消费玩家 UUID，避免一次入口意外认领多个结构。 */
    public static UUID consumeCurrentOwner() {
        UUID ownerId = CURRENT_OWNER.get();
        CURRENT_OWNER.remove();
        return ownerId;
    }

    public static boolean isActive() {
        return CURRENT_OWNER.get() != null;
    }

    public static void end() {
        CURRENT_OWNER.remove();
    }

    public static void rememberSwivelBearing(ServerLevel level, BlockPos pos, UUID ownerId) {
        long gameTime = level.getGameTime();
        PendingServerState state = PENDING_SWIVEL_BEARINGS.computeIfAbsent(
                level.getServer(), ignored -> new PendingServerState());
        state.remember(new PendingKey(level.dimension(), pos.asLong()), ownerId, gameTime);
    }

    public static UUID consumeSwivelBearing(ServerLevel level, BlockPos pos) {
        PendingServerState state = PENDING_SWIVEL_BEARINGS.get(level.getServer());
        return state == null
                ? null
                : state.consume(new PendingKey(level.dimension(), pos.asLong()), level.getGameTime());
    }

    public static void clearAll() {
        CURRENT_OWNER.remove();
        PENDING_SWIVEL_BEARINGS.clear();
    }

    private record PendingKey(ResourceKey<Level> dimension, long blockPos) {
    }

    private record PendingOwner(UUID ownerId, long createdAt) {
    }

    private record PendingExpiration(PendingKey key, long createdAt) {
    }

    /** 摊销 O(1) 的过期队列，不在每次交互时扫描整张表。 */
    private static final class PendingServerState {
        private final Map<PendingKey, PendingOwner> owners = new HashMap<>();
        private final ArrayDeque<PendingExpiration> expirations = new ArrayDeque<>();

        private void remember(PendingKey key, UUID ownerId, long gameTime) {
            prune(gameTime);
            if (expirations.size() >= MAX_PENDING_PER_SERVER) {
                evictOldest();
            }
            if (!owners.containsKey(key) && owners.size() >= MAX_PENDING_PER_SERVER) {
                evictOldest();
            }

            owners.put(key, new PendingOwner(ownerId, gameTime));
            expirations.addLast(new PendingExpiration(key, gameTime));
        }

        private UUID consume(PendingKey key, long gameTime) {
            prune(gameTime);
            PendingOwner pending = owners.remove(key);
            return pending == null ? null : pending.ownerId();
        }

        private void prune(long gameTime) {
            while (!expirations.isEmpty()
                    && gameTime - expirations.peekFirst().createdAt() > PENDING_LIFETIME_TICKS) {
                removeIfCurrent(expirations.removeFirst());
            }
        }

        private void evictOldest() {
            while (!expirations.isEmpty()) {
                PendingExpiration oldest = expirations.removeFirst();
                if (removeIfCurrent(oldest)) {
                    return;
                }
            }
        }

        private boolean removeIfCurrent(PendingExpiration expiration) {
            PendingOwner current = owners.get(expiration.key());
            if (current != null && current.createdAt() == expiration.createdAt()) {
                owners.remove(expiration.key());
                return true;
            }
            return false;
        }
    }
}
