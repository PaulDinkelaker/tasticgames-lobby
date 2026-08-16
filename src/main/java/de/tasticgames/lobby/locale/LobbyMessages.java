package de.tasticgames.lobby.locale;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.service.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Lobby-specific localization (EN/DE/HI, English fallback) on top of TasticCore's player language.
 * Bundles: {@code messages/lobby_<lang>.properties} (MiniMessage, placeholders {@code <name>}).
 */
public final class LobbyMessages implements Service {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<SupportedLanguage, Properties> bundles = new EnumMap<>(SupportedLanguage.class);

    public LobbyMessages(Plugin plugin, TasticCoreApi coreApi, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-messages";
    }

    @Override
    public void start() throws IOException {
        for (SupportedLanguage language : SupportedLanguage.values()) {
            bundles.put(language, load(language));
        }
        Properties english = bundles.get(SupportedLanguage.ENGLISH);
        List<String> missing = new ArrayList<>();
        for (SupportedLanguage language : SupportedLanguage.values()) {
            if (language == SupportedLanguage.ENGLISH) continue;
            for (String key : english.stringPropertyNames()) {
                if (!bundles.get(language).containsKey(key)) {
                    missing.add(language.code() + ":" + key);
                }
            }
        }
        if (!missing.isEmpty()) {
            logger.warning("Lobby message bundles are missing " + missing.size() + " translation(s), English fallback is used: "
                    + (missing.size() > 10 ? missing.subList(0, 10) + "..." : missing));
        }
        logger.info("Loaded lobby messages: " + english.size() + " keys, " + bundles.size() + " languages.");
    }

    @Override
    public void stop() {
        bundles.clear();
    }

    public SupportedLanguage languageOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return coreApi.playerManager().find(player.getUniqueId())
                    .map(coreApi.localizationService()::languageOf)
                    .orElseGet(() -> SupportedLanguage.find(player.locale().getLanguage()).orElse(SupportedLanguage.ENGLISH));
        }
        return SupportedLanguage.ENGLISH;
    }

    public Component get(SupportedLanguage language, String key, Map<String, ?> placeholders) {
        TagResolver.Builder resolvers = TagResolver.builder();
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Component component) {
                resolvers.resolver(Placeholder.component(entry.getKey(), component));
            } else {
                resolvers.resolver(Placeholder.unparsed(entry.getKey(), String.valueOf(value)));
            }
        }
        return miniMessage.deserialize(raw(language, key), resolvers.build());
    }

    public Component get(CommandSender sender, String key) {
        return get(languageOf(sender), key, Map.of());
    }

    public Component get(CommandSender sender, String key, Map<String, ?> placeholders) {
        return get(languageOf(sender), key, placeholders);
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(get(sender, key));
    }

    public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        sender.sendMessage(get(sender, key, placeholders));
    }

    public String raw(SupportedLanguage language, String key) {
        Properties bundle = bundles.get(language);
        String value = bundle == null ? null : bundle.getProperty(key);
        if (value == null) {
            value = bundles.getOrDefault(SupportedLanguage.ENGLISH, new Properties()).getProperty(key);
        }
        if (value == null) {
            logger.warning("Missing lobby message key: " + key);
            return "<red>[" + key + "]";
        }
        return value;
    }

    public boolean contains(String key) {
        Properties english = bundles.get(SupportedLanguage.ENGLISH);
        return english != null && english.containsKey(key);
    }

    public Component mini(String miniMessageText) {
        return miniMessage.deserialize(miniMessageText);
    }

    private Properties load(SupportedLanguage language) throws IOException {
        String resource = "messages/lobby_" + language.code() + ".properties";
        Properties properties = new Properties();
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                if (language == SupportedLanguage.ENGLISH) {
                    throw new IOException("Missing required message bundle " + resource);
                }
                logger.warning("Message bundle " + resource + " not found – English fallback.");
                return properties;
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
