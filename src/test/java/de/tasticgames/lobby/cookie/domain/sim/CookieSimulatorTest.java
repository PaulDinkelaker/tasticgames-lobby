package de.tasticgames.lobby.cookie.domain.sim;

import de.tasticgames.lobby.cookie.domain.engine.CookieEngine;
import de.tasticgames.lobby.cookie.domain.model.CookieProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieSimulatorTest {

    @Test
    void activePlayerReachesFirstPrestigeWithinExpectedWindow() {
        SimulationReport report = new CookieSimulator(CookieEngine.withDefaults(),
                SimulationConfig.activePlayer().withTargetPrestige(1)).run();
        double hours = report.hoursToPrestige(1).orElseThrow();
        assertTrue(hours >= 0.5, "P1 reached too fast: " + hours + " h");
        assertTrue(hours <= 6.0, "P1 reached too slow: " + hours + " h");
    }

    @Test
    void relentlessClickerStillNeedsAtLeastHalfAnHourForFirstPrestige() {
        SimulationReport report = new CookieSimulator(CookieEngine.withDefaults(),
                SimulationConfig.relentlessClicker().withTargetPrestige(1)).run();
        double hours = report.hoursToPrestige(1).orElseThrow();
        assertTrue(hours >= 0.5, "P1 reached too fast even for a relentless clicker: " + hours + " h");
    }

    @Test
    void activePlayerReachesPrestigeTenWithinBoundedHours() {
        SimulationReport report = CookieSimulator.defaults().run();
        assertTrue(report.reachedTarget(), "P10 not reached within " + report.totalGameHours() + " h; final=" + report.finalPrestige());
        assertEquals(10, report.finalPrestige());
        assertTrue(report.totalGameHours() < 5000, "P10 took " + report.totalGameHours() + " h");
        assertEquals(10, report.milestones().size());
        for (int i = 0; i < report.milestones().size(); i++) {
            SimulationReport.PrestigeMilestone m = report.milestones().get(i);
            assertEquals(i + 1, m.level());
            if (i > 0) assertTrue(m.gameSeconds() > report.milestones().get(i - 1).gameSeconds());
        }
        // sanity: the late game is slower than the early game
        assertTrue(report.milestones().get(9).hoursSincePrevious() > report.milestones().get(1).hoursSincePrevious());
        assertTrue(report.purchases() > 100);
        assertTrue(report.toMarkdownTable().contains("| P10 |"));
    }

    @Test
    void lowActivityPlayerStillProgresses() {
        SimulationReport report = new CookieSimulator(CookieEngine.withDefaults(),
                SimulationConfig.activePlayer().withClicksPerSecond(1).withTargetPrestige(1).withMaxGameHours(48)).run();
        assertTrue(report.reachedTarget(), "low-activity player should still reach P1 within 48h, got " + report.totalGameHours());
    }

    @Test
    void playerWhoNeverClicksCannotStart() {
        CookieProfile p = CookieProfile.fresh(UUID.randomUUID());
        SimulationReport report = new CookieSimulator(CookieEngine.withDefaults(),
                SimulationConfig.activePlayer().withClicksPerSecond(0).withTargetPrestige(1).withMaxGameHours(1)).run(p);
        assertEquals(0, report.finalPrestige());
        assertEquals(0, report.purchases());
    }

    @Test
    void simulationRespectsTimeBudget() {
        SimulationReport report = new CookieSimulator(CookieEngine.withDefaults(),
                SimulationConfig.activePlayer().withMaxGameHours(0.1).withTargetPrestige(10)).run();
        assertTrue(report.totalGameHours() <= 0.1 + 1.0 / 3600.0);
        assertEquals(0, report.finalPrestige());
    }
}
