package de.tasticgames.lobby.player;

import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.item.LobbyItemService;
import de.tasticgames.lobby.music.MusicService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.visibility.PlayerVisibilityService;
import de.tasticgames.player.event.TasticPlayerSettingChangedEvent;
import de.tasticgames.settings.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

/**
 * Applies setting changes live (music, sounds, visibility, items, HUD, cosmetics, reduced effects).
 */
public final class LobbySettingChangeListener implements Listener {

    private final LobbyPlayerService players;
    private final MusicService music;
    private final PlayerVisibilityService visibility;
    private final LobbyItemService items;
    private final CosmeticService cosmetics;

    public LobbySettingChangeListener(LobbyPlayerService players, MusicService music, PlayerVisibilityService visibility,
                                      LobbyItemService items, CosmeticService cosmetics) {
        this.players = Objects.requireNonNull(players);
        this.music = Objects.requireNonNull(music);
        this.visibility = Objects.requireNonNull(visibility);
        this.items = Objects.requireNonNull(items);
        this.cosmetics = Objects.requireNonNull(cosmetics);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSettingChanged(TasticPlayerSettingChangedEvent event) {
        Player player = Bukkit.getPlayer(event.tasticPlayer().minecraftUuid());
        if (player == null || !player.isOnline()) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        String id = event.key().id();
        if (id.equals(CoreSettings.MUSIC_ENABLED.id()) || id.equals(CoreSettings.MUSIC_VOLUME.id())) {
            music.onSettingsChanged(player);
        } else if (id.equals(LobbySettings.PLAYER_VISIBILITY.id())) {
            visibility.apply(player);
            items.refresh(player, lobbyPlayer);
        } else if (id.equals(LobbySettings.ITEMS_ENABLED.id())) {
            items.refresh(player, lobbyPlayer);
        } else if (id.equals(CoreSettings.COSMETICS_VISIBLE.id()) || id.equals(CoreSettings.REDUCED_EFFECTS.id())) {
            cosmetics.render(player);
        }
    }
}
