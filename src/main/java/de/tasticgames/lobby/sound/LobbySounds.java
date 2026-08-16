package de.tasticgames.lobby.sound;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.settings.CoreSettings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * UI/gameplay sounds respecting {@code sounds.enabled} and {@code sounds.volume}.
 */
public final class LobbySounds {

    private final TasticCoreApi coreApi;

    public LobbySounds(TasticCoreApi coreApi) {
        this.coreApi = Objects.requireNonNull(coreApi);
    }

    public void play(Player player, String soundKey, float volume, float pitch) {
        if (soundKey == null || soundKey.isBlank()) {
            return;
        }
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (tastic != null && !tastic.settings().get(CoreSettings.SOUNDS_ENABLED)) {
            return;
        }
        float factor = tastic == null ? 1f : tastic.settings().get(CoreSettings.SOUND_VOLUME) / 100f;
        Key key;
        try {
            key = Key.key(soundKey);
        } catch (Exception e) {
            return;
        }
        player.playSound(Sound.sound(key, Sound.Source.MASTER, volume * factor, pitch), Sound.Emitter.self());
    }

    public void click(Player player) {
        play(player, "minecraft:ui.button.click", 0.6f, 1.0f);
    }

    public void success(Player player) {
        play(player, "minecraft:entity.experience_orb.pickup", 0.8f, 1.2f);
    }

    public void error(Player player) {
        play(player, "minecraft:block.note_block.bass", 0.8f, 0.7f);
    }
}
