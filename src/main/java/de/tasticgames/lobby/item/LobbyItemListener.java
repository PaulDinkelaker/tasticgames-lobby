package de.tasticgames.lobby.item;

import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.world.WorldEnvironmentService;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Lobby item interactions (right/left click) and protection (drop, move, swap, pickup, death).
 */
public final class LobbyItemListener implements Listener {

    private final LobbyItemService items;
    private final LobbyPlayerService players;
    private final WorldEnvironmentService environment;
    private final LobbyConfigurationService configurationService;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final BiConsumer<Player, LobbyItemType> handler;

    public LobbyItemListener(LobbyItemService items, LobbyPlayerService players, WorldEnvironmentService environment,
                             LobbyConfigurationService configurationService, LobbySounds sounds, LobbyTelemetryService telemetry,
                             BiConsumer<Player, LobbyItemType> handler) {
        this.items = Objects.requireNonNull(items);
        this.players = Objects.requireNonNull(players);
        this.environment = Objects.requireNonNull(environment);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.handler = Objects.requireNonNull(handler);
    }

    private boolean protectedPlayer(Player player) {
        LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
        return lobbyPlayer == null || !lobbyPlayer.buildMode();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        ItemStack stack = event.getItem();
        LobbyItemType type = items.typeOf(stack).orElse(null);
        if (type == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        if (!lobbyPlayer.tryItemUse(configurationService.configuration().items().interactionCooldownMillis())) {
            return;
        }
        sounds.click(player);
        telemetry.event("lobby.item_use", player.getUniqueId(), Map.of("item", type.key()));
        handler.accept(player, type);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (items.isLobbyItem(event.getItemDrop().getItemStack()) && protectedPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !protectedPlayer(player)) {
            return;
        }
        boolean lobbyItem = items.isLobbyItem(event.getCurrentItem()) || items.isLobbyItem(event.getCursor())
                || (event.getHotbarButton() >= 0 && items.isLobbyItem(player.getInventory().getItem(event.getHotbarButton())));
        if (lobbyItem) {
            event.setCancelled(true);
            return;
        }
        // normal players may not rearrange their inventory in managed worlds at all (adventure hub)
        if (environment.isManaged(player.getWorld()) && player.getGameMode() != GameMode.CREATIVE
                && event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !protectedPlayer(player)) {
            return;
        }
        if (items.isLobbyItem(event.getOldCursor()) || event.getNewItems().values().stream().anyMatch(items::isLobbyItem)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (protectedPlayer(event.getPlayer()) && (items.isLobbyItem(event.getMainHandItem()) || items.isLobbyItem(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && environment.isManaged(player.getWorld()) && protectedPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (environment.isManaged(event.getEntity().getWorld())) {
            event.getDrops().removeIf(items::isLobbyItem);
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        // no-op hook kept for future "hold to preview" – intentionally empty
    }
}
