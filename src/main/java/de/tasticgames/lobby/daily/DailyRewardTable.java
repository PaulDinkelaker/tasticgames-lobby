package de.tasticgames.lobby.daily;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a daily claim is worth, read from {@code config/daily.yml}.
 * <p>
 * Cookie rewards are expressed in seconds of the player's own production, exactly like the special cookies and
 * the shift orders: day 7 is worth the same relative amount at prestige 0 and at prestige 10, so the reward
 * never becomes pocket change and never breaks the economy. Crumbs and cosmetics are flat.
 */
public final class DailyRewardTable {

    /** What a day of the cycle hands out. */
    public enum Kind {
        /** Cookies worth {@code amount} seconds of the player's production. */
        COOKIES,
        /** Prestige crumbs (flat). */
        CRUMBS,
        /** Activates a special cookie of the configured rarity. */
        SPECIAL_COOKIE,
        /** Unlocks the cosmetic {@code id}. */
        COSMETIC
    }

    /**
     * @param kind   what is handed out
     * @param amount seconds of production (COOKIES), crumbs (CRUMBS) or ignored
     * @param id     rarity (SPECIAL_COOKIE) or cosmetic id (COSMETIC)
     */
    public record Reward(Kind kind, long amount, String id) {

        public Reward {
            Objects.requireNonNull(kind, "kind");
            id = id == null ? "" : id;
        }

        public static Reward cookies(long seconds) {
            return new Reward(Kind.COOKIES, seconds, "");
        }
    }

    private final boolean enabled;
    private final ZoneId zone;
    private final int cycleLength;
    private final double streakBonusPerDay;
    private final double maxStreakBonus;
    private final List<List<Reward>> cycle;
    private final Map<Integer, List<Reward>> milestones;

    private DailyRewardTable(boolean enabled, ZoneId zone, int cycleLength, double streakBonusPerDay, double maxStreakBonus,
                             List<List<Reward>> cycle, Map<Integer, List<Reward>> milestones) {
        this.enabled = enabled;
        this.zone = zone;
        this.cycleLength = cycleLength;
        this.streakBonusPerDay = streakBonusPerDay;
        this.maxStreakBonus = maxStreakBonus;
        this.cycle = List.copyOf(cycle);
        this.milestones = Map.copyOf(milestones);
    }

    public boolean enabled() {
        return enabled;
    }

    public ZoneId zone() {
        return zone;
    }

    public int cycleLength() {
        return cycleLength;
    }

    /** Rewards of day {@code cycleDay} (1..cycleLength). */
    public List<Reward> rewards(int cycleDay) {
        int index = Math.floorMod(cycleDay - 1, Math.max(1, cycle.size()));
        return cycle.isEmpty() ? List.of() : cycle.get(index);
    }

    /** Extra rewards handed out exactly when the streak reaches this length. */
    public List<Reward> milestone(int streak) {
        return milestones.getOrDefault(streak, List.of());
    }

    public Map<Integer, List<Reward>> milestones() {
        return milestones;
    }

    /**
     * Multiplier on cookie rewards for a streak: every day adds {@code streak-bonus-per-day}, capped at
     * {@code max-streak-bonus}. A 30 day streak is worth noticeably more than a first day, but never so much
     * that missing a day feels punishing.
     */
    public double streakMultiplier(int streak) {
        double bonus = Math.min(maxStreakBonus, Math.max(0, streak - 1) * streakBonusPerDay);
        return 1.0 + bonus;
    }

    public static DailyRewardTable load(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml, "yaml");
        boolean enabled = yaml.getBoolean("enabled", true);
        ZoneId zone = zone(yaml.getString("reset-zone", "Europe/Berlin"));
        double perDay = yaml.getDouble("streak-bonus-per-day", 0.03);
        double maxBonus = yaml.getDouble("max-streak-bonus", 1.0);

        List<List<Reward>> cycle = new ArrayList<>();
        ConfigurationSection days = yaml.getConfigurationSection("cycle");
        if (days != null) {
            for (String key : days.getKeys(false)) {
                cycle.add(rewards(days.getConfigurationSection(key)));
            }
        }
        if (cycle.isEmpty()) {
            cycle = defaultCycle();
        }

        Map<Integer, List<Reward>> milestones = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("milestones");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int streak = Integer.parseInt(key.trim());
                    if (streak > 0) {
                        milestones.put(streak, rewards(section.getConfigurationSection(key)));
                    }
                } catch (NumberFormatException ignored) {
                    // a milestone key that is not a number is simply not a milestone
                }
            }
        }
        return new DailyRewardTable(enabled, zone, cycle.size(), perDay, maxBonus, cycle, milestones);
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id == null || id.isBlank() ? "Europe/Berlin" : id.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Berlin");
        }
    }

    private static List<Reward> rewards(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }
        List<Reward> rewards = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("rewards")) {
            Object type = entry.get("type");
            if (type == null) {
                continue;
            }
            Kind kind;
            try {
                kind = Kind.valueOf(String.valueOf(type).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue; // unknown reward type: skipped instead of breaking the whole table
            }
            long amount = entry.get("amount") instanceof Number number ? number.longValue() : 0L;
            String id = entry.get("id") == null ? "" : String.valueOf(entry.get("id"));
            rewards.add(new Reward(kind, amount, id));
        }
        return List.copyOf(rewards);
    }

    /** Used when the file has no cycle at all, so the feature still works out of the box. */
    private static List<List<Reward>> defaultCycle() {
        return List.of(
                List.of(Reward.cookies(60)),
                List.of(Reward.cookies(90)),
                List.of(Reward.cookies(120), new Reward(Kind.CRUMBS, 1, "")),
                List.of(Reward.cookies(180)),
                List.of(Reward.cookies(240), new Reward(Kind.SPECIAL_COOKIE, 0, "GOLDEN")),
                List.of(Reward.cookies(300)),
                List.of(Reward.cookies(600), new Reward(Kind.CRUMBS, 3, "")));
    }

    /** Cosmetic id of a milestone, used by the dialog to show what is coming. */
    public Optional<String> milestoneCosmetic(int streak) {
        return milestone(streak).stream()
                .filter(reward -> reward.kind() == Kind.COSMETIC)
                .map(Reward::id)
                .findFirst();
    }
}
