package de.tasticgames.lobby.pass;

import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.lobby.util.Pagination;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.pass.PassLeaderboardEntry;
import de.tasticgames.pass.PassQuestScope;
import de.tasticgames.pass.PassQuestSnapshot;
import de.tasticgames.pass.PassRewardGrant;
import de.tasticgames.pass.PassRewardGrantResult;
import de.tasticgames.pass.PassRewardType;
import de.tasticgames.pass.PassSeasonSnapshot;
import de.tasticgames.pass.PassSnapshot;
import de.tasticgames.pass.PassTierSnapshot;
import de.tasticgames.pass.PassTrack;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * TasticPass UI: overview, the paged 100-tier reward track (10 levels per page, both tracks),
 * quests, premium information and the season leaderboard. Premium is an entitlement – this UI never
 * shows a payment form, only what premium gives and where to get it.
 * <p>
 * Dialogs use {@code pause(false)} and {@code DialogAfterAction.NONE}, so every button callback
 * re-opens a view or closes the dialog explicitly. Callbacks arrive off the main thread and are
 * routed through {@link DialogSupport}. When the pass API is unavailable every entry point shows a
 * localized notice instead of failing.
 */
public final class PassDialogService {

    /** Levels shown per page of the reward track (100 tiers → 10 pages). */
    public static final int TIERS_PER_PAGE = 10;

    private final PassStateService state;
    private final Supplier<PassConfiguration> configuration;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;

    public PassDialogService(PassStateService state, Supplier<PassConfiguration> configuration, LobbyMessages messages, DialogSupport dialogs,
                             MainThread mainThread, LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.state = Objects.requireNonNull(state);
        this.configuration = Objects.requireNonNull(configuration);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    // ------------------------------------------------------------------ overview

    public void openOverview(Player player) {
        withSnapshot(player, "pass.overview_open", (snapshot, season) -> {
            SupportedLanguage lang = messages.languageOf(player);
            long days = PassFormat.daysLeft(snapshot.seasonEndsAt(), Instant.now());
            int claimable = state.claimableCount(snapshot);
            List<Component> body = new ArrayList<>();
            body.add(messages.get(lang, "pass.overview.season", Map.of("season", seasonName(snapshot, season), "days", days)));
            body.add(messages.get(lang, "pass.overview.level", Map.of("level", snapshot.level(), "max", snapshot.maxLevel())));
            body.add(atMaxLevel(snapshot)
                    ? messages.get(lang, "pass.overview.max_level", Map.of("bar", PassFormat.progressBar(1, 1),
                    "xp", PassFormat.number(snapshot.totalXp(), lang)))
                    : messages.get(lang, "pass.overview.progress", Map.of("bar", PassFormat.progressBar(snapshot.xpIntoLevel(), snapshot.xpForNextLevel()),
                    "percent", PassFormat.percent(snapshot.xpIntoLevel(), snapshot.xpForNextLevel()),
                    "xp", PassFormat.number(snapshot.xpIntoLevel(), lang), "next", PassFormat.number(snapshot.xpForNextLevel(), lang))));
            body.add(snapshot.premium()
                    ? messages.get(lang, "pass.overview.premium_active", Map.of())
                    : messages.get(lang, "pass.overview.premium_inactive", Map.of()));
            body.add(messages.get(lang, "pass.overview.next_free", Map.of("reward", nextRewardText(lang, PassTrack.FREE, snapshot))));
            body.add(messages.get(lang, "pass.overview.next_premium", Map.of("reward", nextRewardText(lang, PassTrack.PREMIUM, snapshot))));
            if (claimable > 0) {
                body.add(messages.get(lang, "pass.overview.claimable", Map.of("count", claimable)));
            }
            List<ActionButton> buttons = List.of(
                    dialogs.button(player, messages.get(lang, "pass.button.rewards", Map.of()), null, DialogSupport.BUTTON_WIDTH, p -> openRewards(p, pageOf(snapshot))),
                    dialogs.button(player, messages.get(lang, "pass.button.quests", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openQuests),
                    dialogs.button(player, messages.get(lang, "pass.button.premium", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openPremium),
                    dialogs.button(player, messages.get(lang, "pass.button.leaderboard", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openLeaderboard));
            dialogs.show(player, dialogs.menu(messages.get(lang, "pass.overview.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
        });
    }

    // ------------------------------------------------------------------ rewards

    /** Opens the reward track on the page of the player's current level. */
    public void openRewards(Player player) {
        withSnapshot(player, "pass.rewards_open", (snapshot, season) -> renderRewards(player, snapshot, pageOf(snapshot)));
    }

    public void openRewards(Player player, int page) {
        withSnapshot(player, "pass.rewards_open", (snapshot, season) -> renderRewards(player, snapshot, page));
    }

    private void renderRewards(Player player, PassSnapshot snapshot, int page) {
        SupportedLanguage lang = messages.languageOf(player);
        List<Integer> levels = new ArrayList<>(Math.max(1, snapshot.maxLevel()));
        for (int level = 1; level <= snapshot.maxLevel(); level++) {
            levels.add(level);
        }
        Pagination.Page<Integer> current = Pagination.of(levels, page, TIERS_PER_PAGE);
        int claimable = state.claimableCount(snapshot);
        List<Component> body = new ArrayList<>();
        body.add(messages.get(lang, "pass.rewards.page", Map.of("page", current.number(), "pages", current.pages(), "level", snapshot.level())));
        List<ActionButton> buttons = new ArrayList<>();
        for (int level : current.items()) {
            for (PassTrack track : PassTrack.values()) {
                PassTierSnapshot tier = state.tier(level, track).orElse(null);
                if (tier == null) {
                    continue;
                }
                Component reward = rewardText(lang, tier);
                String trackName = trackName(lang, track);
                if (state.claimable(snapshot, level, track)) {
                    body.add(messages.get(lang, "pass.rewards.entry_available", Map.of("level", level, "track", trackName, "reward", reward)));
                    buttons.add(dialogs.button(player, messages.get(lang, "pass.rewards.claim", Map.of("level", level, "track", trackName)),
                            reward, DialogSupport.WIDE_BUTTON_WIDTH, p -> claim(p, level, track, current.index())));
                    continue;
                }
                String key = PassStateService.claimed(snapshot, level, track) ? "pass.rewards.entry_claimed"
                        : track == PassTrack.PREMIUM && !snapshot.premium() && snapshot.level() >= level ? "pass.rewards.entry_premium"
                        : "pass.rewards.entry_locked";
                body.add(messages.get(lang, key, Map.of("level", level, "track", trackName, "reward", reward)));
            }
        }
        if (claimable > 0) {
            buttons.add(dialogs.button(player, messages.get(lang, "pass.rewards.claim_all", Map.of("count", claimable)), null,
                    DialogSupport.WIDE_BUTTON_WIDTH, p -> claimAll(p, current.index())));
        }
        if (current.hasPrevious()) {
            buttons.add(dialogs.button(player, messages.get(lang, "pass.rewards.previous", Map.of()), null, DialogSupport.BUTTON_WIDTH,
                    p -> openRewards(p, current.index() - 1)));
        }
        if (current.hasNext()) {
            buttons.add(dialogs.button(player, messages.get(lang, "pass.rewards.next", Map.of()), null, DialogSupport.BUTTON_WIDTH,
                    p -> openRewards(p, current.index() + 1)));
        }
        buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.BUTTON_WIDTH, this::openOverview));
        dialogs.show(player, dialogs.menu(messages.get(lang, "pass.rewards.title", Map.of()), body, buttons,
                dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
    }

    private void claim(Player player, int level, PassTrack track, int page) {
        state.claim(player.getUniqueId(), level, track).whenComplete((result, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            report(player, result, throwable, "claim tier " + level + " " + track);
            openRewards(player, page);
        }));
    }

    private void claimAll(Player player, int page) {
        state.claimAll(player.getUniqueId()).whenComplete((result, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            report(player, result, throwable, "claim all tiers");
            openRewards(player, page);
        }));
    }

    /** Summary feedback of a claim; the per-reward lines come from the pass claim event. */
    private void report(Player player, PassRewardGrantResult result, Throwable throwable, String what) {
        if (throwable != null || result == null) {
            logger.warning("Pass " + what + " failed for " + player.getName() + ": "
                    + (throwable == null ? "no result" : LobbyThrowables.rootMessage(throwable)));
            messages.send(player, "pass.unavailable");
            sounds.error(player);
            return;
        }
        if (result.applied() && !result.granted().isEmpty()) {
            sounds.success(player);
            messages.send(player, "pass.rewards.claimed_ok", Map.of("count", result.granted().size()));
            return;
        }
        sounds.error(player);
        messages.send(player, "pass.rewards.claim_failed", Map.of("reason", outcome(messages.languageOf(player), result.outcome())));
    }

    // ------------------------------------------------------------------ quests

    public void openQuests(Player player) {
        withSnapshot(player, "pass.quests_open", (snapshot, season) -> {
            SupportedLanguage lang = messages.languageOf(player);
            List<Component> body = new ArrayList<>();
            boolean any = false;
            for (PassQuestScope scope : PassQuestScope.values()) {
                List<PassQuestSnapshot> quests = state.quests(snapshot, scope);
                if (quests.isEmpty()) {
                    continue;
                }
                any = true;
                String scopeKey = scope.name().toLowerCase(Locale.ROOT);
                body.add(messages.get(lang, "pass.quests." + scopeKey, Map.of()));
                for (PassQuestSnapshot quest : quests) {
                    body.add(messages.get(lang, quest.completed() ? "pass.quests.entry_done" : "pass.quests.entry", Map.of(
                            "quest", questName(lang, quest),
                            "bar", PassFormat.progressBar(quest.progress(), quest.target(), 10),
                            "progress", PassFormat.number(Math.min(quest.progress(), quest.target()), lang),
                            "target", PassFormat.number(quest.target(), lang),
                            "xp", quest.xpReward())));
                }
                body.add(messages.get(lang, "pass.quests.reset_" + scopeKey, Map.of()));
            }
            if (!any) {
                body.add(messages.get(lang, "pass.quests.empty", Map.of()));
            }
            List<ActionButton> buttons = List.of(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null,
                    DialogSupport.BUTTON_WIDTH, this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "pass.quests.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    // ------------------------------------------------------------------ premium

    public void openPremium(Player player) {
        withSnapshot(player, "pass.premium_open", (snapshot, season) -> {
            SupportedLanguage lang = messages.languageOf(player);
            int cents = snapshot.premiumPriceCents() > 0 ? snapshot.premiumPriceCents() : season.premiumPriceCents();
            String currency = snapshot.currency() == null || snapshot.currency().isBlank() ? season.currency() : snapshot.currency();
            List<Component> body = new ArrayList<>();
            body.add(messages.get(lang, "pass.premium.description", Map.of()));
            body.add(messages.get(lang, "pass.premium.benefit_cosmetics", Map.of()));
            body.add(messages.get(lang, "pass.premium.benefit_boost", Map.of()));
            body.add(messages.get(lang, "pass.premium.benefit_fair", Map.of()));
            if (snapshot.premium()) {
                body.add(messages.get(lang, "pass.premium.owned", Map.of()));
            } else {
                body.add(messages.get(lang, "pass.premium.price", Map.of("price", PassFormat.price(cents, currency, lang))));
                String infoKey = configuration.get().premium().infoKey();
                body.add(messages.get(lang, messages.contains(infoKey) ? infoKey : "pass.premium.howto", Map.of()));
                String url = configuration.get().premium().storeUrl();
                if (!url.isBlank()) {
                    body.add(messages.get(lang, "pass.premium.store", Map.of("url", url)));
                }
            }
            List<ActionButton> buttons = List.of(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null,
                    DialogSupport.BUTTON_WIDTH, this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "pass.premium.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    // ------------------------------------------------------------------ leaderboard

    public void openLeaderboard(Player player) {
        if (!available(player)) {
            return;
        }
        telemetry.event("pass.leaderboard_open", player.getUniqueId(), Map.of());
        SupportedLanguage lang = messages.languageOf(player);
        state.leaderboard().whenComplete((entries, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            List<Component> body = new ArrayList<>();
            if (throwable != null) {
                logger.warning("Pass leaderboard failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                body.add(messages.get(lang, "pass.unavailable", Map.of()));
            } else if (entries.isEmpty()) {
                body.add(messages.get(lang, "pass.leaderboard.empty", Map.of()));
            } else {
                for (PassLeaderboardEntry entry : entries) {
                    body.add(messages.get(lang, player.getUniqueId().equals(entry.player()) ? "pass.leaderboard.entry_you" : "pass.leaderboard.entry", Map.of(
                            "rank", entry.rank(), "player", entry.name() == null ? "?" : entry.name(), "level", entry.level(),
                            "xp", PassFormat.number(entry.totalXp(), lang))));
                }
            }
            List<ActionButton> buttons = List.of(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null,
                    DialogSupport.BUTTON_WIDTH, this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "pass.leaderboard.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        }));
    }

    // ------------------------------------------------------------------ helpers

    /** Loads a fresh snapshot and renders on the main thread; shows a notice when the pass is down. */
    private void withSnapshot(Player player, String telemetryType, BiConsumer<PassSnapshot, PassSeasonSnapshot> renderer) {
        if (!available(player)) {
            return;
        }
        telemetry.event(telemetryType, player.getUniqueId(), Map.of());
        state.fresh(player.getUniqueId()).whenComplete((snapshot, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            PassSeasonSnapshot season = state.season().orElse(null);
            if (throwable != null || snapshot == null || !snapshot.seasonActive() || season == null) {
                if (throwable != null) {
                    logger.warning("Pass state failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                }
                unavailable(player);
                return;
            }
            renderer.accept(snapshot, season);
        }));
    }

    private boolean available(Player player) {
        if (configuration.get().dialogsEnabled() && state.available()) {
            return true;
        }
        unavailable(player);
        return false;
    }

    private void unavailable(Player player) {
        SupportedLanguage lang = messages.languageOf(player);
        dialogs.show(player, dialogs.notice(messages.get(lang, "pass.overview.title", Map.of()),
                List.of(messages.get(lang, "pass.unavailable", Map.of())), dialogs.close(messages.get(lang, "common.close", Map.of()))));
    }

    private static boolean atMaxLevel(PassSnapshot snapshot) {
        return snapshot.xpForNextLevel() <= 0 || snapshot.level() >= snapshot.maxLevel();
    }

    /** Reward page of the player's current level, so the next rewards are visible right away. */
    private static int pageOf(PassSnapshot snapshot) {
        return Math.max(0, (Math.max(1, snapshot.level()) - 1) / TIERS_PER_PAGE);
    }

    private static String seasonName(PassSnapshot snapshot, PassSeasonSnapshot season) {
        if (snapshot.seasonName() != null && !snapshot.seasonName().isBlank()) {
            return snapshot.seasonName();
        }
        return season.displayName() == null || season.displayName().isBlank() ? season.key() : season.displayName();
    }

    private Component nextRewardText(SupportedLanguage lang, PassTrack track, PassSnapshot snapshot) {
        Optional<PassTierSnapshot> next = state.nextReward(snapshot, track);
        return next.map(tier -> messages.get(lang, "pass.overview.next_entry", Map.of("level", tier.level(), "reward", rewardText(lang, tier))))
                .orElseGet(() -> messages.get(lang, "pass.overview.next_none", Map.of()));
    }

    /** Human readable reward of a tier; falls back to the reward type when the season carries no key. */
    Component rewardText(SupportedLanguage lang, PassTierSnapshot tier) {
        String typeKey = "pass.reward." + tier.rewardType().name().toLowerCase(Locale.ROOT);
        String key = tier.displayKey() != null && messages.contains(tier.displayKey()) ? tier.displayKey() : typeKey;
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("amount", PassFormat.number(tier.rewardAmount(), lang));
        placeholders.put("name", rewardName(lang, tier));
        placeholders.put("multiplier", String.format(Locale.ROOT, "%.2f", PassFormat.multiplier(tier.rewardAmount())));
        placeholders.put("duration", PassFormat.duration(tier.rewardValue()));
        return messages.get(lang, key, placeholders);
    }

    /** Cosmetics and features are shown with their localized name when the lobby knows one. */
    private String rewardName(SupportedLanguage lang, PassTierSnapshot tier) {
        String value = tier.rewardValue() == null ? "" : tier.rewardValue().trim();
        if (value.isEmpty()) {
            return plain(messages.raw(lang, "pass.reward.unknown"));
        }
        if (tier.rewardType() == PassRewardType.COSMETIC) {
            String cosmeticKey = "lobby.cosmetic." + value + ".name";
            if (messages.contains(cosmeticKey)) {
                return plain(messages.raw(lang, cosmeticKey));
            }
        }
        String featureKey = "pass.feature." + value;
        return messages.contains(featureKey) ? plain(messages.raw(lang, featureKey)) : value.replace('_', ' ');
    }

    /** Localized quest name; the season's display key wins, then the bundled name, then the raw key. */
    String questName(SupportedLanguage lang, PassQuestSnapshot quest) {
        if (quest.displayKey() != null && messages.contains(quest.displayKey())) {
            return plain(messages.raw(lang, quest.displayKey()));
        }
        String key = "pass.quest.name." + quest.questKey();
        return messages.contains(key) ? plain(messages.raw(lang, key)) : quest.questKey().replace('_', ' ');
    }

    private String trackName(SupportedLanguage lang, PassTrack track) {
        return plain(messages.raw(lang, "pass.track." + track.name().toLowerCase(Locale.ROOT)));
    }

    /** Localized text of a server side outcome ({@code TIER_LOCKED}, {@code PREMIUM_REQUIRED}, ...). */
    String outcome(SupportedLanguage lang, String outcome) {
        String normalized = outcome == null || outcome.isBlank() ? "unknown" : outcome.trim().toLowerCase(Locale.ROOT);
        String key = "pass.outcome." + normalized;
        return messages.contains(key) ? plain(messages.raw(lang, key)) : plain(messages.raw(lang, "pass.outcome.unknown"));
    }

    /** Chat line of a single granted reward (used by the claim event listener). */
    public Component grantText(SupportedLanguage lang, PassRewardGrant grant) {
        PassTierSnapshot tier = new PassTierSnapshot(grant.level(), grant.track(), grant.rewardType(), grant.rewardValue(),
                grant.rewardAmount(), null, null);
        return messages.get(lang, grant.status() == de.tasticgames.pass.PassRewardStatus.ALREADY_OWNED ? "pass.claim.already_owned" : "pass.claim.granted",
                Map.of("level", grant.level(), "track", trackName(lang, grant.track()), "reward", rewardText(lang, tier)));
    }

    /** Chat line describing a tier (used by the level-up feedback). */
    public Component tierText(SupportedLanguage lang, PassTierSnapshot tier) {
        return messages.get(lang, "pass.levelup.reward", Map.of("level", tier.level(), "track", trackName(lang, tier.track()),
                "reward", rewardText(lang, tier)));
    }

    private static String plain(String miniMessage) {
        return miniMessage.replaceAll("<[^>]+>", "").trim();
    }
}
