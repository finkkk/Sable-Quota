package com.finkkk.sable_quota.mixin;

import com.finkkk.sable_quota.SableQuota;
import com.finkkk.sable_quota.localization.QuotaFeedback;
import com.finkkk.sable_quota.quota.QuotaCreationContext;
import com.finkkk.sable_quota.quota.QuotaService;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在 Simulated 装配请求进入服务端处理时建立玩家归属上下文。 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.network.packets.AssemblePacket", remap = false)
public abstract class SimulatedAssemblePacketMixin {

    /** 反射只解析一次，避免每次点击装配器都进行方法查找。 */
    @Unique
    private static final ClassValue<Optional<Method>> PLAYER_ACCESSORS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("player"));
            } catch (NoSuchMethodException exception) {
                SableQuota.LOGGER.warn("Simulated packet context {} has no player accessor", type.getName());
                return Optional.empty();
            }
        }
    };
    @Unique
    private static final AtomicBoolean INVOCATION_FAILURE_LOGGED = new AtomicBoolean();

    @Unique
    private boolean sableQuota$creationContextStarted;

    @Shadow(remap = false)
    public abstract BlockPos pos();

    @Dynamic("目标方法来自可选的 Simulated 模组")
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableQuota$notifyPlayer(@Coerce Object context, CallbackInfo callback) {
        ServerPlayer player = getPlayer(context);
        if (player == null || Sable.HELPER.getContaining(player.level(), pos()) != null) {
            return;
        }

        if (!QuotaService.tryBeginCreation(player)) {
            QuotaFeedback.sendCreationBlocked(player);
            callback.cancel();
            return;
        }

        sableQuota$creationContextStarted = true;
    }

    @Dynamic("目标方法来自可选的 Simulated 模组")
    @Inject(method = "handle", at = @At("RETURN"), remap = false)
    private void sableQuota$clearCreationContext(@Coerce Object context, CallbackInfo callback) {
        if (sableQuota$creationContextStarted) {
            QuotaCreationContext.end();
            sableQuota$creationContextStarted = false;
        }
    }

    @Unique
    private static ServerPlayer getPlayer(Object context) {
        Optional<Method> accessor = PLAYER_ACCESSORS.get(context.getClass());
        if (accessor.isEmpty()) {
            return null;
        }
        try {
            Object player = accessor.orElseThrow().invoke(context);
            return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            if (INVOCATION_FAILURE_LOGGED.compareAndSet(false, true)) {
                SableQuota.LOGGER.warn("Unable to identify the player making a Simulated assembly request", exception);
            }
            return null;
        }
    }
}
