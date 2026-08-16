package de.tasticgames.lobby.player;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.visibility.PlayerVisibilityService;
import de.tasticgames.lobby.world.LobbySpawnService;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.service.Service;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Idempotent lobby player initialization pipeline (see docs/tasticlobby-architecture.md).
 */
public final class LobbyPlayerInitializationService implements Service {

    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyPlayerService players;
    private final LobbySpawnService spawn;
    private final LobbyItemService items;
    private final PlayerVisibilityService visibility;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final List<Consumer<Player>> postInitHooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    public LobbyPlayerInitializationService(TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyPlayerService players,
                                            LobbySpawnService spawn, LobbyItemService items, PlayerVisibilityService visibility,
                                            LobbyTelemetryService telemetry, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.players = Objects.requireNonNull(players);
        this.spawn = Objects.requireNonNull(spawn);
        this.items = Objects.requireNonNull(items);
        this.visibility = Objects.requireNonNull(visibility);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-player-initialization-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        postInitHooks.clear();
    }

    /** Hooks executed after the core state is applied (HUD, music, cosmetics, cookie preload, social...). */
    public void addPostInitHook(Consumer<Player> hook) {
        postInitHooks.add(hook);
    }

    /** Runs on the main thread. Safe to call more than once. */
    public void initialize(Player player, TasticPlayer tasticPlayer) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(tasticPlayer);
        if (!player.isOnline()) {
            return;
        }
        if (!player.getUniqueId().equals(tasticPlayer.minecraftUuid())) {
            throw new IllegalArgumentException("Player UUID mismatch.");
        }
        if (!tasticPlayer.ready()) {
            throw new IllegalStateException("TasticPlayer is not ready.");
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        boolean first = lobbyPlayer.markInitialized();
        applyLobbyState(player, lobbyPlayer, first);
        for (Consumer<Player> hook : postInitHooks) {
            try {
                hook.accept(player);
            } catch (RuntimeException e) {
                logger.warning("Post-init hook failed for " + player.getName() + ": " + e.getMessage());
            }
        }
        if (first) {
            telemetry.event("lobby.ready", player.getUniqueId(), Map.of("language", tasticPlayer.language()));
            logger.info("Initialized lobby player " + tasticPlayer.username() + " [" + player.getUniqueId() + "]");
        }
    }

    /** Normalizes the player state for the lobby (also used when leaving build mode / cookie world). */
    public void applyLobbyState(Player player, LobbyPlayer lobbyPlayer, boolean teleportToSpawn) {
        if (lobbyPlayer.buildMode()) {
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(Math.min(player.getHealth(), player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setExp(0f);
        player.setLevel(0);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        boolean staffFlight = configurationService.configuration().world().allowStaffFlight() && player.hasPermission("tasticlobby.fly");
        player.setAllowFlight(staffFlight);
        if (!staffFlight) {
            player.setFlying(false);
        }
        player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        items.giveItems(player, lobbyPlayer);
        visibility.apply(player);
        if (teleportToSpawn && spawn.isLobbyWorld(player.getWorld()) || teleportToSpawn && !spawn.isLobbyWorld(player.getWorld())) {
            spawn.teleportToSpawn(player);
        }
    }
}
