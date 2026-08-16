package de.tasticgames.lobby.cookie.domain.sim;

/**
 * Simulator parameters.
 *
 * @param clicksPerSecond      sustained manual clicks per second while actively clicking (0 = idle only)
 * @param activeClickSeconds   how long (game seconds) after the start of each run the player keeps clicking;
 *                             use {@link Double#POSITIVE_INFINITY} for a player who never stops clicking
 * @param shopCheckIntervalSeconds how often (game seconds) the player opens the shop and buys; purchases
 *                             happen only at these ticks (1 = instant, perfectly attentive player)
 * @param maxGameHours         hard stop for the simulation
 * @param targetPrestige       stop once this prestige level is reached
 * @param maxStepSeconds       upper bound of a single simulated time step
 * @param luxuryBankFraction   fraction of the bank the player is willing to spend on non-CPS upgrades
 */
public record SimulationConfig(
        double clicksPerSecond,
        double activeClickSeconds,
        long shopCheckIntervalSeconds,
        double maxGameHours,
        int targetPrestige,
        long maxStepSeconds,
        double luxuryBankFraction
) {

    public SimulationConfig {
        if (clicksPerSecond < 0) throw new IllegalArgumentException("clicksPerSecond must be >= 0");
        if (activeClickSeconds < 0 || Double.isNaN(activeClickSeconds)) throw new IllegalArgumentException("activeClickSeconds must be >= 0");
        if (shopCheckIntervalSeconds < 1) throw new IllegalArgumentException("shopCheckIntervalSeconds must be >= 1");
        if (maxGameHours <= 0) throw new IllegalArgumentException("maxGameHours must be > 0");
        if (targetPrestige < 1) throw new IllegalArgumentException("targetPrestige must be >= 1");
        if (maxStepSeconds < 1) throw new IllegalArgumentException("maxStepSeconds must be >= 1");
        if (luxuryBankFraction < 0 || luxuryBankFraction > 1) throw new IllegalArgumentException("luxuryBankFraction must be within [0,1]");
    }

    /**
     * Reasonable active player: clicks 5/s for the first 10 minutes of every run, then lets the
     * generators work; up to 5000 game hours, target P10.
     */
    public static SimulationConfig activePlayer() {
        return new SimulationConfig(5.0, 10 * 60, 30, 5000.0, 10, 3600, 0.05);
    }

    /** Player who never stops clicking at 5/s and buys the instant something is affordable (upper bound of progress speed). */
    public static SimulationConfig relentlessClicker() {
        return activePlayer().withActiveClickSeconds(Double.POSITIVE_INFINITY).withShopCheckIntervalSeconds(1);
    }

    public SimulationConfig withShopCheckIntervalSeconds(long seconds) {
        return new SimulationConfig(clicksPerSecond, activeClickSeconds, seconds, maxGameHours, targetPrestige, maxStepSeconds, luxuryBankFraction);
    }

    public SimulationConfig withClicksPerSecond(double cps) {
        return new SimulationConfig(cps, activeClickSeconds, shopCheckIntervalSeconds, maxGameHours, targetPrestige, maxStepSeconds, luxuryBankFraction);
    }

    public SimulationConfig withActiveClickSeconds(double seconds) {
        return new SimulationConfig(clicksPerSecond, seconds, shopCheckIntervalSeconds, maxGameHours, targetPrestige, maxStepSeconds, luxuryBankFraction);
    }

    public SimulationConfig withMaxGameHours(double hours) {
        return new SimulationConfig(clicksPerSecond, activeClickSeconds, shopCheckIntervalSeconds, hours, targetPrestige, maxStepSeconds, luxuryBankFraction);
    }

    public SimulationConfig withTargetPrestige(int target) {
        return new SimulationConfig(clicksPerSecond, activeClickSeconds, shopCheckIntervalSeconds, maxGameHours, target, maxStepSeconds, luxuryBankFraction);
    }
}
