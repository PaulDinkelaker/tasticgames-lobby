package de.tasticgames.lobby.world;

import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;

import java.util.Objects;

/**
 * Prevents griefing / survival mechanics for normal players in managed worlds. Build mode bypasses.
 */
public final class WorldProtectionListener implements Listener {

    private final WorldEnvironmentService environment;
    private final LobbyPlayerService players;

    public WorldProtectionListener(WorldEnvironmentService environment, LobbyPlayerService players) {
        this.environment = Objects.requireNonNull(environment);
        this.players = Objects.requireNonNull(players);
    }

    private boolean protectedPlayer(Player player) {
        if (!environment.isManaged(player.getWorld())) {
            return false;
        }
        LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
        return lobbyPlayer == null || !lobbyPlayer.buildMode();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && protectedPlayer(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if ((event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof Painting)
                && protectedPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && protectedPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!environment.isManaged(player.getWorld())) {
            return;
        }
        // players never take damage in managed worlds (void is handled by VoidRescueListener)
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && environment.isManaged(player.getWorld())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && protectedPlayer(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (environment.isManaged(event.getEntity().getWorld())) event.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (environment.isManaged(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (environment.isManaged(event.getBlock().getWorld()) && (event.getPlayer() == null || protectedPlayer(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (environment.isManaged(event.getLocation().getWorld())
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.COMMAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!protectedPlayer(event.getPlayer()) || event.getClickedBlock() == null) {
            return;
        }
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        switch (event.getClickedBlock().getType()) {
            case FARMLAND -> { if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) event.setCancelled(true); }
            case CHEST, TRAPPED_CHEST, BARREL, FURNACE, BLAST_FURNACE, SMOKER, HOPPER, DISPENSER, DROPPER, SHULKER_BOX,
                 BREWING_STAND, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, ENCHANTING_TABLE, CRAFTING_TABLE, GRINDSTONE, LOOM,
                 STONECUTTER, CARTOGRAPHY_TABLE, SMITHING_TABLE, RESPAWN_ANCHOR, BEACON, LEVER, REPEATER, COMPARATOR,
                 DAYLIGHT_DETECTOR, JUKEBOX, NOTE_BLOCK, CAKE, FLOWER_POT, COMPOSTER, BELL ->
                    event.setCancelled(true);
            default -> {
                if (org.bukkit.Tag.BEDS.isTagged(event.getClickedBlock().getType())
                        || org.bukkit.Tag.DOORS.isTagged(event.getClickedBlock().getType())
                        || org.bukkit.Tag.TRAPDOORS.isTagged(event.getClickedBlock().getType())
                        || org.bukkit.Tag.FENCE_GATES.isTagged(event.getClickedBlock().getType())
                        || org.bukkit.Tag.BUTTONS.isTagged(event.getClickedBlock().getType())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (environment.isManaged(event.getWorld()) && event.toWeatherState()) event.setCancelled(true);
    }
}
