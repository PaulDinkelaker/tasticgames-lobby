package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieLeaderboardEntryResponse;
import de.tasticgames.client.dto.lobby.CookieLeaderboardTypeResponse;
import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.PrestigeTreeNode;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeDefinition;
import de.tasticgames.lobby.cookie.domain.catalog.ZoneDefinition;
import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.BuyMode;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.cookie.domain.model.NodePurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.OfflineResult;
import de.tasticgames.lobby.cookie.domain.model.PrestigeCheck;
import de.tasticgames.lobby.cookie.domain.model.PrestigePlan;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.ZoneAccess;
import de.tasticgames.lobby.dialog.DialogSupport;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Cookie Clicker UI: overview, generator shop (1/10/100/MAX), upgrades, prestige (confirmation),
 * prestige tree, stats, leaderboards, fast travel, offline claim.
 */
public final class CookieDialogService {

    private final CookieRuntimeService runtime;
    private final CookieWorldService world;
    private final CookieLeaderboardService leaderboards;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;
    private final CookieNumberFormatter formatter = new CookieNumberFormatter();
    private final Map<UUID, BuyMode> buyModes = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> prestiging = ConcurrentHashMap.newKeySet();

    public CookieDialogService(CookieRuntimeService runtime, CookieWorldService world, CookieLeaderboardService leaderboards, LobbyMessages messages,
                               DialogSupport dialogs, MainThread mainThread, LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.runtime = Objects.requireNonNull(runtime);
        this.world = Objects.requireNonNull(world);
        this.leaderboards = Objects.requireNonNull(leaderboards);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    private CookieEngine engine() {
        return runtime.engine();
    }

    private Locale localeOf(Player player) {
        return messages.languageOf(player) == SupportedLanguage.GERMAN ? Locale.GERMAN : Locale.ENGLISH;
    }

    private String fmt(Player player, CookieAmount amount) {
        return formatter.format(amount, localeOf(player));
    }

    private String fmt(Player player, BigDecimal value) {
        return formatter.formatRate(value, localeOf(player));
    }

    /** Loads the session (showing "loading"/"unavailable") and continues on the main thread. */
    private void withSession(Player player, Consumer<CookieSession> consumer) {
        CookieSession existing = runtime.session(player.getUniqueId()).orElse(null);
        if (existing != null) {
            consumer.accept(existing);
            return;
        }
        if (!runtime.available()) {
            messages.send(player, "cookie.unavailable");
            return;
        }
        messages.send(player, "cookie.loading");
        runtime.load(player.getUniqueId()).whenComplete((session, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            if (throwable != null) {
                logger.warning("Cookie profile load failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                messages.send(player, "cookie.unavailable");
                sounds.error(player);
                return;
            }
            consumer.accept(session);
        }));
    }

    // ------------------------------------------------------------------ overview

    public void openOverview(Player player) {
        withSession(player, session -> {
            telemetry.event("cookie.overview_open", player.getUniqueId(), Map.of());
            SupportedLanguage lang = messages.languageOf(player);
            CookieProfile profile = session.profile();
            CookieStats stats = engine().compute(profile);
            List<Component> body = List.of(
                    messages.get(lang, "cookie.overview.description", Map.of()),
                    messages.get(lang, "cookie.overview.stats", Map.of("cookies", fmt(player, profile.cookies()), "cps", fmt(player, stats.effectiveCps()),
                            "prestige", profile.prestigeLevel(), "lifetime", fmt(player, profile.lifetimeCookies()))));
            List<ActionButton> buttons = new ArrayList<>();
            boolean inWorld = world.isCookieWorld(player.getWorld());
            buttons.add(dialogs.button(player, messages.get(lang, inWorld ? "cookie.overview.continue" : "cookie.overview.enter", Map.of()), null,
                    DialogSupport.BUTTON_WIDTH, p -> { if (!world.isCookieWorld(p.getWorld())) world.enter(p); }));
            buttons.add(dialogs.button(player, messages.get(lang, "cookie.overview.shop", Map.of()), this::openShop));
            buttons.add(dialogs.button(player, messages.get(lang, "cookie.overview.upgrades", Map.of()), this::openUpgrades));
            buttons.add(dialogs.button(player, messages.get(lang, "cookie.overview.prestige", Map.of()), this::openPrestige));
            buttons.add(dialogs.button(player, messages.get(lang, "cookie.overview.stats_button", Map.of()), this::openStats));
            buttons.add(dialogs.button(player, messages.get(lang, "cookie.overview.leaderboard", Map.of()), p -> openLeaderboard(p, CookieLeaderboardTypeResponse.PRESTIGE)));
            if (inWorld) {
                buttons.add(dialogs.button(player, messages.get(lang, "cookie.overview.exit", Map.of()), world::leave));
            }
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.overview.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
            maybeOfferOffline(player, session);
        });
    }

    private void maybeOfferOffline(Player player, CookieSession session) {
        if (session.offlineDialogShown()) return;
        session.offlineDialogShown(true);
        runtime.previewOffline(session).ifPresent(result -> mainThread.later(5L, () -> openOfflineClaim(player, session, result)));
    }

    public void openOfflineClaim(Player player, CookieSession session, OfflineResult result) {
        SupportedLanguage lang = messages.languageOf(player);
        Duration d = Duration.ofSeconds(result.seconds());
        String duration = d.toHours() + "h " + (d.toMinutesPart()) + "m";
        List<Component> body = List.of(messages.get(lang, "cookie.offline.body", Map.of("duration", duration, "cookies", fmt(player, result.cookies()),
                "rate", Math.round(result.efficiency() * 100))));
        ActionButton claim = dialogs.button(player, messages.get(lang, "cookie.offline.claim", Map.of()), null, DialogSupport.BUTTON_WIDTH, p ->
                runtime.claimOffline(session, result).whenComplete((response, throwable) -> mainThread.run(() -> {
                    if (!p.isOnline()) return;
                    if (throwable != null) {
                        logger.warning("Offline claim failed for " + p.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                        messages.send(p, "cookie.save_failed");
                        return;
                    }
                    if (response.applied()) {
                        messages.send(p, "cookie.offline.claimed", Map.of("cookies", fmt(p, result.cookies())));
                        sounds.success(p);
                    }
                })));
        dialogs.show(player, dialogs.notice(messages.get(lang, "cookie.offline.title", Map.of()), body, claim));
    }

    // ------------------------------------------------------------------ shop

    public void openShop(Player player) {
        withSession(player, session -> {
            SupportedLanguage lang = messages.languageOf(player);
            CookieProfile profile = session.profile();
            CookieStats stats = engine().compute(profile);
            BuyMode mode = buyModes.getOrDefault(player.getUniqueId(), BuyMode.ONE);
            List<Component> body = List.of(
                    messages.get(lang, "cookie.shop.description", Map.of("cookies", fmt(player, profile.cookies()), "cps", fmt(player, stats.effectiveCps()))),
                    messages.get(lang, "cookie.shop.mode", Map.of("mode", messages.raw(lang, "cookie.shop.mode_" + modeKey(mode)))));
            List<ActionButton> buttons = new ArrayList<>();
            for (BuyMode m : BuyMode.values()) {
                if (m == mode) continue;
                buttons.add(dialogs.button(player, Component.text(messages.raw(lang, "cookie.shop.mode_" + modeKey(m)), NamedTextColor.GRAY), null, 100, p -> {
                    buyModes.put(p.getUniqueId(), m);
                    openShop(p);
                }));
            }
            for (GeneratorDefinition generator : engine().catalog().generators()) {
                String name = generatorName(player, generator.id());
                int owned = profile.generatorCount(generator.id());
                if (!engine().isGeneratorUnlocked(profile, generator)) {
                    buttons.add(dialogs.button(player, messages.get(lang, "cookie.shop.entry_locked", Map.of("name", name, "prestige", generator.unlockPrestige())), null,
                            DialogSupport.WIDE_BUTTON_WIDTH, p -> sounds.error(p)));
                    continue;
                }
                int count = mode == BuyMode.MAX ? Math.max(1, engine().maxAffordable(generator, owned, profile.cookies()).count()) : mode.fixedCount();
                CookieAmount cost = engine().costFor(generator, owned, count);
                BigDecimal cpsEach = stats.contributions().stream().filter(c -> c.generatorId().equals(generator.id())).map(c -> c.cpsEach()).findFirst()
                        .orElse(generator.baseCps().multiply(BigDecimal.valueOf(stats.prestigeMultiplier())));
                Component label = messages.get(lang, "cookie.shop.entry", Map.of("name", name, "owned", owned, "cost", fmt(player, cost), "cps", fmt(player, cpsEach)))
                        .color(profile.canAfford(cost) ? NamedTextColor.WHITE : NamedTextColor.GRAY);
                buttons.add(dialogs.button(player, label, null, DialogSupport.WIDE_BUTTON_WIDTH, p -> buy(p, session, generator, mode)));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.shop.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    private void buy(Player player, CookieSession session, GeneratorDefinition generator, BuyMode mode) {
        PurchaseResult result = engine().buy(session.profile(), generator.id(), mode);
        if (result.success()) {
            session.touchDirty();
            sounds.success(player);
            messages.send(player, "cookie.shop.bought", Map.of("count", result.count(), "name", generatorName(player, generator.id()), "cost", fmt(player, result.totalCost())));
            telemetry.event("cookie.generator_bought", player.getUniqueId(), Map.of("generator", generator.id(), "count", result.count(), "cost", result.totalCost().toPlainString()));
            engine().evaluateAchievements(session.profile()).forEach(a -> messages.send(player, "cookie.achievement.unlocked", Map.of("name", world.achievementName(player, a))));
        } else {
            sounds.error(player);
            messages.send(player, "cookie.shop.cannot_afford");
        }
        openShop(player);
    }

    private static String modeKey(BuyMode mode) {
        return switch (mode) {
            case ONE -> "1";
            case TEN -> "10";
            case HUNDRED -> "100";
            case MAX -> "max";
        };
    }

    // ------------------------------------------------------------------ upgrades

    public void openUpgrades(Player player) {
        withSession(player, session -> {
            SupportedLanguage lang = messages.languageOf(player);
            CookieProfile profile = session.profile();
            List<ActionButton> buttons = new ArrayList<>();
            List<UpgradeDefinition> available = engine().availableUpgrades(profile);
            for (UpgradeDefinition upgrade : available) {
                String name = upgradeName(player, upgrade.id());
                Component label = messages.get(lang, "cookie.upgrades.entry", Map.of("name", name, "cost", fmt(player, upgrade.cost()), "desc", describe(upgrade)))
                        .color(profile.canAfford(upgrade.cost()) ? NamedTextColor.WHITE : NamedTextColor.GRAY);
                buttons.add(dialogs.button(player, label, null, DialogSupport.WIDE_BUTTON_WIDTH, p -> {
                    PurchaseResult result = engine().buyUpgrade(session.profile(), upgrade.id());
                    if (result.success()) {
                        session.touchDirty();
                        sounds.success(p);
                        messages.send(p, "cookie.upgrades.bought", Map.of("name", name));
                        telemetry.event("cookie.upgrade_bought", p.getUniqueId(), Map.of("upgrade", upgrade.id()));
                    } else {
                        sounds.error(p);
                        messages.send(p, "cookie.shop.cannot_afford");
                    }
                    openUpgrades(p);
                }));
            }
            for (String owned : profile.upgrades()) {
                buttons.add(dialogs.button(player, messages.get(lang, "cookie.upgrades.entry_owned", Map.of("name", upgradeName(player, owned))), null,
                        DialogSupport.WIDE_BUTTON_WIDTH, p -> { }));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openOverview));
            List<Component> body = available.isEmpty() ? List.of(messages.get(lang, "cookie.upgrades.empty", Map.of())) : List.of();
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.upgrades.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    private String describe(UpgradeDefinition upgrade) {
        var e = upgrade.effect();
        return switch (e.type()) {
            case CLICK_POWER_MULTIPLIER -> "click x" + trim(e.value());
            case CLICK_POWER_ADD_CPS_PERCENT -> "click +" + trim(e.value()) + "% CPS";
            case GLOBAL_CPS_MULTIPLIER -> "CPS x" + trim(e.value());
            case GENERATOR_MULTIPLIER -> e.generatorId() + " x" + trim(e.value());
            case GOLDEN_COOKIE_FREQUENCY -> "golden chance x" + trim(e.value());
            case GOLDEN_COOKIE_VALUE -> "golden value x" + trim(e.value());
            case COMBO_DURATION -> "combo x" + trim(e.value());
            case OFFLINE_EFFICIENCY -> "offline +" + trim(e.value() * 100) + "%";
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    // ------------------------------------------------------------------ prestige

    public void openPrestige(Player player) {
        withSession(player, session -> {
            SupportedLanguage lang = messages.languageOf(player);
            CookieProfile profile = session.profile();
            PrestigeCheck check = engine().canPrestige(profile);
            PrestigeDefinition current = engine().catalog().requirePrestige(profile.prestigeLevel());
            List<Component> body = new ArrayList<>();
            body.add(messages.get(lang, "cookie.prestige.current", Map.of("current", profile.prestigeLevel(), "name", prestigeName(player, current))));
            List<ActionButton> buttons = new ArrayList<>();
            if (check.atMaxLevel()) {
                body.add(messages.get(lang, "cookie.prestige.max", Map.of()));
            } else {
                PrestigeDefinition next = engine().catalog().requirePrestige(check.nextLevel());
                double progress = profile.lifetimeCookies().toBigDecimal().divide(next.requiredLifetimeCookies(), 6, java.math.RoundingMode.DOWN).doubleValue() * 100;
                body.add(messages.get(lang, "cookie.prestige.next", Map.of("next", check.nextLevel(), "requirement", fmt(player, check.requiredLifetime()),
                        "progress", String.format(Locale.ROOT, "%.1f%%", Math.min(100, progress)))));
                body.add(messages.get(lang, "cookie.prestige.reset", Map.of()));
                body.add(messages.get(lang, "cookie.prestige.keep", Map.of()));
                if (check.eligible()) {
                    PrestigePlan plan = engine().planPrestige(profile);
                    body.add(messages.get(lang, "cookie.prestige.reward", Map.of("multiplier", trim(plan.newMultiplier()), "crumbs", plan.crumbsGained(),
                            "unlocks", String.join(", ", unlockNames(player, plan)))));
                    buttons.add(dialogs.button(player, messages.get(lang, "cookie.prestige.confirm", Map.of()), null, DialogSupport.BUTTON_WIDTH, p -> confirmPrestige(p, session)));
                } else {
                    body.add(messages.get(lang, "cookie.prestige.not_ready", Map.of("missing", fmt(player, check.missing()))));
                }
            }
            buttons.add(dialogs.button(player, messages.get(lang, "cookie.prestige.tree", Map.of()), this::openTree));
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.prestige.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    private List<String> unlockNames(Player player, PrestigePlan plan) {
        List<String> names = new ArrayList<>();
        if (plan.unlockedZoneId() != null) names.add(world.zoneName(player, plan.unlockedZoneId()));
        plan.unlockedGeneratorIds().forEach(g -> names.add(generatorName(player, g)));
        plan.rewardCosmeticIds().forEach(names::add);
        return names;
    }

    private void confirmPrestige(Player player, CookieSession session) {
        SupportedLanguage lang = messages.languageOf(player);
        PrestigePlan plan = engine().planPrestige(session.profile());
        if (!plan.eligible()) {
            openPrestige(player);
            return;
        }
        List<Component> body = List.of(
                messages.get(lang, "cookie.prestige.next", Map.of("next", plan.toLevel(), "requirement", fmt(player, engine().canPrestige(session.profile()).requiredLifetime()), "progress", "100%")),
                messages.get(lang, "cookie.prestige.reset", Map.of()),
                messages.get(lang, "cookie.prestige.keep", Map.of()),
                messages.get(lang, "cookie.prestige.reward", Map.of("multiplier", trim(plan.newMultiplier()), "crumbs", plan.crumbsGained(), "unlocks", String.join(", ", unlockNames(player, plan)))));
        ActionButton yes = dialogs.button(player, messages.get(lang, "cookie.prestige.confirm", Map.of()), null, DialogSupport.BUTTON_WIDTH, p -> {
            if (!prestiging.add(p.getUniqueId())) {
                return; // double click guard
            }
            runtime.prestige(session).whenComplete((result, throwable) -> mainThread.run(() -> {
                prestiging.remove(p.getUniqueId());
                if (!p.isOnline()) return;
                if (throwable != null || !result.applied()) {
                    logger.warning("Prestige failed for " + p.getName() + ": " + (throwable != null ? LobbyThrowables.rootMessage(throwable) : result.outcome()));
                    messages.send(p, "cookie.prestige.failed");
                    sounds.error(p);
                    return;
                }
                messages.send(p, "cookie.prestige.done", Map.of("level", result.plan().toLevel(), "multiplier", trim(result.plan().newMultiplier()), "crumbs", result.plan().crumbsGained()));
                sounds.play(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
                openPrestige(p);
            }));
        });
        ActionButton no = dialogs.button(player, messages.get(lang, "common.cancel", Map.of()), this::openPrestige);
        dialogs.show(player, dialogs.confirm(messages.get(lang, "cookie.prestige.title", Map.of()), body, yes, no));
    }

    public void openTree(Player player) {
        withSession(player, session -> {
            SupportedLanguage lang = messages.languageOf(player);
            CookieProfile profile = session.profile();
            List<Component> body = List.of(messages.get(lang, "cookie.tree.crumbs", Map.of("crumbs", profile.crumbs())));
            List<ActionButton> buttons = new ArrayList<>();
            for (PrestigeTreeNode node : engine().catalog().prestigeTree()) {
                int level = profile.prestigeUpgradeLevel(node.id());
                long cost = engine().nodeCost(profile, node);
                Component label = messages.get(lang, "cookie.tree.entry", Map.of("name", nodeName(player, node), "level", level, "max", node.maxLevel(), "cost", level >= node.maxLevel() ? "-" : cost));
                buttons.add(dialogs.button(player, label, null, DialogSupport.WIDE_BUTTON_WIDTH, p -> {
                    NodePurchaseResult result = engine().buyPrestigeNode(session.profile(), node.id());
                    if (result.success()) {
                        session.touchDirty();
                        sounds.success(p);
                        messages.send(p, "cookie.tree.bought", Map.of("name", nodeName(p, node)));
                        telemetry.event("cookie.tree_node_bought", p.getUniqueId(), Map.of("node", node.id(), "level", result.newLevel()));
                    } else {
                        sounds.error(p);
                        messages.send(p, "cookie.tree.cannot_afford");
                    }
                    openTree(p);
                }));
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openPrestige));
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.tree.title", Map.of()), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    // ------------------------------------------------------------------ stats / leaderboard / travel

    public void openStats(Player player) {
        withSession(player, session -> {
            SupportedLanguage lang = messages.languageOf(player);
            CookieProfile profile = session.profile();
            CookieStats stats = engine().compute(profile);
            List<Component> body = new ArrayList<>();
            body.add(line(lang, "Cookies", fmt(player, profile.cookies())));
            body.add(line(lang, "Lifetime", fmt(player, profile.lifetimeCookies())));
            body.add(line(lang, "CPS", fmt(player, stats.effectiveCps())));
            body.add(line(lang, "Click", formatter.format(stats.clickValue(), localeOf(player))));
            body.add(line(lang, "Prestige", profile.prestigeLevel() + " (x" + trim(stats.prestigeMultiplier()) + ")"));
            body.add(line(lang, "Crumbs", profile.crumbs()));
            body.add(line(lang, "Clicks", profile.totalClicks()));
            body.add(line(lang, "Golden cookies", profile.goldenCookiesClicked()));
            body.add(line(lang, "Highest combo", profile.highestCombo()));
            body.add(line(lang, "Generators", profile.totalGenerators()));
            body.add(line(lang, "Achievements", profile.achievements().size() + "/" + engine().catalog().achievements().size()));
            body.add(line(lang, "Zones", profile.discoveredZones().size() + "/" + engine().catalog().zones().size()));
            body.add(line(lang, "Playtime", Duration.ofSeconds(profile.playtimeSeconds()).toHours() + "h"));
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.stats.title", Map.of()), body,
                    List.of(dialogs.button(player, messages.get(lang, "common.back", Map.of()), this::openOverview)),
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    private Component line(SupportedLanguage lang, String label, Object value) {
        return messages.get(lang, "cookie.stats.line", Map.of("label", label, "value", value));
    }

    public void openLeaderboard(Player player, CookieLeaderboardTypeResponse type) {
        SupportedLanguage lang = messages.languageOf(player);
        leaderboards.get(type, 10).whenComplete((response, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            List<Component> body = new ArrayList<>();
            if (throwable != null) {
                body.add(messages.get(lang, "cookie.unavailable", Map.of()));
            } else if (response.entries().isEmpty()) {
                body.add(messages.get(lang, "cookie.leaderboard.empty", Map.of()));
            } else {
                for (CookieLeaderboardEntryResponse entry : response.entries()) {
                    String value = type == CookieLeaderboardTypeResponse.PRESTIGE ? entry.value() : formatter.format(CookieAmount.parse(entry.value()), localeOf(player));
                    body.add(messages.get(lang, "cookie.leaderboard.entry", Map.of("rank", entry.rank(), "player", entry.name() == null ? "?" : entry.name(), "value", value)));
                }
            }
            List<ActionButton> buttons = new ArrayList<>();
            for (CookieLeaderboardTypeResponse other : CookieLeaderboardTypeResponse.values()) {
                if (other != type) {
                    buttons.add(dialogs.button(player, Component.text(other.name().replace('_', ' '), NamedTextColor.YELLOW), null, 150, p -> openLeaderboard(p, other)));
                }
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, 150, this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.leaderboard.title", Map.of("type", type.name().replace('_', ' '))), body, buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
        }));
    }

    public void openTravel(Player player) {
        withSession(player, session -> {
            SupportedLanguage lang = messages.languageOf(player);
            List<ActionButton> buttons = new ArrayList<>();
            for (ZoneDefinition zone : engine().catalog().zones()) {
                ZoneAccess access = engine().canEnter(session.profile(), zone.id());
                boolean configured = world.configuration().zones().containsKey(zone.id());
                if (access.allowed() && configured) {
                    buttons.add(dialogs.button(player, messages.get(lang, "cookie.travel.entry", Map.of("zone", world.zoneName(player, zone.id()))), null,
                            DialogSupport.WIDE_BUTTON_WIDTH, p -> world.travel(p, zone.id())));
                } else {
                    buttons.add(dialogs.button(player, messages.get(lang, "cookie.travel.entry_locked", Map.of("zone", world.zoneName(player, zone.id()), "prestige", zone.minPrestige())), null,
                            DialogSupport.WIDE_BUTTON_WIDTH, p -> sounds.error(p)));
                }
            }
            buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openOverview));
            dialogs.show(player, dialogs.menu(messages.get(lang, "cookie.travel.title", Map.of()), List.of(), buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
        });
    }

    // ------------------------------------------------------------------ names

    public String generatorName(Player player, String id) {
        String key = "cookie.generator." + id + ".name";
        return messages.contains(key) ? messages.raw(messages.languageOf(player), key) : id.replace('_', ' ');
    }

    public String upgradeName(Player player, String id) {
        String key = "cookie.upgrade." + id;
        return messages.contains(key) ? messages.raw(messages.languageOf(player), key) : id.replace('_', ' ');
    }

    public String prestigeName(Player player, PrestigeDefinition definition) {
        return messages.contains(definition.nameKey()) ? messages.raw(messages.languageOf(player), definition.nameKey()) : definition.displayName();
    }

    public String nodeName(Player player, PrestigeTreeNode node) {
        return messages.contains(node.nameKey()) ? messages.raw(messages.languageOf(player), node.nameKey()) : node.id().replace('_', ' ');
    }
}
