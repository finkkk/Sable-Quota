package com.finkkk.sable_quota.localization;

import com.finkkk.sable_quota.QuotaConfig;
import com.finkkk.sable_quota.quota.QuotaService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** 统一生成并发送配额相关的 Action Bar 提示。 */
public final class QuotaFeedback {

    private static final String CREATION_BLOCKED = "message.sable_quota.creation_blocked";
    private static final String SPLIT_BLOCKED = "message.sable_quota.split_blocked";
    private static final String QUOTA_PROGRESS = "message.sable_quota.progress";
    private static final String COMMAND_CREATION_BLOCKED = "message.sable_quota.command_creation_blocked";

    private QuotaFeedback() {
    }

    /** 发送创建额度不足提示，并兼容配置文件中的自定义文本。 */
    public static void sendCreationBlocked(ServerPlayer player) {
        QuotaService.QuotaStatus status = QuotaService.getStatus(player);
        int owned = status.owned();
        int limit = status.limitInfo().limit();
        String configuredMessage = QuotaConfig.creationBlockedMessage();
        Component message = configuredMessage.isBlank()
                ? ServerTranslations.text(player, CREATION_BLOCKED, owned, limit)
                : Component.literal(configuredMessage
                        .replace("{owned}", Integer.toString(owned))
                        .replace("{limit}", Integer.toString(limit)));
        sendActionBar(player, message, ChatFormatting.RED);
    }

    /** 发送“本次破坏会造成超额断裂”的提示。 */
    public static void sendSplitBlocked(ServerPlayer player, int owned, int limit) {
        sendActionBar(player,
                ServerTranslations.text(player, SPLIT_BLOCKED, owned, limit),
                ChatFormatting.RED);
    }

    /** 发送高风险多结构命令已被禁用的提示。 */
    public static void sendCommandCreationBlocked(ServerPlayer player) {
        sendActionBar(player,
                ServerTranslations.text(player, COMMAND_CREATION_BLOCKED),
                ChatFormatting.RED);
    }

    /** 结构登记成功后，向在线所有者显示最新的“已用/上限”。 */
    public static void sendProgress(MinecraftServer server, UUID ownerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
        if (player == null) {
            return;
        }

        QuotaService.QuotaUsage usage = QuotaService.getUsage(server, ownerId);
        sendActionBar(player,
                ServerTranslations.text(player, QUOTA_PROGRESS,
                        usage.owned(), formatLimit(usage.limit())),
                ChatFormatting.WHITE);
    }

    private static String formatLimit(int limit) {
        return limit < 0 ? "∞" : Integer.toString(limit);
    }

    private static void sendActionBar(ServerPlayer player, Component message, ChatFormatting color) {
        player.displayClientMessage(message.copy().withStyle(color), true);
    }
}
