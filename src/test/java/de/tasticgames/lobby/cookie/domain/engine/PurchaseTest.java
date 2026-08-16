package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.model.BuyMode;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult.Reason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseTest {

    private final CookieEngine engine = TestSupport.engine();

    @Test
    void buysSingleGeneratorAndDeductsCost() {
        CookieProfile p = TestSupport.withCookies("100");
        p.markClean();
        PurchaseResult r = engine.buy(p, "cursor", BuyMode.ONE);
        assertTrue(r.success());
        assertEquals(1, r.count());
        assertEquals(CookieAmount.of(15), r.totalCost());
        assertEquals(1, r.newOwned());
        assertEquals(CookieAmount.of(85), p.cookies());
        assertEquals(1, p.generatorCount("cursor"));
        assertTrue(p.isDirty());
    }

    @Test
    void buysTenAndHundredExactly() {
        CookieProfile p = TestSupport.withCookies("1e9");
        PurchaseResult ten = engine.buy(p, "cursor", BuyMode.TEN);
        assertTrue(ten.success());
        assertEquals(10, ten.count());
        assertEquals(engine.costFor("cursor", 0, 10), ten.totalCost());
        PurchaseResult hundred = engine.buy(p, "cursor", BuyMode.HUNDRED);
        assertTrue(hundred.success());
        assertEquals(110, hundred.newOwned());
        assertEquals(engine.costFor("cursor", 10, 100), hundred.totalCost());
        assertEquals(CookieAmount.of("1e9").minus(ten.totalCost()).minus(hundred.totalCost()), p.cookies());
    }

    @Test
    void buyMaxBuysExactlyWhatIsAffordable() {
        CookieProfile p = TestSupport.withCookies("1000");
        PurchaseResult r = engine.buy(p, "cursor", BuyMode.MAX);
        assertTrue(r.success());
        assertTrue(r.count() > 1);
        assertTrue(p.cookies().compareTo(engine.unitCost("cursor", r.newOwned())) < 0, "next unit must not be affordable");
        assertFalse(p.cookies().toBigDecimal().signum() < 0);
        // second MAX with the remaining bank fails
        PurchaseResult again = engine.buy(p, "cursor", BuyMode.MAX);
        assertFalse(again.success());
        assertEquals(Reason.INSUFFICIENT_FUNDS, again.reason());
    }

    @Test
    void insufficientFundsNeverGoesNegative() {
        CookieProfile p = TestSupport.withCookies("14");
        PurchaseResult r = engine.buy(p, "cursor", BuyMode.ONE);
        assertFalse(r.success());
        assertEquals(Reason.INSUFFICIENT_FUNDS, r.reason());
        assertEquals(CookieAmount.of(14), p.cookies());
        assertEquals(0, p.generatorCount("cursor"));
        // TEN with money for only a few
        CookieProfile q = TestSupport.withCookies("100");
        PurchaseResult ten = engine.buy(q, "cursor", BuyMode.TEN);
        assertFalse(ten.success());
        assertEquals(CookieAmount.of(100), q.cookies());
    }

    @Test
    void rejectsZeroNegativeUnknownAndLocked() {
        CookieProfile p = TestSupport.withCookies("1e12");
        assertEquals(Reason.INVALID_COUNT, engine.buy(p, "cursor", 0).reason());
        assertEquals(Reason.INVALID_COUNT, engine.buy(p, "cursor", -5).reason());
        assertEquals(Reason.UNKNOWN_ITEM, engine.buy(p, "unicorn", BuyMode.ONE).reason());
        assertEquals(Reason.UNKNOWN_ITEM, engine.buy(p, "unicorn", BuyMode.MAX).reason());
        assertEquals(Reason.LOCKED, engine.buy(p, "sugar_farm", BuyMode.ONE).reason());
        assertEquals(Reason.LOCKED, engine.buy(p, "sugar_farm", BuyMode.MAX).reason());
        assertEquals(CookieAmount.of("1e12"), p.cookies());
        // unlocked once the prestige level is high enough
        p.setPrestigeLevel(1);
        assertTrue(engine.buy(p, "sugar_farm", BuyMode.ONE).success());
        assertEquals(Reason.LOCKED, engine.buy(p, "cocoa_mine", BuyMode.ONE).reason());
    }

    @Test
    void buysUpgradeWithValidation() {
        CookieProfile p = TestSupport.withCookies("1000");
        assertEquals(Reason.UNKNOWN_ITEM, engine.buyUpgrade(p, "nope").reason());
        assertEquals(Reason.INSUFFICIENT_FUNDS, engine.buyUpgrade(p, "ambidextrous").reason());
        assertEquals(Reason.REQUIREMENT_NOT_MET, engine.buyUpgrade(p, "cursor_tier_1").reason());
        assertEquals(Reason.LOCKED, engine.buyUpgrade(p, "sugar_awakening_recipe").reason());

        PurchaseResult ok = engine.buyUpgrade(p, "reinforced_index_finger");
        assertTrue(ok.success());
        assertEquals(CookieAmount.of(100), ok.totalCost());
        assertEquals(CookieAmount.of(900), p.cookies());
        assertTrue(p.hasUpgrade("reinforced_index_finger"));
        assertEquals(Reason.ALREADY_OWNED, engine.buyUpgrade(p, "reinforced_index_finger").reason());

        engine.buy(p, "cursor", 1);
        assertTrue(engine.buyUpgrade(p, "cursor_tier_1").success());
        assertFalse(engine.isUpgradeAvailable(p, engine.catalog().requireUpgrade("cursor_tier_1")));
        assertTrue(engine.availableUpgrades(p).stream().anyMatch(u -> u.id().equals("carpal_tunnel")));
    }
}
