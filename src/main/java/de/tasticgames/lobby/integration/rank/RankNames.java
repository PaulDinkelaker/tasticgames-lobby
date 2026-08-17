package de.tasticgames.lobby.integration.rank;

import java.util.Locale;

/**
 * Small helpers shared by the {@link RankProvider} implementations: group id normalisation and
 * human readable fallback names ({@code senior_mod} becomes {@code Senior Mod}).
 */
final class RankNames {

    private RankNames() {
    }

    /** Lower case, trimmed group id; {@code default} when nothing usable was given. */
    static String normaliseGroup(String group) {
        if (group == null) {
            return RankProvider.RankInfo.DEFAULT.group();
        }
        String trimmed = group.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? RankProvider.RankInfo.DEFAULT.group() : trimmed;
    }

    /** Capitalises every word of a group id ({@code _}, {@code -} and blanks are word separators). */
    static String capitalise(String group) {
        if (group == null || group.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(group.length());
        for (String part : group.trim().split("[_\\-\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }

    /** Trimmed, lower case permission node; {@code null} when blank (permission nodes are case insensitive). */
    static String normaliseNode(String node) {
        if (node == null) {
            return null;
        }
        String trimmed = node.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
