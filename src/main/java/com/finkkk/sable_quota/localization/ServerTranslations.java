package com.finkkk.sable_quota.localization;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

/**
 * 在服务端解析本模组的语言文本。
 *
 * <p>如果只在服务器安装模组，客户端拿不到模组语言包。此时发送
 * {@link Component#translatable(String, Object...)} 会直接显示翻译键，所以这里根据玩家
 * 上报的语言生成普通文本，确保原版客户端也能正常阅读。</p>
 */
public final class ServerTranslations {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, String> EN_US = load("en_us");
    private static final Map<String, String> ZH_CN = load("zh_cn");

    private ServerTranslations() {
    }

    public static MutableComponent text(ServerPlayer player, String key, Object... arguments) {
        return textForLanguage(player.getLanguage(), key, arguments);
    }

    public static MutableComponent text(CommandSourceStack source, String key, Object... arguments) {
        String language = source.getEntity() instanceof ServerPlayer player
                ? player.getLanguage()
                : "en_us";
        return textForLanguage(language, key, arguments);
    }

    private static MutableComponent textForLanguage(String language, String key, Object... arguments) {
        Map<String, String> translations = isChinese(language) ? ZH_CN : EN_US;
        String pattern = translations.get(key);
        if (pattern == null) {
            // 语言资源损坏也不能影响命令执行，更不能把内部翻译键直接丢给玩家。
            LOGGER.warn("Missing server-side translation: {}", key);
            return Component.literal("Sable Quota: " + joinArguments(arguments));
        }

        Object[] plainArguments = new Object[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            plainArguments[index] = argument instanceof Component component
                    ? component.getString()
                    : argument;
        }

        try {
            return Component.literal(String.format(Locale.ROOT, pattern, plainArguments));
        } catch (IllegalFormatException exception) {
            LOGGER.error("Invalid server-side translation format for key {}", key, exception);
            return Component.literal(pattern + " " + joinArguments(plainArguments));
        }
    }

    private static boolean isChinese(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("zh_");
    }

    private static String joinArguments(Object[] arguments) {
        if (arguments.length == 0) {
            return "message unavailable";
        }
        StringBuilder result = new StringBuilder();
        for (Object argument : arguments) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(argument instanceof Component component ? component.getString() : argument);
        }
        return result.toString();
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/sable_quota/lang/" + language + ".json";
        try (InputStream stream = ServerTranslations.class.getResourceAsStream(path)) {
            if (stream == null) {
                LOGGER.error("Missing bundled language resource: {}", path);
                return Map.of();
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> translations = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isString()) {
                        translations.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                return Map.copyOf(translations);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to load bundled language resource: {}", path, exception);
            return Map.of();
        }
    }
}
