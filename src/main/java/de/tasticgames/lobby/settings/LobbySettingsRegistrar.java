package de.tasticgames.lobby.settings;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.service.Service;
import de.tasticgames.settings.SettingKey;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Registers the lobby setting keys in TasticCore before any player is loaded.
 */
public final class LobbySettingsRegistrar implements Service {

    private final TasticCoreApi coreApi;
    private final Logger logger;

    public LobbySettingsRegistrar(TasticCoreApi coreApi, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-settings-registrar";
    }

    @Override
    public void start() {
        int registered = 0;
        for (SettingKey<?> key : LobbySettings.ALL) {
            if (!coreApi.settingRegistry().contains(key.id())) {
                coreApi.settingRegistry().register(key);
                registered++;
            }
        }
        logger.info("Registered " + registered + " lobby settings (" + coreApi.settingRegistry().size() + " total).");
    }

    @Override
    public void stop() {
    }
}
