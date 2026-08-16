package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.CookieCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.GeneratorDefinition;
import de.tasticgames.lobby.cookie.domain.format.CookieNumberFormatter;
import de.tasticgames.lobby.cookie.domain.model.BuyMode;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.CookieStats;
import de.tasticgames.lobby.cookie.domain.model.OfflineResult;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult;
import de.tasticgames.lobby.cookie.domain.prestige.PrestigeCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static de.tasticgames.lobby.cookie.domain.TestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P10-scale magnitudes: 1e40 cookies, 10k generators, huge costs — nothing overflows or loses sign. */
class OverflowSafetyTest {

    private final CookieEngine engine = TestSupport.engine();
    private final CookieCatalog catalog = engine.catalog();

    private CookieProfile endgame() {
        Map<String, Integer> gens = new HashMap<>();
        for (GeneratorDefinition g : catalog.generators()) gens.put(g.id(), 10_000);
        CookieProfile p = TestSupport.profileAt(10, "1e40");
        p.setGenerators(gens);
        for (var u : catalog.upgrades()) p.addUpgrade(u.id());
        for (var n : catalog.prestigeTree()) p.setPrestigeUpgradeLevel(n.id(), n.maxLevel());
        return p;
    }

    @Test
    void statsAtEndgameMagnitudes() {
        CookieProfile p = endgame();
        CookieStats s = engine.compute(p);
        assertTrue(s.effectiveCps().signum() > 0);
        assertTrue(s.effectiveCps().compareTo(new BigDecimal("1e30")) > 0, s.effectiveCps().toString());
        assertTrue(s.clickValue().compareTo(new BigDecimal("1e28")) > 0);
        assertEquals(11, s.contributions().size());
        BigDecimal sum = BigDecimal.ZERO;
        for (var c : s.contributions()) sum = sum.add(c.cpsTotal());
        assertEquals(0, sum.compareTo(s.effectiveCps()));
        assertEquals(1.0, s.offlineEfficiency());
    }

    @Test
    void productionClicksAndOfflineAtEndgame() {
        CookieProfile p = endgame();
        CookieAmount before = p.cookies();
        CookieAmount produced = engine.produce(p, Duration.ofHours(1));
        assertTrue(produced.isPositive());
        assertEquals(before.plus(produced), p.cookies());
        assertTrue(engine.click(p, T0).reward().isPositive());
        OfflineResult off = engine.previewOffline(p, T0, T0.plusSeconds(100_000));
        assertEquals(28_800, off.cappedSeconds());
        assertTrue(off.cookies().isPositive());
        assertTrue(p.cookies().toPlainString().length() > 30);
    }

    @Test
    void purchasesAtEndgame() {
        CookieProfile p = endgame();
        // unit price of the 10,001st cursor: 15 * 1.15^10000 ≈ 1e608 — unaffordable but computed exactly
        CookieAmount unit = engine.unitCost("cursor", 10_000);
        assertTrue(unit.toPlainString().length() > 600);
        PurchaseResult r = engine.buy(p, "cursor", BuyMode.ONE);
        assertEquals(PurchaseResult.Reason.INSUFFICIENT_FUNDS, r.reason());
        assertEquals(PurchaseResult.Reason.INSUFFICIENT_FUNDS, engine.buy(p, "reality_forge", BuyMode.MAX).reason());
        // a fresh P10 profile with 1e40 cookies can buy hundreds of reality forges via MAX
        CookieProfile rich = TestSupport.profileAt(10, "1e40");
        PurchaseResult max = engine.buy(rich, "reality_forge", BuyMode.MAX);
        assertTrue(max.success());
        assertTrue(max.count() > 400, "count=" + max.count());
        assertTrue(rich.cookies().toBigDecimal().signum() >= 0);
        assertTrue(rich.cookies().compareTo(engine.unitCost("reality_forge", max.newOwned())) < 0);
        // costFor with 10k units is exact and finite
        CookieAmount bulk = engine.costFor("cursor", 0, 10_000);
        assertTrue(bulk.toPlainString().length() > 600);
    }

    @Test
    void crumbsAndFormattingAtEndgame() {
        assertEquals(215_443_469_003L, PrestigeCalculator.crumbsFor(CookieAmount.of("1e40"))); // floor(cbrt(1e34))
        CookieNumberFormatter f = new CookieNumberFormatter();
        assertEquals("10.00DDc", f.format(CookieAmount.of("1e40"), Locale.ENGLISH));
        assertEquals("1.00e100", f.format(new BigDecimal("1e100"), Locale.ENGLISH));
        String plain = CookieAmount.of("1e40").times(BigDecimal.valueOf(3)).toPlainString();
        assertEquals(41, plain.length());
    }
}
