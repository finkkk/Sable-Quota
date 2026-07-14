package com.finkkk.sable_quota.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.finkkk.sable_quota.QuotaConfig;
import com.finkkk.sable_quota.SableQuota;
import com.finkkk.sable_quota.localization.ServerTranslations;
import com.finkkk.sable_quota.quota.QuotaService;
import com.finkkk.sable_quota.quota.StructureLocationService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class SableQuotaCommands {

    private static final int STRUCTURES_PER_PAGE = 10;
    private SableQuotaCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sablequota")
                .then(Commands.literal("get")
                        .executes(context -> sendQuota(
                                context.getSource().getPlayerOrException().getGameProfile(), context.getSource()))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> sendQuota(getSingleProfile(context), context.getSource()))))
                .then(Commands.literal("list")
                        .executes(context -> listStructures(
                                context.getSource().getPlayerOrException().getGameProfile(), context.getSource(), 1))
                        .then(Commands.literal("page")
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> listStructures(
                                                context.getSource().getPlayerOrException().getGameProfile(),
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page")))))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> listStructures(
                                        getSingleProfile(context), context.getSource(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> listStructures(
                                                getSingleProfile(context), context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page"))))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(-1))
                                        .executes(SableQuotaCommands::setQuota))))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(SableQuotaCommands::resetQuota)))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(SableQuotaCommands::reloadConfig)));
    }

    private static GameProfile getSingleProfile(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "player");
        if (profiles.size() != 1) {
            throw new SimpleCommandExceptionType(ServerTranslations.text(
                    context.getSource(), "command.sable_quota.single_player_required")).create();
        }
        return profiles.iterator().next();
    }

    private static int sendQuota(GameProfile target, CommandSourceStack source) {
        QuotaService.QuotaStatus status = QuotaService.getStatus(source.getServer(), target);
        QuotaService.LimitInfo limitInfo = status.limitInfo();
        Component limitSource = ServerTranslations.text(source, switch (limitInfo.source()) {
            case PLAYER_OVERRIDE -> "command.sable_quota.source.player_override";
            case OPERATOR_DEFAULT -> "command.sable_quota.source.operator_default";
            case PLAYER_DEFAULT -> "command.sable_quota.source.player_default";
        });
        Component targetName = profileDisplayName(target);

        source.sendSuccess(() -> limitInfo.limit() < 0
                ? ServerTranslations.text(source, "command.sable_quota.get_unlimited",
                        targetName, status.owned(), limitSource)
                : ServerTranslations.text(source, "command.sable_quota.get",
                        targetName, status.owned(), limitInfo.limit(), limitSource), false);
        return status.owned();
    }

    private static int setQuota(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        GameProfile target = getSingleProfile(context);
        int amount = IntegerArgumentType.getInteger(context, "amount");
        MinecraftServer server = context.getSource().getServer();
        QuotaService.setPlayerLimit(server, target.getId(), amount);

        int owned = QuotaService.getOwnedCount(server, target.getId());
        Component targetName = profileDisplayName(target);
        context.getSource().sendSuccess(() -> amount < 0
                ? ServerTranslations.text(context.getSource(),
                        "command.sable_quota.set_unlimited", targetName, owned)
                : ServerTranslations.text(context.getSource(),
                        "command.sable_quota.set", targetName, amount, owned), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int resetQuota(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        GameProfile target = getSingleProfile(context);
        MinecraftServer server = context.getSource().getServer();
        boolean removed = QuotaService.resetPlayerLimit(server, target.getId());
        QuotaService.LimitInfo effectiveLimit = QuotaService.getLimitInfo(server, target);
        Component targetName = profileDisplayName(target);

        context.getSource().sendSuccess(() -> ServerTranslations.text(context.getSource(),
                removed ? "command.sable_quota.reset" : "command.sable_quota.reset_no_override",
                targetName, formatLimit(effectiveLimit.limit())), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int listStructures(GameProfile target, CommandSourceStack source, int page) {
        MinecraftServer server = source.getServer();
        List<UUID> structureIds = StructureLocationService.getOwnedStructureIds(server, target.getId());
        Component targetName = profileDisplayName(target);

        if (structureIds.isEmpty()) {
            source.sendSuccess(() -> ServerTranslations.text(source,
                    "command.sable_quota.list_empty", targetName), false);
            return 0;
        }

        int pageCount = (structureIds.size() + STRUCTURES_PER_PAGE - 1) / STRUCTURES_PER_PAGE;
        if (page > pageCount) {
            source.sendFailure(ServerTranslations.text(source,
                    "command.sable_quota.list_invalid_page", page, pageCount));
            return 0;
        }

        int fromIndex = (page - 1) * STRUCTURES_PER_PAGE;
        int toIndex = Math.min(fromIndex + STRUCTURES_PER_PAGE, structureIds.size());
        List<UUID> pageStructures = structureIds.subList(fromIndex, toIndex);
        source.sendSuccess(() -> ServerTranslations.text(source,
                "command.sable_quota.list_header", targetName, structureIds.size(), page, pageCount), false);
        for (UUID structureId : pageStructures) {
            var located = StructureLocationService.locate(server, structureId);
            if (located.isEmpty()) {
                source.sendSuccess(() -> ServerTranslations.text(source,
                        "command.sable_quota.list_unknown", structureId.toString()), false);
                continue;
            }
            sendStructureLocation(source, located.orElseThrow());
        }
        return pageStructures.size();
    }

    private static void sendStructureLocation(CommandSourceStack source,
                                              StructureLocationService.LocatedStructure structure) {
        String translationKey = structure.loaded()
                ? "command.sable_quota.list_loaded"
                : "command.sable_quota.list_unloaded";
        var blockPos = structure.blockPos();
        source.sendSuccess(() -> ServerTranslations.text(source, translationKey,
                structure.structureId().toString(), structure.dimension().toString(),
                blockPos.getX(), blockPos.getY(), blockPos.getZ()), false);
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        try {
            QuotaConfig.reloadFromDisk();
            context.getSource().sendSuccess(() -> ServerTranslations.text(context.getSource(),
                    "command.sable_quota.reload_success",
                    formatLimit(QuotaConfig.defaultPlayerLimit()),
                    formatLimit(QuotaConfig.defaultOperatorLimit())), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            SableQuota.LOGGER.warn("Failed to reload Sable Quota config", exception);
            context.getSource().sendFailure(ServerTranslations.text(context.getSource(),
                    "command.sable_quota.reload_failed", safeMessage(exception)));
            return 0;
        }
    }

    private static String formatLimit(int limit) {
        return limit < 0 ? "∞" : Integer.toString(limit);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static Component profileDisplayName(GameProfile profile) {
        String name = profile.getName();
        return Component.literal(name == null || name.isBlank() ? profile.getId().toString() : name);
    }
}
