package de.tasticgames.lobby.integration.selection;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Region selection backend (WorldEdit / FastAsyncWorldEdit). Used by admin commands to define
 * launchpad/teleport regions, cookie zones and golden-cookie areas from the player's selection.
 */
public interface SelectionProvider extends Integration {

    /** The player's current cuboid selection (bounding box for non-cuboid shapes); empty when incomplete. */
    Optional<LobbyConfiguration.Region> selection(Player player);
}
