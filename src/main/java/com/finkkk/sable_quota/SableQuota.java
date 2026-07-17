package com.finkkk.sable_quota;

import com.finkkk.sable_quota.command.SableQuotaCommands;
import com.finkkk.sable_quota.localization.QuotaFeedback;
import com.finkkk.sable_quota.quota.QuotaCreationContext;
import com.finkkk.sable_quota.quota.QuotaLifecycleObserver;
import com.finkkk.sable_quota.quota.QuotaSavedData;
import com.finkkk.sable_quota.quota.QuotaService;
import com.finkkk.sable_quota.quota.StructureSplitGuard;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 模组入口：注册配额事件、指令以及 Sable 生命周期观察器。 */
@Mod(SableQuota.MODID)
public final class SableQuota {

    public static final String MODID = "sable_quota";
    private static final String SABLE_COMMAND = "sable";
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
        NeoForge.EVENT_BUS.addListener(SableQuota::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(SableQuota::onServerTick);
        NeoForge.EVENT_BUS.addListener(SableQuota::onServerStopped);
        SableEventPlatform.INSTANCE.onSubLevelContainerReady(SableQuota::onSubLevelContainerReady);
        LOGGER.info("Sable Quota loaded with default limits: players={}, operators={}.",
                QuotaConfig.DEFAULT_PLAYER_LIMIT.getDefault(),
                QuotaConfig.DEFAULT_OPERATOR_LIMIT.getDefault());
    }

    /**
     * 将 Sable 的单结构装配命令归属到执行玩家。
     * 一次可生成任意数量结构的调试命令无法安全预留额度，因此直接禁止玩家执行。
     */
    private static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        String command = event.getParseResults().getReader().getString().trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }

        if (!command.regionMatches(true, 0, SABLE_COMMAND, 0, SABLE_COMMAND.length())
                || (command.length() > SABLE_COMMAND.length()
                && !Character.isWhitespace(command.charAt(SABLE_COMMAND.length())))) {
            return;
        }

        String[] arguments = command.toLowerCase(Locale.ROOT).split("\\s+", 4);
        if (arguments.length < 2 || !arguments[0].equals(SABLE_COMMAND)) {
            return;
        }

        boolean createsMultipleStructures = arguments[1].equals("spawn")
                || (arguments[1].equals("assemble")
                && arguments.length >= 3
                && arguments[2].equals("shatter"));
        if (createsMultipleStructures) {
            event.setCanceled(true);
            QuotaFeedback.sendCommandCreationBlocked(player);
            return;
        }

        if (arguments[1].equals("assemble")
                && arguments.length >= 3
                && SINGLE_STRUCTURE_ASSEMBLE_COMMANDS.contains(arguments[2])
                && !QuotaService.tryBeginCreation(player)) {
            event.setCanceled(true);
            QuotaFeedback.sendCreationBlocked(player);
        }
    }

    /** 检查空手点击旋转轴承触发的延迟装配，并暂存发起玩家。 */
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
            QuotaFeedback.sendCreationBlocked(player);
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

    /** 在方块真正被破坏前，拦截会使结构断裂并超过剩余额度的操作。 */
    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        SubLevel containing = Sable.HELPER.getContaining(level, event.getPos());
        if (!(containing instanceof ServerSubLevel structure)) {
            return;
        }

        QuotaSavedData data = QuotaSavedData.get(level.getServer());
        UUID ownerId = data.getStructureOwner(structure.getUniqueId());
        if (ownerId == null) {
            return;
        }

        int limit = QuotaService.getLimit(level.getServer(), ownerId);
        if (limit < 0) {
            return;
        }

        int owned = data.countOwnedBy(ownerId);
        int availableSlots = Math.max(0, limit - owned);
        if (!StructureSplitGuard.wouldExceedAvailableSlots(
                level, structure, event.getPos(), availableSlots)) {
            return;
        }

        event.setCanceled(true);
        QuotaFeedback.sendSplitBlocked(player, owned, limit);
    }

    private static void onSubLevelContainerReady(Level level, SubLevelContainer container) {
        if (level instanceof ServerLevel serverLevel && container instanceof ServerSubLevelContainer) {
            container.addObserver(new QuotaLifecycleObserver(serverLevel));
        }
    }

    /** 清理未被结构分配消费的命令上下文，避免跨 Tick 误归属。 */
    private static void onServerTick(ServerTickEvent.Post event) {
        QuotaCreationContext.end();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        QuotaCreationContext.clearAll();
    }
}
