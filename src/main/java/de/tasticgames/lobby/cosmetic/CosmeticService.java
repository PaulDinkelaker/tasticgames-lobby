package de.tasticgames.lobby.cosmetic;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.client.dto.lobby.CosmeticEquipRequest;
import de.tasticgames.client.dto.lobby.CosmeticOperationResponse;
import de.tasticgames.client.dto.lobby.CosmeticUnlockRequest;
import de.tasticgames.client.dto.lobby.OwnedCosmeticResponse;
import de.tasticgames.client.dto.lobby.PlayerCosmeticsResponse;
import de.tasticgames.lobby.api.LobbyApiService;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.service.Service;
import de.tasticgames.settings.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Cosmetic domain service: catalog, per-player ownership/equipped cache (API is the source of
 * truth), renderers, visibility/reduced-effects handling, prestige/achievement unlock gateway.
 */
public final class CosmeticService implements Service {

    public record PlayerCosmetics(Set<String> owned, Map<CosmeticCategory, String> equipped) {
        public boolean owns(CosmeticDefinition definition) {
            return definition.defaultOwned() || owned.contains(definition.id());
        }
    }

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyApiService api;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final de.tasticgames.lobby.integration.LobbyIntegrations integrations;
    private final Logger logger;
    private final List<CosmeticRenderer> renderers = new ArrayList<>();
    private final Map<UUID, PlayerCosmetics> cache = new ConcurrentHashMap<>();
    private volatile CosmeticCatalog catalog;
    private BukkitTask tickTask;

    public CosmeticService(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyApiService api,
                           LobbyTelemetryService telemetry, MainThread mainThread, de.tasticgames.lobby.integration.LobbyIntegrations integrations, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.api = Objects.requireNonNull(api);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.integrations = Objects.requireNonNull(integrations);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "cosmetic-service";
    }

    @Override
    public void start() {
        catalog = CosmeticCatalog.load(configurationService.raw("cosmetics"));
        renderers.clear();
        var hmc = integrations.hmcCosmetics();
        long hmcMapped = catalog.all().stream().filter(de.tasticgames.lobby.integration.cosmetic.HmcCosmeticsRenderer::handles).count();
        long unresolved = 0;
        if (hmc.available()) {
            renderers.add(hmc);
            unresolved = catalog.all().stream().filter(de.tasticgames.lobby.integration.cosmetic.HmcCosmeticsRenderer::handles)
                    .filter(d -> !hmc.cosmeticExists(de.tasticgames.lobby.integration.cosmetic.HmcCosmeticsRenderer.hmcId(d))).count();
        }
        renderers.add(new NativeCosmeticRenderer(plugin));
        logger.info("Cosmetic catalog loaded: " + catalog.size() + " cosmetics (native " + (catalog.size() - hmcMapped)
                + ", hmccosmetics " + hmcMapped + (hmc.available() ? "" : " – plugin missing, rendered natively") + ", unresolved " + unresolved
                + "); renderers: " + renderers.stream().map(CosmeticRenderer::id).toList()
                + (hmcMapped == 0 && hmc.available() ? " – no catalog entry uses render: hmc:<id>, all cosmetics are native by design" : ""));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    @Override
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderers.forEach(r -> r.clear(player));
        }
        cache.clear();
    }

    public CosmeticCatalog catalog() {
        return catalog;
    }

    public boolean available() {
        return api.enabled();
    }

    public List<CosmeticRenderer> renderers() {
        return List.copyOf(renderers);
    }

    public Optional<PlayerCosmetics> cached(UUID player) {
        return Optional.ofNullable(cache.get(player));
    }

    public CompletableFuture<PlayerCosmetics> load(UUID player) {
        if (!api.enabled()) {
            return CompletableFuture.failedFuture(new LobbyApiService.ApiUnavailableException());
        }
        return api.call("cosmetics.load", c -> c.lobby().cosmetics(player)).thenApply(response -> {
            PlayerCosmetics cosmetics = map(response);
            cache.put(player, cosmetics);
            return cosmetics;
        });
    }

    public CompletableFuture<CosmeticOperationResponse> equip(Player player, CosmeticDefinition definition) {
        return api.call("cosmetics.equip", c -> c.lobby().equipCosmetic(player.getUniqueId(),
                new CosmeticEquipRequest(definition.category().name(), definition.id()))).thenApply(response -> {
            cache.put(player.getUniqueId(), map(response.cosmetics()));
            telemetry.event("lobby.cosmetic_equip", player.getUniqueId(), Map.of("cosmetic", definition.id(), "outcome", response.outcome()));
            mainThread.run(() -> render(player));
            return response;
        });
    }

    public CompletableFuture<CosmeticOperationResponse> unequip(Player player, CosmeticCategory category) {
        return api.call("cosmetics.unequip", c -> c.lobby().equipCosmetic(player.getUniqueId(),
                new CosmeticEquipRequest(category.name(), null))).thenApply(response -> {
            cache.put(player.getUniqueId(), map(response.cosmetics()));
            mainThread.run(() -> render(player));
            return response;
        });
    }

    /** Unlock gateway used by admin tools; prestige rewards are unlocked server side by the API. */
    public CompletableFuture<CosmeticOperationResponse> unlock(UUID player, String cosmeticId, String source) {
        return api.call("cosmetics.unlock", c -> c.lobby().unlockCosmetic(player, new CosmeticUnlockRequest(cosmeticId, source, UUID.randomUUID())))
                .thenApply(response -> {
                    cache.put(player, map(response.cosmetics()));
                    return response;
                });
    }

    public CompletableFuture<CosmeticOperationResponse> revoke(UUID player, String cosmeticId) {
        return api.call("cosmetics.revoke", c -> c.lobby().revokeCosmetic(player, cosmeticId)).thenApply(response -> {
            cache.put(player, map(response.cosmetics()));
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                mainThread.run(() -> render(online));
            }
            return response;
        });
    }

    public Optional<CosmeticDefinition> equipped(UUID player, CosmeticCategory category) {
        return cached(player).map(c -> c.equipped().get(category)).flatMap(catalog::find);
    }

    /** Applies all equipped cosmetics of a player (main thread). */
    public void render(Player player) {
        PlayerCosmetics cosmetics = cache.get(player.getUniqueId());
        boolean visible = coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(CoreSettings.COSMETICS_VISIBLE)).orElse(true);
        boolean reduced = reducedEffects(player);
        for (CosmeticCategory category : CosmeticCategory.values()) {
            CosmeticDefinition definition = cosmetics == null || !visible ? null : catalog.find(cosmetics.equipped().get(category)).orElse(null);
            for (CosmeticRenderer renderer : renderers) {
                if (renderer.supports(category)) {
                    renderer.apply(player, category, definitionFor(renderer, definition), reduced);
                }
            }
        }
    }

    public void clear(Player player) {
        renderers.forEach(r -> r.clear(player));
        cache.remove(player.getUniqueId());
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerCosmetics cosmetics = cache.get(player.getUniqueId());
            if (cosmetics == null) continue;
            boolean visible = coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(CoreSettings.COSMETICS_VISIBLE)).orElse(true);
            if (!visible) continue;
            boolean reduced = reducedEffects(player);
            for (CosmeticCategory category : List.of(CosmeticCategory.AURA, CosmeticCategory.TRAIL)) {
                CosmeticDefinition definition = catalog.find(cosmetics.equipped().get(category)).orElse(null);
                if (definition == null) continue;
                for (CosmeticRenderer renderer : renderers) {
                    if (renderer.supports(category) && definitionFor(renderer, definition) != null) {
                        renderer.tick(player, definition, reduced);
                    }
                }
            }
        }
    }

    /** HMCCosmetics renders {@code hmc:} entries, the native renderer everything else; the other one clears its state. */
    private static CosmeticDefinition definitionFor(CosmeticRenderer renderer, CosmeticDefinition definition) {
        if (definition == null) {
            return null;
        }
        boolean hmcRenderer = renderer instanceof de.tasticgames.lobby.integration.cosmetic.HmcCosmeticsRenderer;
        return hmcRenderer == de.tasticgames.lobby.integration.cosmetic.HmcCosmeticsRenderer.handles(definition) ? definition : null;
    }

    private boolean reducedEffects(Player player) {
        return coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(CoreSettings.REDUCED_EFFECTS)).orElse(false);
    }

    private static PlayerCosmetics map(PlayerCosmeticsResponse response) {
        Set<String> owned = new HashSet<>();
        for (OwnedCosmeticResponse o : response.owned()) {
            owned.add(o.cosmeticId());
        }
        Map<CosmeticCategory, String> equipped = new EnumMap<>(CosmeticCategory.class);
        response.equipped().forEach((category, id) -> CosmeticCategory.find(category).ifPresent(c -> equipped.put(c, id)));
        return new PlayerCosmetics(Set.copyOf(owned), Map.copyOf(equipped));
    }
}
