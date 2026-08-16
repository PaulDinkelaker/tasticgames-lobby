package de.tasticgames.lobby.cookie;

import de.tasticgames.client.dto.lobby.CookieProfileResponse;
import de.tasticgames.client.dto.lobby.CookieProfileSaveRequest;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps between the API DTOs (decimal strings) and the domain profile. crumbsEarnedTotal is
 * persisted inside the prestigeUpgrades map under a reserved key (no schema change needed).
 */
public final class CookieProfileMapper {

    static final String CRUMBS_TOTAL_KEY = "_crumbs_earned_total";
    static final String CRUMBS_KEY = "_crumbs";

    private CookieProfileMapper() {
    }

    public static CookieProfile fromResponse(CookieProfileResponse r) {
        Map<String, Integer> tree = new HashMap<>(r.prestigeUpgrades());
        long crumbsTotal = tree.containsKey(CRUMBS_TOTAL_KEY) ? tree.remove(CRUMBS_TOTAL_KEY) : 0L;
        tree.remove(CRUMBS_KEY);
        long crumbs = parseLong(r.prestigeCurrency());
        return CookieProfile.load(r.minecraftUuid(),
                CookieAmount.parse(defaultZero(r.cookies())),
                CookieAmount.parse(defaultZero(r.lifetimeCookies())),
                r.prestigeLevel(), crumbs, Math.max(crumbsTotal, crumbs),
                r.totalClicks(), r.goldenCookiesClicked(), r.playtimeSeconds(), r.highestCombo(),
                new HashMap<>(r.generators()), new HashSet<>(r.upgrades()), tree, new HashSet<>(r.achievements()),
                new HashSet<>(r.discoveredZones()),
                r.lastActiveAt() == null ? Instant.now() : r.lastActiveAt(),
                r.offlineClaimedUntil() == null ? Instant.EPOCH : r.offlineClaimedUntil(),
                r.version());
    }

    public static CookieProfileSaveRequest toSaveRequest(CookieProfile p) {
        Map<String, Integer> tree = new HashMap<>(p.prestigeUpgrades());
        tree.put(CRUMBS_TOTAL_KEY, (int) Math.min(Integer.MAX_VALUE, p.crumbsEarnedTotal()));
        return new CookieProfileSaveRequest(p.version(), p.cookies().toPlainString(), p.lifetimeCookies().toPlainString(),
                Long.toString(p.crumbs()), p.totalClicks(), p.goldenCookiesClicked(), p.playtimeSeconds(), p.highestCombo(),
                p.generators(), List.copyOf(p.upgrades()), tree, List.copyOf(p.achievements()), List.copyOf(p.discoveredZones()),
                p.lastActiveAt() == null ? Instant.now() : p.lastActiveAt());
    }

    /** Applies persisted server state (after prestige/offline/admin operations) to the live profile. */
    public static void applyServerState(CookieProfile target, CookieProfileResponse r) {
        CookieProfile fresh = fromResponse(r);
        target.setCookies(fresh.cookies());
        target.setLifetimeCookies(fresh.lifetimeCookies());
        target.setPrestigeLevel(fresh.prestigeLevel());
        long crumbDelta = fresh.crumbs() - target.crumbs();
        if (crumbDelta > 0) target.addCrumbs(crumbDelta);
        else if (crumbDelta < 0) target.spendCrumbs(-crumbDelta);
        target.setGenerators(fresh.generators());
        target.clearUpgrades();
        fresh.upgrades().forEach(target::addUpgrade);
        fresh.prestigeUpgrades().forEach(target::setPrestigeUpgradeLevel);
        fresh.achievements().forEach(target::addAchievement);
        fresh.discoveredZones().forEach(target::addDiscoveredZone);
        target.setOfflineClaimedUntil(fresh.offlineClaimedUntil());
        target.setVersion(fresh.version());
        target.markClean();
    }

    private static String defaultZero(String value) {
        return value == null || value.isBlank() ? "0" : value;
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return new java.math.BigDecimal(value).longValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Set<String> set(List<String> list) {
        return new HashSet<>(list);
    }
}
