package de.tasticgames.lobby.cookie;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import de.tasticgames.lobby.cookie.domain.order.OrderBoard;
import de.tasticgames.lobby.cookie.domain.order.OrderType;
import de.tasticgames.lobby.cookie.domain.order.ShiftOrder;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shift orders: three small goals that run next to the normal baking and pay a reward measured in the
 * player's own production. They give the minigame a second thing to do besides "click and buy" – a run can
 * be steered towards clicking, buying or hunting special cookies, and the board always shows a next goal.
 * <p>
 * The board lives in memory for as long as the player is online ({@link OrderBoard}); it is deliberately not
 * persisted, because an order is a goal for the current shift and the reward is paid out immediately.
 * Progress comes from the {@link CookieProgressListener} hooks the other cookie services already fire.
 */
public final class CookieOrderService implements CookieProgressListener {

    private final TasticCoreApi coreApi;
    private final CookieRuntimeService runtime;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Map<UUID, OrderBoard> boards = new ConcurrentHashMap<>();
    /** Claimable count the player was told about last, so one finished order produces one chat line. */
    private final Map<UUID, Integer> lastNotified = new ConcurrentHashMap<>();

    public CookieOrderService(TasticCoreApi coreApi, CookieRuntimeService runtime, LobbyMessages messages,
                              LobbySounds sounds, LobbyTelemetryService telemetry) {
        this.coreApi = Objects.requireNonNull(coreApi);
        this.runtime = Objects.requireNonNull(runtime);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    /** The board of a player, rolled from the current production the first time it is asked for. */
    public OrderBoard board(UUID player) {
        OrderBoard board = boards.computeIfAbsent(player, id -> new OrderBoard());
        CookieStats stats = stats(player);
        board.fill(stats == null ? BigDecimal.ZERO : stats.effectiveCps(),
                stats == null ? BigDecimal.ONE : stats.clickValue(), ThreadLocalRandom.current());
        return board;
    }

    public void forget(UUID player) {
        boards.remove(player);
        lastNotified.remove(player);
    }

    /**
     * Claims the finished order in {@code slot} and books the reward.
     *
     * @return the paid amount, or empty when the slot is not claimable (unfinished, already paid, hourly cap)
     */
    public Optional<CookieAmount> claim(Player player, int slot) {
        CookieSession session = runtime.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        OrderBoard board = board(player.getUniqueId());
        CookieStats stats = runtime.engine().compute(session.profile());
        ShiftOrder order = board.claim(slot, stats.effectiveCps(), stats.clickValue(),
                ThreadLocalRandom.current(), System.currentTimeMillis());
        if (order == null) {
            return Optional.empty();
        }
        CookieAmount reward = reward(order, stats);
        session.profile().earn(reward);
        session.touchDirty();
        lastNotified.put(player.getUniqueId(), board.claimable());
        telemetry.event("cookie.order_claimed", player.getUniqueId(),
                Map.of("type", order.type(), "reward", reward.toPlainString()));
        return Optional.of(reward);
    }

    /**
     * Reward of an order: {@code rewardCpsSeconds} seconds of production, at least what 30 clicks are worth
     * so the very first orders of a fresh run are not paid in crumbs.
     */
    public CookieAmount reward(ShiftOrder order, CookieStats stats) {
        BigDecimal fromProduction = stats.effectiveCps().multiply(BigDecimal.valueOf(order.rewardCpsSeconds()));
        BigDecimal fromClicks = stats.clickValue().multiply(BigDecimal.valueOf(30));
        return CookieAmount.of(fromProduction.max(fromClicks).setScale(0, RoundingMode.DOWN).max(BigDecimal.ONE));
    }

    /** Reward of an order for the player, used by the dialog to show what is waiting. */
    public Optional<CookieAmount> previewReward(UUID player, ShiftOrder order) {
        CookieStats stats = stats(player);
        return stats == null ? Optional.empty() : Optional.of(reward(order, stats));
    }

    // ------------------------------------------------------------------ progress hooks

    @Override
    public void onClick(UUID player, CookieAmount baked) {
        OrderBoard board = boards.get(player);
        if (board == null) {
            return;
        }
        board.advance(OrderType.CLICKS, BigDecimal.ONE);
        board.advance(OrderType.COOKIES, baked.toBigDecimal());
        notifyIfDone(player, board);
    }

    @Override
    public void onGeneratorsBought(UUID player, String generatorId, int count) {
        OrderBoard board = boards.get(player);
        if (board == null) {
            return;
        }
        board.advance(OrderType.GENERATORS, BigDecimal.valueOf(count));
        notifyIfDone(player, board);
    }

    @Override
    public void onUpgradeBought(UUID player, String upgradeId) {
        OrderBoard board = boards.get(player);
        if (board == null) {
            return;
        }
        board.advance(OrderType.UPGRADES, BigDecimal.ONE);
        notifyIfDone(player, board);
    }

    @Override
    public void onSpecialCookie(UUID player, SpecialCookieRarity rarity) {
        OrderBoard board = boards.get(player);
        if (board == null) {
            return;
        }
        board.advance(OrderType.SPECIALS, BigDecimal.ONE);
        notifyIfDone(player, board);
    }

    /** Production while the player is in the cookie area counts towards the "bake N cookies" orders. */
    public void onProduced(UUID player, BigDecimal amount) {
        OrderBoard board = boards.get(player);
        if (board == null || amount == null || amount.signum() <= 0) {
            return;
        }
        board.advance(OrderType.COOKIES, amount);
        notifyIfDone(player, board);
    }

    // ------------------------------------------------------------------ internals

    private CookieStats stats(UUID player) {
        return runtime.session(player).map(session -> runtime.engine().compute(session.profile())).orElse(null);
    }

    /** One quiet chat line per newly finished order – the board itself is opened by the player. */
    private void notifyIfDone(UUID playerId, OrderBoard board) {
        int claimable = board.claimable();
        if (claimable == 0) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !notificationsEnabled(playerId)) {
            return;
        }
        Integer previous = lastNotified.put(playerId, claimable);
        if (previous != null && previous >= claimable) {
            return;
        }
        messages.send(player, "cookie.orders.ready", Map.of("count", claimable));
        sounds.play(player, "minecraft:entity.experience_orb.pickup", 0.6f, 1.4f);
    }

    private boolean notificationsEnabled(UUID player) {
        return coreApi.playerManager().find(player)
                .map(p -> p.settings().get(LobbySettings.COOKIE_NOTIFICATIONS)).orElse(true);
    }
}
