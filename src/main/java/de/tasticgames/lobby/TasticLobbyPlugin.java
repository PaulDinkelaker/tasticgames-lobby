package de.tasticgames.lobby;

import de.tasticgames.lobby.bootstrap.LobbyBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TasticLobby – the TasticGames hub. Entry point only; see {@link LobbyBootstrap}.
 */
public final class TasticLobbyPlugin extends JavaPlugin {

    private volatile LobbyBootstrap bootstrap;

    @Override
    public void onEnable() {
        getLogger().info("Starting TasticLobby " + getPluginMeta().getVersion() + "...");
        LobbyBootstrap newBootstrap = new LobbyBootstrap(this);
        try {
            newBootstrap.start();
            bootstrap = newBootstrap;
            getLogger().info("TasticLobby started successfully.");
        } catch (Exception exception) {
            getLogger().severe("TasticLobby failed to start: " + exception.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "Startup failure", exception);
            try {
                newBootstrap.stop();
            } catch (Exception ignored) {
                // best effort
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        LobbyBootstrap current = bootstrap;
        bootstrap = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
            getLogger().info("TasticLobby stopped.");
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.WARNING, "TasticLobby did not stop cleanly", exception);
        }
    }

    public LobbyBootstrap bootstrap() {
        LobbyBootstrap current = bootstrap;
        if (current == null) {
            throw new IllegalStateException("TasticLobby is not running.");
        }
        return current;
    }
}
