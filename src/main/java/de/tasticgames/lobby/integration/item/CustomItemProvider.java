package de.tasticgames.lobby.integration.item;

import de.tasticgames.lobby.integration.Integration;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Custom item backend (ItemsAdder). Lobby items may reference {@code namespace:id} items; the
 * provider returns a fresh ItemStack the lobby then tags with its own PDC marker/name/lore.
 */
public interface CustomItemProvider extends Integration {

    /** Data loaded (ItemsAdder loads its content asynchronously after the server started). */
    boolean ready();

    /** Fresh copy of the custom item, empty when unknown/not ready. Main thread only. */
    Optional<ItemStack> item(String namespacedId);

    /** Whether the id refers to a known custom item (false while not ready). */
    boolean exists(String namespacedId);

    /** Callback (main thread) invoked whenever the custom item data becomes available or was reloaded. */
    void onReady(Runnable callback);
}
