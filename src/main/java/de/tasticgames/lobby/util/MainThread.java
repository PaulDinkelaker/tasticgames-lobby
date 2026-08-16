package de.tasticgames.lobby.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Helpers to hop between async completions and the Paper main thread.
 */
public final class MainThread {

    private final Plugin plugin;

    public MainThread(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void run(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public void later(long ticks, Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, runnable, ticks);
    }

    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        run(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Runs the continuation on the main thread when the future completes. */
    public <T> void whenComplete(CompletableFuture<T> future, java.util.function.BiConsumer<T, Throwable> consumer) {
        future.whenComplete((result, throwable) -> run(() -> consumer.accept(result, throwable == null ? null : LobbyThrowables.unwrap(throwable))));
    }
}
