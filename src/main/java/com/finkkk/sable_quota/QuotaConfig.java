package com.finkkk.sable_quota;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;

public final class QuotaConfig {

    private static final int DEFAULT_PLAYER_LIMIT_VALUE = 3;
    private static final int DEFAULT_OPERATOR_LIMIT_VALUE = -1;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DEFAULT_PLAYER_LIMIT;
    public static final ModConfigSpec.IntValue DEFAULT_OPERATOR_LIMIT;
    public static final ModConfigSpec.ConfigValue<String> CREATION_BLOCKED_MESSAGE;

    // 一次替换完整快照，读取无需加锁，重载失败也不会留下半更新状态。
    private static volatile RuntimeSettings runtimeSettings = new RuntimeSettings(
            DEFAULT_PLAYER_LIMIT_VALUE, DEFAULT_OPERATOR_LIMIT_VALUE, "");
    // 配置监听器和命令线程都可能访问它，volatile 保证热重载时可见。
    private static volatile ModConfig commonConfig;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("quota");
        DEFAULT_PLAYER_LIMIT = builder
                .comment("Default maximum structures for regular players. -1 means unlimited.")
                .defineInRange("maxStructuresPerPlayer", DEFAULT_PLAYER_LIMIT_VALUE, -1, Integer.MAX_VALUE);
        DEFAULT_OPERATOR_LIMIT = builder
                .comment("Default maximum structures for server operators. -1 means unlimited.")
                .defineInRange("maxStructuresPerOperator", DEFAULT_OPERATOR_LIMIT_VALUE, -1, Integer.MAX_VALUE);
        builder.pop();

        builder.push("messages");
        CREATION_BLOCKED_MESSAGE = builder
                .comment(
                        "Custom quota-denied message. Supports {owned} and {limit} placeholders.",
                        "Leave empty to use the localized message from the player's language."
                )
                .define("creationBlocked", "");
        builder.pop();
        SPEC = builder.build();
    }

    private QuotaConfig() {
    }

    public static void onConfigLoading(ModConfigEvent.Loading event) {
        applySpecValues(event.getConfig());
    }

    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        applySpecValues(event.getConfig());
    }

    private static void applySpecValues(ModConfig config) {
        if (config.getSpec() == SPEC) {
            commonConfig = config;
            runtimeSettings = new RuntimeSettings(
                    DEFAULT_PLAYER_LIMIT.get(),
                    DEFAULT_OPERATOR_LIMIT.get(),
                    CREATION_BLOCKED_MESSAGE.get());
        }
    }

    public static void reloadFromDisk() throws IOException {
        ModConfig config = commonConfig;
        if (config == null) {
            throw new IllegalStateException("Sable Quota config is not registered");
        }

        CommentedConfig parsed = CommentedConfig.inMemory();
        try (Reader reader = Files.newBufferedReader(config.getFullPath())) {
            new TomlParser().parse(reader, parsed, ParsingMode.REPLACE);
        }

        int playerLimit = readLimit(parsed, "quota.maxStructuresPerPlayer", DEFAULT_PLAYER_LIMIT_VALUE);
        int operatorLimit = readLimit(parsed, "quota.maxStructuresPerOperator", DEFAULT_OPERATOR_LIMIT_VALUE);
        Object messageValue = parsed.get("messages.creationBlocked");
        if (messageValue != null && !(messageValue instanceof String)) {
            throw new IllegalArgumentException("messages.creationBlocked must be a string");
        }
        String blockedMessage = messageValue instanceof String message ? message : "";
        runtimeSettings = new RuntimeSettings(playerLimit, operatorLimit, blockedMessage);
    }

    private static int readLimit(CommentedConfig config, String path, int defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }

        long limit = number.longValue();
        if (number.doubleValue() != limit || limit < -1 || limit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " must be -1 or greater");
        }
        return (int) limit;
    }

    public static int defaultPlayerLimit() {
        return runtimeSettings.playerLimit();
    }

    public static int defaultOperatorLimit() {
        return runtimeSettings.operatorLimit();
    }

    public static String creationBlockedMessage() {
        return runtimeSettings.creationBlockedMessage();
    }

    private record RuntimeSettings(int playerLimit, int operatorLimit, String creationBlockedMessage) {
    }
}
