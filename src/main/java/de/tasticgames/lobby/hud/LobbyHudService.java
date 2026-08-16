package de.tasticgames.lobby.hud;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.social.SocialSnapshotService;
import de.tasticgames.service.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Native sidebar HUD (per-player scoreboard, team based lines, coalesced refresh).
 * Cookie world lines are supplied by the cookie module.
 */
public final class LobbyHudService implements Service {

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final LobbyPlayerService players;
    private final SocialSnapshotService social;
    private final LobbyMessages messages;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private volatile Function<Player, List<Component>> cookieLines = p -> List.of();
    private volatile Function<Player, String> rankResolver = p -> "Player";
    private BukkitTask task;

    public LobbyHudService(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, LobbyPlayerService players,
                           SocialSnapshotService social, LobbyMessages messages) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.players = Objects.requireNonNull(players);
        this.social = Objects.requireNonNull(social);
        this.messages = Objects.requireNonNull(messages);
    }

    @Override
    public String id() {
        return "lobby-hud-service";
    }

    @Override
    public void start() {
        int seconds = Math.max(1, configurationService.configuration().hud().refreshSeconds());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L * seconds, 20L * seconds);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
        boards.clear();
    }

    public void setCookieLines(Function<Player, List<Component>> supplier) {
        this.cookieLines = Objects.requireNonNull(supplier);
    }

    public void setRankResolver(Function<Player, String> resolver) {
        this.rankResolver = Objects.requireNonNull(resolver);
    }

    public int activeBoards() {
        return boards.size();
    }

    public void show(Player player) {
        LobbyConfiguration.Hud config = configurationService.configuration().hud();
        boolean enabled = config.enabled() && coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(LobbySettings.HUD_ENABLED)).orElse(true);
        if (!enabled) {
            hide(player);
            return;
        }
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), ignored -> Bukkit.getScoreboardManager().getNewScoreboard());
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
        refresh(player);
    }

    public void hide(Player player) {
        Scoreboard removed = boards.remove(player.getUniqueId());
        if (removed != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void refresh(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null || !player.isOnline()) {
            return;
        }
        LobbyPlayer lobbyPlayer = players.getOrCreate(player);
        var lang = messages.languageOf(player);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(messages.get(lang, "lobby.hud.player", Map.of("player", player.getName())));
        lines.add(messages.get(lang, "lobby.hud.rank", Map.of("rank", rankResolver.apply(player))));
        lines.add(Component.empty());
        lines.add(messages.get(lang, "lobby.hud.online", Map.of("count", Bukkit.getOnlinePlayers().size())));
        var snapshot = social.cached(player.getUniqueId());
        String party = snapshot.map(s -> s.party() == null ? null : s.party().members().size() + "/" + 8).orElse(null);
        lines.add(messages.get(lang, "lobby.hud.party", Map.of("party", party == null ? messages.raw(lang, "lobby.hud.none") : party)));
        String clan = snapshot.map(s -> s.clan() == null ? null : s.clan().name()).orElse(null);
        lines.add(messages.get(lang, "lobby.hud.clan", Map.of("clan", clan == null ? messages.raw(lang, "lobby.hud.none") : clan)));
        List<Component> cookie = cookieLines.apply(player);
        if (!cookie.isEmpty()) {
            lines.add(Component.empty());
            lines.addAll(cookie);
        }
        lines.add(Component.empty());
        lines.add(messages.get(lang, "lobby.hud.footer", Map.of()));
        render(board, messages.mini(configurationService.configuration().hud().title()), lines, lobbyPlayer.inCookieWorld());
    }

    private void render(Scoreboard board, Component title, List<Component> lines, boolean cookieWorld) {
        Objective objective = board.getObjective("tastic");
        if (objective == null) {
            objective = board.registerNewObjective("tastic", Criteria.DUMMY, title);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            objective.displayName(title);
        }
        int max = Math.min(lines.size(), 15);
        for (int i = 0; i < 15; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            Team team = board.getTeam("line" + i);
            if (i >= max) {
                if (team != null) {
                    board.resetScores(entry);
                    team.unregister();
                }
                continue;
            }
            if (team == null) {
                team = board.registerNewTeam("line" + i);
                team.addEntry(entry);
            }
            team.prefix(lines.get(i));
            objective.getScore(entry).setScore(max - i);
        }
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(player.getUniqueId())) {
                refresh(player);
            }
        }
    }
}
