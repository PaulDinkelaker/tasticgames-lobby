package de.tasticgames.lobby.locale;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EN is the fallback; DE and HI must be complete and use the same placeholders.
 */
class MessageBundleTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z_]+)>");
    private static final Set<String> MINI_TAGS = Set.of("gold", "gray", "white", "red", "green", "yellow", "aqua", "dark_gray", "dark_red",
            "light_purple", "dark_aqua", "bold", "italic", "newline");

    private Properties load(String lang) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages/lobby_" + lang + ".properties")) {
            assertNotNull(in, "bundle " + lang);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bundlesAreCompleteAndConsistent() throws Exception {
        Properties en = load("en");
        assertTrue(en.size() > 400, "english bundle should be large");
        for (String lang : List.of("de", "hi")) {
            Properties other = load(lang);
            Set<String> missing = new TreeSet<>(en.stringPropertyNames());
            missing.removeAll(other.stringPropertyNames());
            assertTrue(missing.isEmpty(), lang + " is missing keys: " + missing);
            for (String key : en.stringPropertyNames()) {
                assertEquals(placeholders(en.getProperty(key)), placeholders(other.getProperty(key)), "placeholders differ for " + key + " (" + lang + ")");
                assertFalse(other.getProperty(key).isBlank(), lang + " has an empty translation for " + key);
            }
        }
    }

    @Test
    void requiredKeysExist() throws Exception {
        Properties en = load("en");
        for (String key : List.of("common.close", "lobby.item.gateway.name", "lobby.item.visibility.name", "lobby.visibility.all",
                "lobby.gateway.title", "lobby.settings.title", "lobby.profile.title_own", "lobby.social.title", "lobby.cosmetics.title",
                "cookie.overview.title", "cookie.prestige.confirm", "cookie.offline.title", "cookie.generator.cursor.name",
                "cookie.zone.ascendant_sanctum", "cookie.prestige.p10", "cookie.tree.iron_fingers", "cookie.achievement.prestige_10",
                "cookie.upgrade.reality_forge_tier_2", "pass.overview.title", "pass.unavailable", "pass.rewards.claim_all",
                "pass.quests.title", "pass.premium.price", "pass.leaderboard.title", "pass.levelup.chat", "pass.reward.xp_boost",
                "pass.outcome.premium_required", "pass.track.premium", "lobby.profile.pass",
                "lobby.npc.pass.name", "lobby.npc.creative.name", "lobby.npc.survival.name", "lobby.npc.duels.name",
                "lobby.npc.games.name", "lobby.npc.none")) {
            assertTrue(en.containsKey(key), "missing key " + key);
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!MINI_TAGS.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }
}
