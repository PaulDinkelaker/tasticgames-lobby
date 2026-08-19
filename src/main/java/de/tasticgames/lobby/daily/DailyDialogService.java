package de.tasticgames.lobby.daily;

import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.localization.SupportedLanguage;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The daily reward screen: the seven day cycle with the day that is due highlighted, the current streak, what
 * the next milestone gives and a claim button while today's reward is still open.
 */
public final class DailyDialogService {

    private final DailyRewardService daily;
    private final Supplier<DailyRewardTable> table;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final LobbySounds sounds;

    public DailyDialogService(DailyRewardService daily, Supplier<DailyRewardTable> table, LobbyMessages messages,
                              DialogSupport dialogs, LobbySounds sounds) {
        this.daily = Objects.requireNonNull(daily);
        this.table = Objects.requireNonNull(table);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.sounds = Objects.requireNonNull(sounds);
    }

    public void open(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        if (!daily.enabled()) {
            messages.send(player, "lobby.daily.disabled");
            return;
        }
        DailyRewardTable rewards = table.get();
        DailyStreak streak = daily.state(player);
        LocalDate today = daily.today();
        int dueDay = streak.cycleDay(today, rewards.cycleLength());
        boolean claimable = streak.claimable(today);

        List<Component> body = new ArrayList<>();
        body.add(messages.get(lang, "lobby.daily.description", Map.of()));
        body.add(messages.get(lang, "lobby.daily.streak", Map.of(
                "streak", streak.streak(), "best", streak.bestStreak(), "total", streak.totalDays(),
                "bonus", Math.round((rewards.streakMultiplier(claimable ? streak.streakAfter(today) : streak.streak()) - 1) * 100))));
        for (int day = 1; day <= rewards.cycleLength(); day++) {
            String state = day == dueDay && claimable ? "today" : day < dueDay || (day == dueDay && !claimable) ? "done" : "open";
            body.add(messages.get(lang, "lobby.daily.day." + state, Map.of(
                    "day", day, "rewards", summary(lang, rewards.rewards(day)))));
        }
        nextMilestone(rewards, claimable ? streak.streakAfter(today) : streak.streak()).ifPresent(entry ->
                body.add(messages.get(lang, "lobby.daily.milestone", Map.of(
                        "streak", entry.getKey(), "rewards", summary(lang, entry.getValue())))));
        if (!claimable) {
            body.add(messages.get(lang, "lobby.daily.next_reset", Map.of()));
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (claimable) {
            buttons.add(dialogs.button(player, messages.get(lang, "lobby.daily.claim", Map.of()),
                    messages.get(lang, "lobby.daily.claim_tooltip", Map.of()), DialogSupport.BUTTON_WIDTH, this::claim));
        }
        dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.daily.title", Map.of()), body, buttons,
                dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
    }

    private void claim(Player player) {
        DailyRewardService.ClaimResult result = daily.claim(player);
        if (!result.claimed()) {
            messages.send(player, result.failureKey());
            sounds.error(player);
            return;
        }
        messages.send(player, "lobby.daily.claimed", Map.of("streak", result.streak()));
        for (String line : result.rewardLines()) {
            player.sendMessage(messages.mini(line));
        }
        sounds.play(player, "minecraft:ui.toast.challenge_complete", 0.9f, 1.1f);
        open(player);
    }

    /** Compact "+3 crumbs, golden cookie" line for one day. */
    private Component summary(SupportedLanguage lang, List<DailyRewardTable.Reward> rewards) {
        List<String> parts = new ArrayList<>();
        for (DailyRewardTable.Reward reward : rewards) {
            switch (reward.kind()) {
                case COOKIES -> parts.add(messages.raw(lang, "lobby.daily.summary.cookies")
                        .replace("<seconds>", String.valueOf(reward.amount())));
                case CRUMBS -> parts.add(messages.raw(lang, "lobby.daily.summary.crumbs")
                        .replace("<crumbs>", String.valueOf(reward.amount())));
                case SPECIAL_COOKIE -> parts.add(messages.raw(lang, "lobby.daily.summary.special")
                        .replace("<rarity>", reward.id()));
                case COSMETIC -> parts.add(messages.raw(lang, "lobby.daily.summary.cosmetic")
                        .replace("<cosmetic>", reward.id()));
            }
        }
        return parts.isEmpty() ? Component.text("-", NamedTextColor.DARK_GRAY) : messages.mini(String.join("<dark_gray>, <gray>", parts));
    }

    private java.util.Optional<Map.Entry<Integer, List<DailyRewardTable.Reward>>> nextMilestone(DailyRewardTable rewards, int streak) {
        return rewards.milestones().entrySet().stream()
                .filter(entry -> entry.getKey() >= Math.max(1, streak))
                .min(Map.Entry.comparingByKey());
    }
}
