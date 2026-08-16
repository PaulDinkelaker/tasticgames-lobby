package de.tasticgames.lobby.cookie.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable player aggregate. Single-threaded use; the hosting plugin synchronizes access.
 * <p>
 * Persistent fields are everything exposed through {@link #snapshot()} except the transient
 * runtime state (active buffs, combo state, click history), which is not stored.
 */
public final class CookieProfile {

    private final UUID playerId;
    private CookieAmount cookies;
    private CookieAmount lifetimeCookies;
    private int prestigeLevel;
    private long crumbs;
    private long crumbsEarnedTotal;
    private long totalClicks;
    private long goldenCookiesClicked;
    private long playtimeSeconds;
    private int highestCombo;
    private final Map<String, Integer> generators = new HashMap<>();
    private final Set<String> upgrades = new HashSet<>();
    private final Map<String, Integer> prestigeUpgrades = new HashMap<>();
    private final Set<String> achievements = new HashSet<>();
    private final Set<String> discoveredZones = new HashSet<>();
    private Instant lastActiveAt;
    private Instant offlineClaimedUntil;
    private long version;
    private boolean dirty;

    // ---- transient runtime state (not persisted)
    private final List<ActiveBuff> activeBuffs = new ArrayList<>();
    private final ClickHistory clickHistory = new ClickHistory();
    private int comboStage;
    private int comboRapidClicks;
    private long lastClickEpochMillis = Long.MIN_VALUE;

    private CookieProfile(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.cookies = CookieAmount.ZERO;
        this.lifetimeCookies = CookieAmount.ZERO;
    }

    /** New profile with nothing owned. Not dirty until modified. */
    public static CookieProfile fresh(UUID playerId) {
        CookieProfile p = new CookieProfile(playerId);
        p.lastActiveAt = null;
        p.offlineClaimedUntil = null;
        return p;
    }

    /** New profile with nothing owned, {@code lastActiveAt} set to {@code now}. */
    public static CookieProfile fresh(UUID playerId, Instant now) {
        CookieProfile p = fresh(playerId);
        p.lastActiveAt = now;
        return p;
    }

    /** Builder for loading from persistence. */
    public static Builder builder(UUID playerId) {
        return new Builder(playerId);
    }

    /** Full-args constructor equivalent for persistence loading. */
    public static CookieProfile load(UUID playerId,
                                     CookieAmount cookies,
                                     CookieAmount lifetimeCookies,
                                     int prestigeLevel,
                                     long crumbs,
                                     long crumbsEarnedTotal,
                                     long totalClicks,
                                     long goldenCookiesClicked,
                                     long playtimeSeconds,
                                     int highestCombo,
                                     Map<String, Integer> generators,
                                     Set<String> upgrades,
                                     Map<String, Integer> prestigeUpgrades,
                                     Set<String> achievements,
                                     Set<String> discoveredZones,
                                     Instant lastActiveAt,
                                     Instant offlineClaimedUntil,
                                     long version) {
        CookieProfile p = new CookieProfile(playerId);
        p.cookies = Objects.requireNonNull(cookies, "cookies");
        p.lifetimeCookies = Objects.requireNonNull(lifetimeCookies, "lifetimeCookies");
        if (prestigeLevel < 0) throw new IllegalArgumentException("prestigeLevel must be >= 0");
        p.prestigeLevel = prestigeLevel;
        if (crumbs < 0 || crumbsEarnedTotal < 0) throw new IllegalArgumentException("crumbs must be >= 0");
        p.crumbs = crumbs;
        p.crumbsEarnedTotal = crumbsEarnedTotal;
        p.totalClicks = Math.max(0, totalClicks);
        p.goldenCookiesClicked = Math.max(0, goldenCookiesClicked);
        p.playtimeSeconds = Math.max(0, playtimeSeconds);
        p.highestCombo = Math.max(0, highestCombo);
        if (generators != null) {
            generators.forEach((id, count) -> {
                if (count != null && count > 0) p.generators.put(id, count);
            });
        }
        if (upgrades != null) p.upgrades.addAll(upgrades);
        if (prestigeUpgrades != null) {
            prestigeUpgrades.forEach((id, lvl) -> {
                if (lvl != null && lvl > 0) p.prestigeUpgrades.put(id, lvl);
            });
        }
        if (achievements != null) p.achievements.addAll(achievements);
        if (discoveredZones != null) p.discoveredZones.addAll(discoveredZones);
        p.lastActiveAt = lastActiveAt;
        p.offlineClaimedUntil = offlineClaimedUntil;
        p.version = version;
        p.dirty = false;
        return p;
    }

    // ------------------------------------------------------------------ identity / dirty

    public UUID playerId() { return playerId; }

    public boolean isDirty() { return dirty; }

    public void markDirty() { this.dirty = true; }

    /** Called by the persistence layer after a successful save (use {@link #bumpVersion()} for optimistic locking). */
    public void markClean() {
        this.dirty = false;
    }

    public long version() { return version; }

    public void setVersion(long version) { this.version = version; }

    /** Increments the version (persistence optimistic locking) and returns the new value. */
    public long bumpVersion() { return ++version; }

    // ------------------------------------------------------------------ cookies

    public CookieAmount cookies() { return cookies; }

    public CookieAmount lifetimeCookies() { return lifetimeCookies; }

    /** Adds earned cookies to the bank and to the lifetime total. */
    public void earn(CookieAmount amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) return;
        cookies = cookies.plus(amount);
        lifetimeCookies = lifetimeCookies.plus(amount);
        dirty = true;
    }

    /** Removes cookies from the bank (must be affordable). */
    public void spend(CookieAmount amount) {
        Objects.requireNonNull(amount, "amount");
        cookies = cookies.minus(amount);
        dirty = true;
    }

    public boolean canAfford(CookieAmount amount) {
        return cookies.compareTo(amount) >= 0;
    }

    /** Sets the bank directly (prestige reset, admin). */
    public void setCookies(CookieAmount cookies) {
        this.cookies = Objects.requireNonNull(cookies, "cookies");
        dirty = true;
    }

    /** Sets lifetime directly (admin / migration only). */
    public void setLifetimeCookies(CookieAmount lifetimeCookies) {
        this.lifetimeCookies = Objects.requireNonNull(lifetimeCookies, "lifetimeCookies");
        dirty = true;
    }

    // ------------------------------------------------------------------ prestige / crumbs

    public int prestigeLevel() { return prestigeLevel; }

    public void setPrestigeLevel(int prestigeLevel) {
        if (prestigeLevel < 0) throw new IllegalArgumentException("prestigeLevel must be >= 0");
        this.prestigeLevel = prestigeLevel;
        dirty = true;
    }

    public long crumbs() { return crumbs; }

    public long crumbsEarnedTotal() { return crumbsEarnedTotal; }

    public void addCrumbs(long gained) {
        if (gained < 0) throw new IllegalArgumentException("gained must be >= 0");
        if (gained == 0) return;
        crumbs = Math.addExact(crumbs, gained);
        crumbsEarnedTotal = Math.addExact(crumbsEarnedTotal, gained);
        dirty = true;
    }

    public void spendCrumbs(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        if (amount > crumbs) throw new ArithmeticException("Insufficient crumbs");
        crumbs -= amount;
        dirty = true;
    }

    // ------------------------------------------------------------------ stats

    public long totalClicks() { return totalClicks; }

    public void incrementClicks() {
        totalClicks++;
        dirty = true;
    }

    public void addClicks(long count) {
        if (count < 0) throw new IllegalArgumentException("count must be >= 0");
        totalClicks += count;
        dirty = true;
    }

    public long goldenCookiesClicked() { return goldenCookiesClicked; }

    public void incrementGoldenCookiesClicked() {
        goldenCookiesClicked++;
        dirty = true;
    }

    public long playtimeSeconds() { return playtimeSeconds; }

    public void addPlaytimeSeconds(long seconds) {
        if (seconds < 0) throw new IllegalArgumentException("seconds must be >= 0");
        if (seconds == 0) return;
        playtimeSeconds += seconds;
        dirty = true;
    }

    public int highestCombo() { return highestCombo; }

    public void recordCombo(int stage) {
        if (stage > highestCombo) {
            highestCombo = stage;
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ generators / upgrades

    public Map<String, Integer> generators() { return Collections.unmodifiableMap(generators); }

    public int generatorCount(String generatorId) {
        return generators.getOrDefault(generatorId, 0);
    }

    public long totalGenerators() {
        long total = 0;
        for (int v : generators.values()) total += v;
        return total;
    }

    public void addGenerators(String generatorId, int count) {
        Objects.requireNonNull(generatorId, "generatorId");
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        generators.merge(generatorId, count, Math::addExact);
        dirty = true;
    }

    public void setGenerators(Map<String, Integer> newGenerators) {
        generators.clear();
        newGenerators.forEach((id, count) -> {
            if (count != null && count > 0) generators.put(id, count);
        });
        dirty = true;
    }

    public Set<String> upgrades() { return Collections.unmodifiableSet(upgrades); }

    public boolean hasUpgrade(String upgradeId) { return upgrades.contains(upgradeId); }

    public boolean addUpgrade(String upgradeId) {
        boolean added = upgrades.add(Objects.requireNonNull(upgradeId, "upgradeId"));
        if (added) dirty = true;
        return added;
    }

    public void clearUpgrades() {
        if (!upgrades.isEmpty()) {
            upgrades.clear();
            dirty = true;
        }
    }

    public Map<String, Integer> prestigeUpgrades() { return Collections.unmodifiableMap(prestigeUpgrades); }

    public int prestigeUpgradeLevel(String nodeId) {
        return prestigeUpgrades.getOrDefault(nodeId, 0);
    }

    public void setPrestigeUpgradeLevel(String nodeId, int level) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (level < 0) throw new IllegalArgumentException("level must be >= 0");
        if (level == 0) prestigeUpgrades.remove(nodeId);
        else prestigeUpgrades.put(nodeId, level);
        dirty = true;
    }

    // ------------------------------------------------------------------ achievements / zones

    public Set<String> achievements() { return Collections.unmodifiableSet(achievements); }

    public boolean addAchievement(String id) {
        boolean added = achievements.add(Objects.requireNonNull(id, "id"));
        if (added) dirty = true;
        return added;
    }

    public Set<String> discoveredZones() { return Collections.unmodifiableSet(discoveredZones); }

    public boolean hasDiscoveredZone(String zoneId) { return discoveredZones.contains(zoneId); }

    public boolean addDiscoveredZone(String zoneId) {
        boolean added = discoveredZones.add(Objects.requireNonNull(zoneId, "zoneId"));
        if (added) dirty = true;
        return added;
    }

    // ------------------------------------------------------------------ timestamps

    public Instant lastActiveAt() { return lastActiveAt; }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
        dirty = true;
    }

    public Instant offlineClaimedUntil() { return offlineClaimedUntil; }

    public void setOfflineClaimedUntil(Instant offlineClaimedUntil) {
        this.offlineClaimedUntil = offlineClaimedUntil;
        dirty = true;
    }

    // ------------------------------------------------------------------ transient runtime state

    /** Live view of the active buffs (mutable, transient). */
    public List<ActiveBuff> activeBuffs() { return activeBuffs; }

    public void addBuff(ActiveBuff buff) {
        activeBuffs.add(Objects.requireNonNull(buff, "buff"));
    }

    public void clearBuffs() {
        activeBuffs.clear();
    }

    public ClickHistory clickHistory() { return clickHistory; }

    public int comboStage() { return comboStage; }

    public int comboRapidClicks() { return comboRapidClicks; }

    public long lastClickEpochMillis() { return lastClickEpochMillis; }

    public void setComboState(int stage, int rapidClicks, long lastClickEpochMillis) {
        this.comboStage = Math.max(0, stage);
        this.comboRapidClicks = Math.max(0, rapidClicks);
        this.lastClickEpochMillis = lastClickEpochMillis;
    }

    public void resetCombo() {
        comboStage = 0;
        comboRapidClicks = 0;
        lastClickEpochMillis = Long.MIN_VALUE;
        clickHistory.clear();
    }

    // ------------------------------------------------------------------ snapshot

    public CookieProfileSnapshot snapshot() {
        return new CookieProfileSnapshot(playerId, cookies, lifetimeCookies, prestigeLevel, crumbs, crumbsEarnedTotal,
                totalClicks, goldenCookiesClicked, playtimeSeconds, highestCombo,
                new HashMap<>(generators), new HashSet<>(upgrades), new HashMap<>(prestigeUpgrades),
                new HashSet<>(achievements), new HashSet<>(discoveredZones), lastActiveAt, offlineClaimedUntil,
                version, new ArrayList<>(activeBuffs), comboStage);
    }

    @Override
    public String toString() {
        return "CookieProfile{" + playerId + ", cookies=" + cookies + ", lifetime=" + lifetimeCookies
                + ", prestige=" + prestigeLevel + ", crumbs=" + crumbs + '}';
    }

    // ------------------------------------------------------------------ builder

    /** Builder for loading persisted profiles. Missing fields default to the fresh-profile values. */
    public static final class Builder {
        private final UUID playerId;
        private CookieAmount cookies = CookieAmount.ZERO;
        private CookieAmount lifetimeCookies = CookieAmount.ZERO;
        private int prestigeLevel;
        private long crumbs;
        private long crumbsEarnedTotal;
        private long totalClicks;
        private long goldenCookiesClicked;
        private long playtimeSeconds;
        private int highestCombo;
        private Map<String, Integer> generators = Map.of();
        private Set<String> upgrades = Set.of();
        private Map<String, Integer> prestigeUpgrades = Map.of();
        private Set<String> achievements = Set.of();
        private Set<String> discoveredZones = Set.of();
        private Instant lastActiveAt;
        private Instant offlineClaimedUntil;
        private long version;

        private Builder(UUID playerId) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        public Builder cookies(CookieAmount v) { this.cookies = v; return this; }
        public Builder lifetimeCookies(CookieAmount v) { this.lifetimeCookies = v; return this; }
        public Builder prestigeLevel(int v) { this.prestigeLevel = v; return this; }
        public Builder crumbs(long v) { this.crumbs = v; return this; }
        public Builder crumbsEarnedTotal(long v) { this.crumbsEarnedTotal = v; return this; }
        public Builder totalClicks(long v) { this.totalClicks = v; return this; }
        public Builder goldenCookiesClicked(long v) { this.goldenCookiesClicked = v; return this; }
        public Builder playtimeSeconds(long v) { this.playtimeSeconds = v; return this; }
        public Builder highestCombo(int v) { this.highestCombo = v; return this; }
        public Builder generators(Map<String, Integer> v) { this.generators = v; return this; }
        public Builder upgrades(Set<String> v) { this.upgrades = v; return this; }
        public Builder prestigeUpgrades(Map<String, Integer> v) { this.prestigeUpgrades = v; return this; }
        public Builder achievements(Set<String> v) { this.achievements = v; return this; }
        public Builder discoveredZones(Set<String> v) { this.discoveredZones = v; return this; }
        public Builder lastActiveAt(Instant v) { this.lastActiveAt = v; return this; }
        public Builder offlineClaimedUntil(Instant v) { this.offlineClaimedUntil = v; return this; }
        public Builder version(long v) { this.version = v; return this; }

        public CookieProfile build() {
            return load(playerId, cookies, lifetimeCookies, prestigeLevel, crumbs, crumbsEarnedTotal, totalClicks,
                    goldenCookiesClicked, playtimeSeconds, highestCombo, generators, upgrades, prestigeUpgrades,
                    achievements, discoveredZones, lastActiveAt, offlineClaimedUntil, version);
        }
    }
}
