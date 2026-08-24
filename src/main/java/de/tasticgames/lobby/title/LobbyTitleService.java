package de.tasticgames.lobby.title;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.client.dto.network.NetworkTitleCatalogEntry;
import de.tasticgames.client.dto.network.NetworkTitleCatalogRequest;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.cosmetic.CosmeticCatalog;
import de.tasticgames.lobby.cosmetic.CosmeticCategory;
import de.tasticgames.lobby.cosmetic.CosmeticDefinition;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.service.Service;
import de.tasticgames.title.NetworkTitle;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The lobby owns the title catalog: the titles live in {@code cosmetics.yml} and their texts in the
 * lobby message files. Nobody else has that catalog, so the lobby pushes it into the API on every
 * start - from there the proxy renders the chat title and every backend the line under the name.
 * <p>
 * When a player equips or removes a title, TasticCore is told right away so the line above their head
 * changes without a reconnect. The proxies are not involved: the chat line shows the clan tag and the
 * rank colour, not the title.
 */
public final class LobbyTitleService implements Service {

    private final TasticCoreApi coreApi;
    private final LobbyApiService api;
    private final LobbyMessages messages;
    private final Supplier<CosmeticCatalog> catalog;
    private final Logger logger;

    public LobbyTitleService(TasticCoreApi coreApi, LobbyApiService api, LobbyMessages messages,
                             Supplier<CosmeticCatalog> catalog, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.api = Objects.requireNonNull(api);
        this.messages = Objects.requireNonNull(messages);
        this.catalog = Objects.requireNonNull(catalog);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-title-service";
    }

    @Override
    public void start() {
        pushCatalog();
    }

    @Override
    public void stop() {
    }

    /**
     * Sends the whole title catalog to the API. Repeating an unchanged catalog changes nothing, so
     * this runs on every start without a guard.
     */
    public CompletableFuture<Void> pushCatalog() {
        if (!api.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        List<NetworkTitleCatalogEntry> entries = new ArrayList<>();
        for (CosmeticDefinition definition : catalog.get().all()) {
            if (definition.category() == CosmeticCategory.TITLE && definition.enabled()) {
                entries.add(new NetworkTitleCatalogEntry(definition.id(), texts(definition)));
            }
        }
        return api.call("titles.catalog", c -> c.network().replaceTitleCatalog(new NetworkTitleCatalogRequest(entries)))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        logger.warning("Title catalog could not be published: " + LobbyThrowables.rootMessage(throwable)
                                + " - titles stay empty outside the lobby until the next start.");
                        return null;
                    }
                    logger.info("Title catalog published: " + entries.size() + " titles (" + response.outcome() + ").");
                    return null;
                });
    }

    /**
     * Announces the title a player wears now; {@code definition} is {@code null} when the title was
     * taken off.
     */
    public void announce(Player player, CosmeticDefinition definition) {
        Objects.requireNonNull(player, "player");
        NetworkTitle title = definition == null
                ? NetworkTitle.none()
                : new NetworkTitle(definition.id(), texts(definition));

        coreApi.playerTitleService().apply(player.getUniqueId(), title);
    }

    /** The texts of one title, one entry per language the lobby speaks. */
    private Map<String, String> texts(CosmeticDefinition definition) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (SupportedLanguage language : SupportedLanguage.values()) {
            String text = messages.raw(language, definition.nameKey());
            if (!text.isBlank() && !text.startsWith("<red>[")) {
                texts.put(language.code(), text);
            }
        }
        return texts;
    }

}
