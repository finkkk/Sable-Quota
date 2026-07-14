package com.finkkk.sable_quota;

import com.finkkk.sable_quota.command.SableQuotaCommands;
import com.finkkk.sable_quota.localization.ServerTranslations;
import com.finkkk.sable_quota.quota.QuotaCreationContext;
import com.finkkk.sable_quota.quota.QuotaLifecycleObserver;
import com.finkkk.sable_quota.quota.QuotaService;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Locale;
import java.util.Set;

@Mod(SableQuota.MODID)
public class SableQuota {

    public static final String MODID = "sable_quota";
    public static final String CREATION_BLOCKED_MESSAGE = "message.sable_quota.creation_blocked";
    public static final String COMMAND_CREATION_BLOCKED_MESSAGE = "message.sable_quota.command_creation_blocked";
    private static final ResourceLocation SIMULATED_SWIVEL_BEARING =
            ResourceLocation.fromNamespaceAndPath("simulated", "swivel_bearing");
    private static final Set<String> SINGLE_STRUCTURE_ASSEMBLE_COMMANDS =
            Set.of("area", "connected", "sphere", "cube");
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableQuota(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, QuotaConfig.SPEC);
        modEventBus.addListener(QuotaConfig::onConfigLoading);
        modEventBus.addListener(QuotaConfig::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(SableQuota::onCommand);
        NeoForge.EVENT_BUS.addListener(SableQuotaCommands::register);
        NeoForge.EVENT_BUS.addListener(SableQuota::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(SableQuota::onServerTick);
        NeoForge.EVENT_BUS.addListener(SableQuota::onServerStopped);
        SableEventPlatform.INSTANCE.onSubLevelContainerReady(SableQuota::onSubLevelContainerReady);
        LOGGER.info("Sable Quota loaded with default limits: players={}, operators={}.",
                QuotaConfig.DEFAULT_PLAYER_LIMIT.getDefault(),
                QuotaConfig.DEFAULT_OPERATOR_LIMIT.getDefault());
    }

    /**
     * Attributes Sable's single-structure assembly commands to their player.
     * Multi-structure debug commands remain blocked because one invocation can
     * allocate an unbounded number of sub-levels.
     */
    private static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        String command = event.getParseResults().getReader().getString().trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }

        String[] arguments = command.toLowerCase(Locale.ROOT).split("\\s+");
        if (arguments.length < 2 || !arguments[0].equals("sable")) {
            return;
        }

        boolean createsMultipleStructures = arguments[1].equals("spawn")
                || (arguments[1].equals("assemble")
                && arguments.length >= 3
                && arguments[2].equals("shatter"));
        if (createsMultipleStructures) {
            event.setCanceled(true);
            player.sendSystemMessage(ServerTranslations.text(player, COMMAND_CREATION_BLOCKED_MESSAGE)
                    .withStyle(ChatFormatting.RED));
            return;
        }

        if (arguments[1].equals("assemble")
                && arguments.length >= 3
                && SINGLE_STRUCTURE_ASSEMBLE_COMMANDS.contains(arguments[2])
                && !QuotaService.tryBeginCreation(player)) {
            event.setCanceled(true);
            sendCreationBlockedMessage(player);
        }
    }

    /** Checks and attributes the delayed empty-hand swivel-bearing assembly. */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getItemStack().isEmpty()) {
            return;
        }

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(SIMULATED_SWIVEL_BEARING)
                || getBooleanProperty(state, "assembled")) {
            return;
        }

        if (!QuotaService.hasCapacity(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            sendCreationBlockedMessage(player);
            return;
        }

        QuotaCreationContext.rememberSwivelBearing(player.serverLevel(), event.getPos(), player.getUUID());
    }

    private static boolean getBooleanProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name) && state.getValue(property) instanceof Boolean value) {
                return value;
            }
        }
        return false;
    }

    public static void sendCreationBlockedMessage(ServerPlayer player) {
        QuotaService.QuotaStatus status = QuotaService.getStatus(player);
        int owned = status.owned();
        int limit = status.limitInfo().limit();
        String configuredMessage = QuotaConfig.creationBlockedMessage();
        Component message = configuredMessage.isBlank()
                ? ServerTranslations.text(player, CREATION_BLOCKED_MESSAGE, owned, limit)
                : Component.literal(configuredMessage
                        .replace("{owned}", Integer.toString(owned))
                        .replace("{limit}", Integer.toString(limit)));
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
    }

    private static void onSubLevelContainerReady(net.minecraft.world.level.Level level, SubLevelContainer container) {
        if (level instanceof ServerLevel serverLevel && container instanceof ServerSubLevelContainer) {
            container.addObserver(new QuotaLifecycleObserver(serverLevel));
        }
    }

    /** Clears a command context when the command completed without allocating a structure. */
    private static void onServerTick(ServerTickEvent.Post event) {
        QuotaCreationContext.end();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        QuotaCreationContext.clearAll();
    }
}
