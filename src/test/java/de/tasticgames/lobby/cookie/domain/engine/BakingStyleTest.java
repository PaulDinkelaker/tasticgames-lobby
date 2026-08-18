package de.tasticgames.lobby.cookie.domain.engine;

import de.tasticgames.lobby.cookie.domain.TestSupport;
import de.tasticgames.lobby.cookie.domain.catalog.DefaultCatalog;
import de.tasticgames.lobby.cookie.domain.catalog.UpgradeDefinition;
import de.tasticgames.lobby.cookie.domain.model.CookieAmount;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import de.tasticgames.lobby.cookie.domain.model.PurchaseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One baking style per run: the three style upgrades exclude each other and a prestige frees the choice. */
class BakingStyleTest {

    private final CookieEngine engine = TestSupport.engine();

    private CookieProfile styleReady() {
        CookieProfile profile = TestSupport.profileAt(1, "1e9");
        return profile;
    }

    @Test
    void stylesAreLockedBeforeTheFirstPrestige() {
        CookieProfile fresh = TestSupport.fresh();
        fresh.earn(CookieAmount.of("1e9"));
        assertTrue(engine.exclusiveOptions(fresh, DefaultCatalog.STYLE_GROUP).isEmpty());
        assertEquals(PurchaseResult.Reason.LOCKED, engine.buyUpgrade(fresh, "style_artisan").reason());
    }

    @Test
    void exactlyOneStyleCanBeOwned() {
        CookieProfile profile = styleReady();
        List<UpgradeDefinition> options = engine.exclusiveOptions(profile, DefaultCatalog.STYLE_GROUP);
        assertEquals(3, options.size());
        assertTrue(engine.exclusiveChoice(profile, DefaultCatalog.STYLE_GROUP).isEmpty());

        assertTrue(engine.buyUpgrade(profile, "style_artisan").success());
        assertEquals("style_artisan", engine.exclusiveChoice(profile, DefaultCatalog.STYLE_GROUP).orElseThrow().id());

        PurchaseResult second = engine.buyUpgrade(profile, "style_industrial");
        assertFalse(second.success());
        assertEquals(PurchaseResult.Reason.ALREADY_OWNED, second.reason());
        assertFalse(profile.hasUpgrade("style_industrial"));

        List<String> available = engine.availableUpgrades(profile).stream().map(UpgradeDefinition::id).toList();
        assertFalse(available.contains("style_industrial"), "the other styles disappear from the shop");
        assertFalse(available.contains("style_lucky"));
    }

    @Test
    void theChosenStyleChangesTheRun() {
        CookieProfile clicker = styleReady();
        clicker.addGenerators("baker", 20);
        CookieProfile idler = styleReady();
        idler.addGenerators("baker", 20);

        double baseClick = engine.compute(clicker).clickValue().doubleValue();
        double baseCps = engine.compute(idler).effectiveCps().doubleValue();

        engine.buyUpgrade(clicker, "style_artisan");
        engine.buyUpgrade(idler, "style_industrial");

        assertEquals(baseClick * 4, engine.compute(clicker).clickValue().doubleValue(), 1e-6);
        assertEquals(baseCps * 1.4, engine.compute(idler).effectiveCps().doubleValue(), 1e-6);
        assertEquals(2.0, engine.compute(styleWith("style_lucky")).goldenChanceMultiplier(), 1e-6);
    }

    private CookieProfile styleWith(String id) {
        CookieProfile profile = styleReady();
        assertTrue(engine.buyUpgrade(profile, id).success());
        return profile;
    }

    @Test
    void prestigeClearsTheStyleSoTheNextRunCanBePlayedDifferently() {
        CookieProfile profile = TestSupport.profileAt(1, "1e9");
        assertTrue(engine.buyUpgrade(profile, "style_lucky").success());
        profile.setLifetimeCookies(CookieAmount.of("1e10"));
        engine.applyPrestige(profile, engine.planPrestige(profile));
        assertTrue(engine.exclusiveChoice(profile, DefaultCatalog.STYLE_GROUP).isEmpty());
        assertFalse(profile.hasUpgrade("style_lucky"));
    }
}
