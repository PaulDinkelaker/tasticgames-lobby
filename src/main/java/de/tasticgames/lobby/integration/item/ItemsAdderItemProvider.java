package de.tasticgames.lobby.integration.item;

import de.tasticgames.lobby.integration.Integration;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ItemsAdder backend for custom lobby items. ItemsAdder loads its content after the server started
 * (and on {@code /iazip}/reload), so consumers register {@link #onReady(Runnable)} and re-give items.
 */
public final class ItemsAdderItemProvider implements CustomItemProvider, Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final List<Runnable> readyCallbacks = new CopyOnWriteArrayList<>();
    private volatile boolean available;
    private volatile boolean ready;
    private volatile boolean warned;

    public ItemsAdderItemProvider(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            ready = ItemsAdder.areItemsLoaded();
            available = true;
        } catch (Throwable t) {
            available = false;
            logger.warning("ItemsAdder hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – vanilla items are used.");
        }
    }

    public void unhook() {
        HandlerList.unregisterAll(this);
        readyCallbacks.clear();
        available = false;
        ready = false;
    }

    @Override
    public String pluginName() {
        return "ItemsAdder";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean ready() {
        return available && ready;
    }

    @Override
    public Optional<ItemStack> item(String namespacedId) {
        if (!ready() || namespacedId == null || namespacedId.isBlank()) return Optional.empty();
        try {
            CustomStack stack = CustomStack.getInstance(namespacedId);
            return stack == null ? Optional.empty() : Optional.of(stack.getItemStack().clone());
        } catch (Throwable t) {
            warnOnce("item " + namespacedId, t);
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(String namespacedId) {
        if (!ready() || namespacedId == null || namespacedId.isBlank()) return false;
        try {
            return CustomStack.isInRegistry(namespacedId);
        } catch (Throwable t) {
            warnOnce("registry lookup", t);
            return false;
        }
    }

    @Override
    public void onReady(Runnable callback) {
        Objects.requireNonNull(callback);
        if (ready()) {
            // ItemsAdder already fired its load event (early load order, /iareload before we hooked in):
            // running the callback now instead of waiting for an event that will not come again
            try {
                callback.run();
            } catch (RuntimeException e) {
                logger.warning("ItemsAdder ready callback failed: " + e.getMessage());
            }
            return;
        }
        readyCallbacks.add(callback);
    }

    @Override
    public boolean fontImagesSupported() {
        return ready();
    }

    @Override
    public Optional<FontGlyph> fontImage(String namespacedId) {
        if (!ready() || namespacedId == null || namespacedId.isBlank()) return Optional.empty();
        try {
            dev.lone.itemsadder.api.FontImages.FontImageWrapper wrapper = new dev.lone.itemsadder.api.FontImages.FontImageWrapper(namespacedId);
            if (!wrapper.exists()) return Optional.empty();
            return Optional.of(new FontGlyph(wrapper.getString(), wrapper.getWidth()));
        } catch (Throwable t) {
            warnOnce("font image " + namespacedId, t);
            return Optional.empty();
        }
    }

    @Override
    public String pixelOffset(int pixels) {
        if (!ready() || pixels == 0) return "";
        try {
            return dev.lone.itemsadder.api.FontImages.FontImageWrapper.applyPixelsOffsetToString("", pixels);
        } catch (Throwable t) {
            warnOnce("pixel offset", t);
            return "";
        }
    }

    @EventHandler
    public void onLoadData(ItemsAdderLoadDataEvent event) {
        ready = true;
        logger.info("ItemsAdder data loaded (" + event.getCause() + ") – refreshing custom lobby items.");
        for (Runnable callback : readyCallbacks) {
            try {
                callback.run();
            } catch (RuntimeException e) {
                logger.warning("ItemsAdder ready callback failed: " + e.getMessage());
            }
        }
    }

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("ItemsAdder " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
