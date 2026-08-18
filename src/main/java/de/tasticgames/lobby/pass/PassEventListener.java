package de.tasticgames.lobby.pass;

import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.pass.PassQuestSnapshot;
import de.tasticgames.pass.PassTierSnapshot;
import de.tasticgames.pass.event.TasticPassClaimEvent;
import de.tasticgames.pass.event.TasticPassLevelUpEvent;
import de.tasticgames.pass.event.TasticPassQuestCompletedEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Player feedback for the pass events TasticCore fires on the main thread: a title, a sound and the
 * unlocked rewards in chat on a level up, an actionbar plus chat line on a completed quest and one
 * chat line per reward a claim granted.
 */
public final class PassEventListener implements Listener {

    private final PassStateService state;
    private final PassDialogService dialogService;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;

    public PassEventListener(PassStateService state, PassDialogService dialogService, LobbyMessages messages, LobbySounds sounds,
                             LobbyTelemetryService telemetry) {
        this.state = Objects.requireNonNull(state);
        this.dialogService = Objects.requireNonNull(dialogService);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelUp(TasticPassLevelUpEvent event) {
        Player player = Bukkit.getPlayer(event.tasticPlayer().minecraftUuid());
        if (player == null || !state.enabled()) {
            return;
        }
        SupportedLanguage lang = messages.languageOf(player);
        player.showTitle(Title.title(messages.get(lang, "pass.levelup.title", Map.of("level", event.toLevel())),
                messages.get(lang, "pass.levelup.subtitle", Map.of("level", event.toLevel())),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        sounds.play(player, "minecraft:ui.toast.challenge_complete", 1f, 1f);
        messages.send(player, "pass.levelup.chat", Map.of("from", event.fromLevel(), "to", event.toLevel()));
        for (PassTierSnapshot tier : event.unlocked()) {
            player.sendMessage(dialogService.tierText(lang, tier));
        }
        telemetry.event("pass.level_up", player.getUniqueId(), Map.of("from", event.fromLevel(), "to", event.toLevel(),
                "unlocked", event.unlocked().size()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuestCompleted(TasticPassQuestCompletedEvent event) {
        Player player = Bukkit.getPlayer(event.tasticPlayer().minecraftUuid());
        if (player == null || !state.enabled()) {
            return;
        }
        PassQuestSnapshot quest = event.quest();
        SupportedLanguage lang = messages.languageOf(player);
        String name = dialogService.questName(lang, quest);
        player.sendActionBar(messages.get(lang, "pass.quest.completed_actionbar", Map.of("quest", name, "xp", event.xpAwarded())));
        messages.send(player, "pass.quest.completed", Map.of("quest", name, "xp", event.xpAwarded()));
        sounds.play(player, "minecraft:entity.player.levelup", 0.7f, 1.6f);
        telemetry.event("pass.quest_completed", player.getUniqueId(), Map.of("quest", quest.questKey(), "xp", event.xpAwarded()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaim(TasticPassClaimEvent event) {
        Player player = Bukkit.getPlayer(event.tasticPlayer().minecraftUuid());
        if (player == null || !state.enabled()) {
            return;
        }
        player.sendMessage(dialogService.grantText(messages.languageOf(player), event.grant()));
        telemetry.event("pass.reward_claimed", player.getUniqueId(), Map.of("level", event.grant().level(),
                "track", event.grant().track(), "type", event.grant().rewardType(), "status", event.grant().status()));
    }
}
