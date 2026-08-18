package de.tasticgames.lobby.pass;

import de.tasticgames.localization.SupportedLanguage;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;

/**
 * Presentation helpers of the pass UI: unicode progress bars, the season price in the player's
 * locale ({@code 9,99 €}), XP boost values and the remaining season time. Everything here is pure
 * text so it can be unit tested without a server.
 */
public final class PassFormat {

    public static final char FILLED = '█';
    public static final char EMPTY = '░';
    public static final int BAR_WIDTH = 20;

    private PassFormat() {
    }

    /** Locale used for numbers and the currency layout of the player's language. */
    public static Locale locale(SupportedLanguage language) {
        return language == SupportedLanguage.GERMAN ? Locale.GERMANY : Locale.ENGLISH;
    }

    /**
     * Unicode bar with {@code width} cells. A target of 0 or less counts as complete (the pass uses
     * that for the last level, where no further XP is required).
     */
    public static String progressBar(long current, long target, int width) {
        int cells = Math.max(1, width);
        int filled = target <= 0 ? cells : (int) Math.round(cells * ratio(current, target));
        return String.valueOf(FILLED).repeat(filled) + String.valueOf(EMPTY).repeat(cells - filled);
    }

    public static String progressBar(long current, long target) {
        return progressBar(current, target, BAR_WIDTH);
    }

    /** Progress in whole percent, clamped to 0..100. */
    public static int percent(long current, long target) {
        return target <= 0 ? 100 : (int) Math.round(ratio(current, target) * 100);
    }

    private static double ratio(long current, long target) {
        if (target <= 0) {
            return 1.0;
        }
        return Math.clamp((double) Math.max(0, current) / target, 0.0, 1.0);
    }

    /**
     * Season price for display, e.g. {@code 9,99 €} in German and {@code €9.99} in English. Unknown
     * currency codes fall back to "amount code" so the dialog never breaks on bad season data.
     */
    public static String price(int cents, String currency, SupportedLanguage language) {
        Locale locale = locale(language);
        double amount = cents / 100.0;
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance(locale);
            format.setCurrency(Currency.getInstance(code));
            return format.format(amount);
        } catch (IllegalArgumentException | NullPointerException e) {
            return NumberFormat.getNumberInstance(locale).format(amount) + (code.isEmpty() ? "" : " " + code);
        }
    }

    /** {@code 150} (amount ×100 as stored by the season) becomes {@code 1.5}. */
    public static double multiplier(long rewardAmount) {
        return rewardAmount <= 0 ? 1.0 : rewardAmount / 100.0;
    }

    /** ISO-8601 duration of an XP boost ({@code P7D}) as a short label ({@code 7d}, {@code 12h}). */
    public static String duration(String iso8601) {
        if (iso8601 == null || iso8601.isBlank()) {
            return "";
        }
        Duration duration;
        try {
            duration = Duration.parse(iso8601.trim());
        } catch (RuntimeException e) {
            return iso8601.trim();
        }
        long days = duration.toDays();
        if (days > 0) {
            return days + "d";
        }
        long hours = duration.toHours();
        return hours > 0 ? hours + "h" : Math.max(0, duration.toMinutes()) + "m";
    }

    /** Full days left until the season ends; 0 once it is over or unknown. */
    public static long daysLeft(Instant endsAt, Instant now) {
        if (endsAt == null || now == null || !endsAt.isAfter(now)) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(now, endsAt));
    }

    /** Thousand separated XP number in the player's locale. */
    public static String number(long value, SupportedLanguage language) {
        return NumberFormat.getIntegerInstance(locale(language)).format(value);
    }
}
