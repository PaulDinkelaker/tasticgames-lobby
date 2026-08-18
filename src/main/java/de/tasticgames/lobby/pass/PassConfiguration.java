package de.tasticgames.lobby.pass;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Objects;

/**
 * pass.yml: what the lobby contributes to the season pass (XP rates for the Cookie Clicker hooks and
 * the local flush interval) and how it is presented (dialog/NPC toggles, leaderboard size, premium
 * information). The season itself – tiers, quests, prices – is owned by the API.
 */
public record PassConfiguration(boolean enabled, boolean npcEnabled, boolean dialogsEnabled, int leaderboardSize, Xp xp, Premium premium) {

    public PassConfiguration {
        Objects.requireNonNull(xp);
        Objects.requireNonNull(premium);
        if (leaderboardSize < 3 || leaderboardSize > 100) {
            throw new IllegalArgumentException("pass.yml: leaderboard-size must be 3..100");
        }
    }

    /**
     * @param flushIntervalSeconds seconds between two local flushes of the accumulated progress
     * @param clicksPerXp          clicks needed for 1 XP (leftover clicks are carried into the next flush)
     * @param perAchievement       extra XP per cookie achievement on top of the reward the API grants itself (0 = off)
     */
    public record Xp(int flushIntervalSeconds, int clicksPerXp, int perPurchase, int perPrestige, int perGolden, int perZone,
                     int perAchievement, int perNpcQuest) {
        public Xp {
            if (clicksPerXp < 1) {
                throw new IllegalArgumentException("pass.yml: xp.clicks-per-xp must be at least 1");
            }
            if (perPurchase < 0 || perPrestige < 0 || perGolden < 0 || perZone < 0 || perAchievement < 0 || perNpcQuest < 0) {
                throw new IllegalArgumentException("pass.yml: xp rates must not be negative");
            }
            flushIntervalSeconds = Math.clamp(flushIntervalSeconds, 1, 300);
        }
    }

    /**
     * @param storeUrl shown as clickable text in the premium dialog (empty = not shown)
     * @param infoKey  message key with the instructions how premium is obtained
     */
    public record Premium(String storeUrl, String infoKey) {
        public Premium {
            storeUrl = storeUrl == null ? "" : storeUrl.trim();
            infoKey = infoKey == null || infoKey.isBlank() ? "pass.premium.howto" : infoKey.trim();
        }
    }

    public static PassConfiguration load(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml);
        ConfigurationSection xpSection = yaml.getConfigurationSection("xp");
        Xp xp = new Xp(
                xpSection == null ? 10 : xpSection.getInt("flush-interval-seconds", 10),
                xpSection == null ? 25 : xpSection.getInt("clicks-per-xp", 25),
                xpSection == null ? 2 : xpSection.getInt("per-purchase", 2),
                xpSection == null ? 500 : xpSection.getInt("per-prestige", 500),
                xpSection == null ? 15 : xpSection.getInt("per-golden", 15),
                xpSection == null ? 50 : xpSection.getInt("per-zone", 50),
                xpSection == null ? 0 : xpSection.getInt("per-achievement", 0),
                xpSection == null ? 100 : xpSection.getInt("per-npc-quest", 100));
        ConfigurationSection premiumSection = yaml.getConfigurationSection("premium");
        Premium premium = new Premium(
                premiumSection == null ? "" : premiumSection.getString("store-url", ""),
                premiumSection == null ? "" : premiumSection.getString("info-key", ""));
        return new PassConfiguration(yaml.getBoolean("enabled", true), yaml.getBoolean("npc", true), yaml.getBoolean("dialogs", true),
                yaml.getInt("leaderboard-size", 10), xp, premium);
    }
}
