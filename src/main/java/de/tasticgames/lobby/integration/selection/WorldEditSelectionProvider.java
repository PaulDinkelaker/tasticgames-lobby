package de.tasticgames.lobby.integration.selection;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * WorldEdit / FastAsyncWorldEdit selections for admin region tools (launchpads, teleport pads,
 * cookie zones, golden areas). Non-cuboid selections are reduced to their bounding box.
 */
public final class WorldEditSelectionProvider implements SelectionProvider {

    private final Plugin plugin;
    private final Logger logger;
    private volatile boolean available;
    private volatile String pluginName = "WorldEdit";
    private volatile boolean warned;

    public WorldEditSelectionProvider(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (Integration.pluginEnabled("FastAsyncWorldEdit")) {
            pluginName = "FastAsyncWorldEdit";
        } else if (Integration.pluginEnabled("WorldEdit")) {
            pluginName = "WorldEdit";
        } else {
            return;
        }
        try {
            available = WorldEdit.getInstance() != null && WorldEdit.getInstance().getSessionManager() != null;
        } catch (Throwable t) {
            available = false;
            logger.warning(pluginName + " hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – selection based admin tools disabled.");
        }
    }

    public void unhook() {
        available = false;
    }

    @Override
    public String pluginName() {
        return pluginName;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public Optional<LobbyConfiguration.Region> selection(Player player) {
        if (!available || player == null) return Optional.empty();
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return Optional.of(new LobbyConfiguration.Region(player.getWorld().getName(),
                    min.x(), min.y(), min.z(), max.x(), max.y(), max.z()));
        } catch (IncompleteRegionException e) {
            return Optional.empty();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                logger.warning(pluginName + " selection lookup failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
            }
            return Optional.empty();
        }
    }
}
