package de.tasticgames.lobby.cookie.domain.sim;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Result of a {@link CookieSimulator} run.
 *
 * @param milestones      one entry per reached prestige level (in order)
 * @param totalGameSeconds simulated game seconds
 * @param finalPrestige   prestige level at the end
 * @param reachedTarget   whether the target prestige was reached within the time budget
 * @param purchases       number of purchases made
 * @param steps           number of simulation steps
 */
public record SimulationReport(
        List<PrestigeMilestone> milestones,
        double totalGameSeconds,
        int finalPrestige,
        boolean reachedTarget,
        long purchases,
        long steps
) {

    /** @param level reached level, @param gameSeconds simulated seconds since start, @param secondsSincePrevious seconds since the previous prestige */
    public record PrestigeMilestone(int level, double gameSeconds, double secondsSincePrevious) {
        public double hours() { return gameSeconds / 3600.0; }
        public double hoursSincePrevious() { return secondsSincePrevious / 3600.0; }
    }

    public SimulationReport {
        milestones = List.copyOf(Objects.requireNonNull(milestones, "milestones"));
    }

    public double totalGameHours() {
        return totalGameSeconds / 3600.0;
    }

    /** Cumulative hours at which {@code level} was reached, if reached. */
    public OptionalDouble hoursToPrestige(int level) {
        return milestones.stream().filter(m -> m.level() == level).mapToDouble(PrestigeMilestone::hours).findFirst();
    }

    /** Markdown table of the milestones. */
    public String toMarkdownTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Prestige | Reached after (h, cumulative) | Duration of previous level (h) |\n");
        sb.append("|---|---:|---:|\n");
        for (PrestigeMilestone m : milestones) {
            sb.append(String.format(Locale.ROOT, "| P%d | %.2f | %.2f |%n", m.level(), m.hours(), m.hoursSincePrevious()));
        }
        return sb.toString();
    }
}
