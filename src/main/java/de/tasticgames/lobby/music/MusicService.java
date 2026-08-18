package de.tasticgames.lobby.music;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.service.Service;
import de.tasticgames.settings.CoreSettings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Resource-pack music playlists (lobby / cookie world) driven by track durations, respecting
 * {@code music.enabled} and {@code music.volume}. One session per player, no overlaps.
 */
public final class MusicService implements Service {

    private record Session(LobbyConfiguration.Track track, Instant endsAt, boolean cookie) {
    }

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyPlayerService players;
    private final MusicAssetInstaller soundtrack;
    private final Logger logger;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask task;

    public MusicService(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyPlayerService players,
                        MusicAssetInstaller soundtrack, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.players = Objects.requireNonNull(players);
        this.soundtrack = Objects.requireNonNull(soundtrack);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "music-service";
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            stop(player);
        }
        sessions.clear();
    }

    public int activeSessions() {
        return sessions.size();
    }

    /** Starts (or restarts) the right playlist for the player's mode. */
    public void play(Player player) {
        LobbyConfiguration.Music config = configurationService.configuration().music();
        var tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (!config.enabled() || tastic == null || !tastic.settings().get(CoreSettings.MUSIC_ENABLED)) {
            stop(player);
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        List<LobbyConfiguration.Track> playlist = playlist(config, lobbyPlayer.inCookieWorld());
        if (playlist.isEmpty()) {
            stop(player);
            return;
        }
        Session current = sessions.get(player.getUniqueId());
        if (current != null && current.cookie() == lobbyPlayer.inCookieWorld() && current.endsAt().isAfter(Instant.now())) {
            return; // keep playing
        }
        stop(player);
        LobbyConfiguration.Track track = pick(playlist, current == null ? null : current.track(), config.shuffle());
        float volume = tastic.settings().get(CoreSettings.MUSIC_VOLUME) / 100f;
        Key key;
        try {
            key = Key.key(track.soundKey());
        } catch (Exception e) {
            logger.warning("Invalid music sound key '" + track.soundKey() + "' for track " + track.id());
            return;
        }
        // the soundtrack replaces Minecraft's music: silence whatever the client started before ours begins,
        // and play in the MUSIC category so the player's music slider controls it
        player.stopSound(SoundStop.source(Sound.Source.MUSIC));
        player.playSound(Sound.sound(key, Sound.Source.MUSIC, volume, 1f), Sound.Emitter.self());
        sessions.put(player.getUniqueId(), new Session(track, Instant.now().plusSeconds(track.durationSeconds() + config.gapSeconds()), lobbyPlayer.inCookieWorld()));
    }

    public void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null && player.isOnline()) {
            try {
                player.stopSound(SoundStop.named(Key.key(session.track().soundKey())));
            } catch (Exception ignored) {
                player.stopSound(SoundStop.source(Sound.Source.MUSIC));
            }
        }
    }

    /** Called on setting changes: volume changes restart the current track at the new volume. */
    public void onSettingsChanged(Player player) {
        stop(player);
        play(player);
    }

    private void tick() {
        Instant now = Instant.now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null && !session.endsAt().isAfter(now)) {
                play(player);
            }
        }
    }

    /** The installed soundtrack wins over the configured playlists as long as tracks are present. */
    private List<LobbyConfiguration.Track> playlist(LobbyConfiguration.Music config, boolean cookieWorld) {
        List<MusicTrack> installed = soundtrack.tracks();
        if (config.ostAutoPlaylist() && !installed.isEmpty()) {
            List<LobbyConfiguration.Track> tracks = new java.util.ArrayList<>(installed.size());
            for (MusicTrack track : installed) {
                tracks.add(new LobbyConfiguration.Track(track.id(), track.soundKey(), track.roundedSeconds(), 1));
            }
            return tracks;
        }
        return cookieWorld && !config.cookiePlaylist().isEmpty() ? config.cookiePlaylist() : config.lobbyPlaylist();
    }

    private static LobbyConfiguration.Track pick(List<LobbyConfiguration.Track> playlist, LobbyConfiguration.Track previous, boolean shuffle) {
        if (playlist.size() == 1) {
            return playlist.getFirst();
        }
        if (!shuffle) {
            int index = previous == null ? -1 : playlist.indexOf(previous);
            return playlist.get((index + 1) % playlist.size());
        }
        int total = playlist.stream().filter(t -> !t.equals(previous)).mapToInt(LobbyConfiguration.Track::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (LobbyConfiguration.Track track : playlist) {
            if (track.equals(previous)) continue;
            roll -= track.weight();
            if (roll < 0) return track;
        }
        return playlist.getFirst();
    }
}
