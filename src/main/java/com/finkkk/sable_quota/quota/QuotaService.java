package com.finkkk.sable_quota.quota;

import com.mojang.authlib.GameProfile;
import com.finkkk.sable_quota.QuotaConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** 配额业务层：所有入口统一在这里解析个人覆盖、OP 默认值与普通默认值。 */
public final class QuotaService {

    private QuotaService() {
    }

    public static boolean hasCapacity(ServerPlayer player) {
        QuotaSavedData data = QuotaSavedData.get(player.getServer());
        int limit = getLimitInfo(player.getServer(), player.getGameProfile(), data).limit();
        return limit < 0 || data.countOwnedBy(player.getUUID()) < limit;
    }

    public static boolean hasCapacity(MinecraftServer server, UUID playerId) {
        QuotaSavedData data = QuotaSavedData.get(server);
        int limit = getLimit(server, playerId, data);
        return limit < 0 || data.countOwnedBy(playerId) < limit;
    }

    public static boolean tryBeginCreation(ServerPlayer player) {
        if (!hasCapacity(player)) {
            return false;
        }
        QuotaCreationContext.begin(player.getUUID());
        return true;
    }

    public static boolean tryBeginCreation(MinecraftServer server, UUID playerId) {
        if (!hasCapacity(server, playerId)) {
            return false;
        }
        QuotaCreationContext.begin(playerId);
        return true;
    }

    public static int getOwnedCount(MinecraftServer server, UUID playerId) {
        return QuotaSavedData.get(server).countOwnedBy(playerId);
    }

    public static QuotaStatus getStatus(ServerPlayer player) {
        return getStatus(player.getServer(), player.getGameProfile());
    }

    public static QuotaStatus getStatus(MinecraftServer server, GameProfile profile) {
        QuotaSavedData data = QuotaSavedData.get(server);
        return new QuotaStatus(data.countOwnedBy(profile.getId()),
                getLimitInfo(server, profile, data));
    }

    public static int getLimit(ServerPlayer player) {
        return getLimitInfo(player.getServer(), player.getGameProfile()).limit();
    }

    public static int getLimit(MinecraftServer server, UUID playerId) {
        return getLimit(server, playerId, QuotaSavedData.get(server));
    }

    private static int getLimit(MinecraftServer server, UUID playerId, QuotaSavedData data) {
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
        if (onlinePlayer != null) {
            return getLimitInfo(server, onlinePlayer.getGameProfile(), data).limit();
        }

        return server.getProfileCache().get(playerId)
                .map(profile -> getLimitInfo(server, profile, data).limit())
                .orElseGet(() -> {
                    Integer override = data.getPlayerLimitOverride(playerId);
                    return override != null ? override : QuotaConfig.defaultPlayerLimit();
                });
    }

    public static LimitInfo getLimitInfo(MinecraftServer server, GameProfile profile) {
        return getLimitInfo(server, profile, QuotaSavedData.get(server));
    }

    private static LimitInfo getLimitInfo(MinecraftServer server, GameProfile profile, QuotaSavedData data) {
        Integer override = data.getPlayerLimitOverride(profile.getId());
        if (override != null) {
            return new LimitInfo(override, LimitSource.PLAYER_OVERRIDE);
        }
        // 不缓存 OP 状态，升权/降权后下一次查询或创建立即生效。
        if (server.getPlayerList().isOp(profile)) {
            return new LimitInfo(QuotaConfig.defaultOperatorLimit(), LimitSource.OPERATOR_DEFAULT);
        }
        return new LimitInfo(QuotaConfig.defaultPlayerLimit(), LimitSource.PLAYER_DEFAULT);
    }

    public static void setPlayerLimit(MinecraftServer server, UUID playerId, int limit) {
        QuotaSavedData.get(server).setPlayerLimitOverride(playerId, limit);
    }

    public static boolean resetPlayerLimit(MinecraftServer server, UUID playerId) {
        return QuotaSavedData.get(server).clearPlayerLimitOverride(playerId);
    }

    /** Returns the regular-player default for integrations without a player context. */
    public static int getLimit() {
        return QuotaConfig.defaultPlayerLimit();
    }

    public record LimitInfo(int limit, LimitSource source) {
    }

    public record QuotaStatus(int owned, LimitInfo limitInfo) {
    }

    public enum LimitSource {
        PLAYER_OVERRIDE,
        OPERATOR_DEFAULT,
        PLAYER_DEFAULT
    }
}
