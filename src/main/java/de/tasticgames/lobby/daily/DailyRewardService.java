package de.tasticgames.lobby.daily;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.cookie.CookieModule;
import de.tasticgames.lobby.cookie.CookieSession;
import de.tasticgames.lobby.cookie.SpecialCookieService;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.service.Service;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Daily rewards with a streak: one claim per day, seven days per cycle, and milestones that only a player who
 * keeps coming back reaches. The reason to log in tomorrow is that the streak (and the growing multiplier) is
 * lost otherwise – the reward itself scales with the player's own production, so it never breaks the economy.
 * <p>
 * The state lives in the player's TasticCore settings ({@link LobbySettings#DAILY_LAST_CLAIM} and friends), so
 * it is stored with the account and is the same on every server without a schema of its own.
 */
public final class DailyRewardService implements Service {

    /** Result of a claim attempt: what was handed out, or why nothing was. */
    public record ClaimResult(boolean claimed, String failureKey, int streak, List<String> rewardLines) {

        static ClaimResult failed(String key) {
            return new ClaimResult(false, key, 0, List.of());
        }
    }

    private final TasticCoreApi coreApi;
    private final CookieModule cookie;
    private final SpecialCookieService specialCookies;
    private final CosmeticService cosmetics;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Supplier<DailyRewardTable> table;
    private final Logger logger;

    public DailyRewardService(TasticCoreApi coreApi, CookieModule cookie, SpecialCookieService specialCookies,
                              CosmeticService cosmetics, LobbyMessages messages, LobbySounds sounds,
                              LobbyTelemetryService telemetry, Supplier<DailyRewardTable> table, Logger logger) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.cookie = Objects.requireNonNull(cookie);
        this.specialCookies = Objects.requireNonNull(specialCookies);
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.table = Objects.requireNonNull(table);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "daily-rewards";
    }

    @Override
    public void start() {
        // nothing to schedule: the reward is pulled by the player, not pushed
    }

    @Override
    public void stop() {
    }

    public boolean enabled() {
        return table.get().enabled();
    }

    /** Today in the configured reset zone – the same moment for everybody on the network. */
    public LocalDate today() {
        return LocalDate.now(table.get().zone());
    }

    /** The player's streak state, or {@link DailyStreak#NEVER} while the account is not loaded. */
    public DailyStreak state(Player player) {
        TasticPlayer tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (tastic == null) {
            return DailyStreak.NEVER;
        }
        String raw = tastic.settings().get(LobbySettings.DAILY_LAST_CLAIM);
        LocalDate last = null;
        if (raw != null && !raw.isBlank()) {
            try {
                last = LocalDate.parse(raw.trim());
            } catch (RuntimeException e) {
                last = null; // a broken value simply means "never claimed"
            }
        }
        return new DailyStreak(last, tastic.settings().get(LobbySettings.DAILY_STREAK),
                tastic.settings().get(LobbySettings.DAILY_BEST_STREAK),
                tastic.settings().get(LobbySettings.DAILY_TOTAL));
    }

    public boolean claimable(Player player) {
        return enabled() && state(player).claimable(today());
    }

    /**
     * Hands out today's rewards and advances the streak. Everything happens on the main thread; the settings
     * are written afterwards, so a failing write never hands out a reward twice.
     */
    public ClaimResult claim(Player player) {
        if (!enabled()) {
            return ClaimResult.failed("lobby.daily.disabled");
        }
        TasticPlayer tastic = coreApi.playerManager().find(player.getUniqueId()).orElse(null);
        if (tastic == null) {
            return ClaimResult.failed("common.error");
        }
        DailyRewardTable rewards = table.get();
        LocalDate today = today();
        DailyStreak current = state(player);
        if (!current.claimable(today)) {
            return ClaimResult.failed("lobby.daily.already");
        }
        CookieSession session = cookie.runtime().session(player.getUniqueId()).orElse(null);
        if (session == null) {
            // cookies are the main reward: without a loaded profile the claim would silently shrink
            return ClaimResult.failed("lobby.daily.unavailable");
        }

        DailyStreak next = current.claim(today);
        int cycleDay = current.cycleDay(today, rewards.cycleLength());
        double multiplier = rewards.streakMultiplier(next.streak());
        List<DailyRewardTable.Reward> due = new ArrayList<>(rewards.rewards(cycleDay));
        due.addAll(rewards.milestone(next.streak()));

        List<String> lines = new ArrayList<>();
        for (DailyRewardTable.Reward reward : due) {
            apply(player, session, reward, multiplier).ifPresent(lines::add);
        }
        session.touchDirty();
        persist(tastic, next);
        telemetry.event("lobby.daily_claimed", player.getUniqueId(),
                Map.of("streak", next.streak(), "day", cycleDay, "rewards", due.size()));
        return new ClaimResult(true, "", next.streak(), List.copyOf(lines));
    }

    /** Applies one reward and returns the line for the chat/dialog summary. */
    private Optional<String> apply(Player player, CookieSession session, DailyRewardTable.Reward reward, double multiplier) {
        switch (reward.kind()) {
            case COOKIES -> {
                CookieStats stats = cookie.engine().compute(session.profile());
                BigDecimal fromProduction = stats.effectiveCps().multiply(BigDecimal.valueOf(reward.amount()));
                BigDecimal floor = stats.clickValue().multiply(BigDecimal.valueOf(30));
                BigDecimal amount = fromProduction.max(floor)
                        .multiply(BigDecimal.valueOf(multiplier))
                        .setScale(0, RoundingMode.DOWN)
                        .max(BigDecimal.ONE);
                CookieAmount cookies = CookieAmount.of(amount);
                session.profile().earn(cookies);
                return Optional.of(messages.raw(messages.languageOf(player), "lobby.daily.reward.cookies")
                        .replace("<cookies>", cookie.formatter().format(cookies, localeOf(player))));
            }
            case CRUMBS -> {
                if (reward.amount() <= 0) {
                    return Optional.empty();
                }
                session.profile().addCrumbs(reward.amount());
                return Optional.of(messages.raw(messages.languageOf(player), "lobby.daily.reward.crumbs")
                        .replace("<crumbs>", String.valueOf(reward.amount())));
            }
            case SPECIAL_COOKIE -> {
                SpecialCookieRarity rarity = rarity(reward.id());
                if (!specialCookies.grant(player, rarity)) {
                    return Optional.empty(); // rarity not unlocked yet: the other rewards still apply
                }
                return Optional.of(messages.raw(messages.languageOf(player), "lobby.daily.reward.special")
                        .replace("<rarity>", rarity.displayName()));
            }
            case COSMETIC -> {
                if (reward.id().isBlank()) {
                    return Optional.empty();
                }
                cosmetics.unlock(player.getUniqueId(), reward.id(), "daily").whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        logger.warning("Daily cosmetic " + reward.id() + " for " + player.getName() + " failed: " + throwable.getMessage());
                    }
                });
                return Optional.of(messages.raw(messages.languageOf(player), "lobby.daily.reward.cosmetic")
                        .replace("<cosmetic>", reward.id()));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private SpecialCookieRarity rarity(String id) {
        try {
            return SpecialCookieRarity.valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return SpecialCookieRarity.GOLDEN;
        }
    }

    private Locale localeOf(Player player) {
        return messages.languageOf(player) == de.tasticgames.localization.SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
    }

    private void persist(TasticPlayer tastic, DailyStreak next) {
        List<CompletableFuture<?>> writes = List.of(
                coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.DAILY_LAST_CLAIM, next.lastClaim().toString()),
                coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.DAILY_STREAK, next.streak()),
                coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.DAILY_BEST_STREAK, next.bestStreak()),
                coreApi.playerSettingUpdateDispatcher().update(tastic, LobbySettings.DAILY_TOTAL, next.totalDays()));
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                logger.warning("Daily streak of " + tastic.minecraftUuid() + " could not be saved: " + throwable.getMessage());
            }
        });
    }

    /** One quiet line on join when today's reward is still waiting. */
    public void notifyOnJoin(Player player) {
        if (!claimable(player)) {
            return;
        }
        DailyStreak streak = state(player);
        messages.send(player, "lobby.daily.available", Map.of("streak", streak.streak()));
        sounds.play(player, "minecraft:entity.experience_orb.pickup", 0.7f, 1.2f);
    }
}
