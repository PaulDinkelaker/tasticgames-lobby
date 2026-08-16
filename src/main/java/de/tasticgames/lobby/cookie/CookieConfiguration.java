package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * cookie-clicker.yml: world layout (zones, POIs, NPCs, golden areas, travel points), runtime and balancing.
 * The layout is data – a builder map replaces the development world without code changes.
 */
public record CookieConfiguration(
        World world,
        Map<String, Zone> zones,
        Map<String, Poi> pois,
        Map<String, Npc> npcs,
        List<LobbyConfiguration.Region> goldenAreas,
        Runtime runtime,
        CookieBalancing balancing
) {

    public record Point(String world, double x, double y, double z, float yaw, float pitch) {
        public org.bukkit.Location toLocation(org.bukkit.World w) {
            return new org.bukkit.Location(w, x, y, z, yaw, pitch);
        }
    }

    public record World(String name, boolean createIfMissing, Point entry, Point mainCookie, boolean useLobbyWorld) {
        public World {
            Objects.requireNonNull(name);
            Objects.requireNonNull(entry);
            Objects.requireNonNull(mainCookie);
        }
    }

    public record Zone(String id, LobbyConfiguration.Region region, Point entry, Point gateReturn) {
        public Zone {
            Objects.requireNonNull(id);
            Objects.requireNonNull(region);
            Objects.requireNonNull(entry);
        }
    }

    public record Poi(String id, String type, Point location, double radius) {
        public Poi {
            Objects.requireNonNull(id);
            Objects.requireNonNull(location);
            if (radius <= 0) throw new IllegalArgumentException("poi radius must be positive: " + id);
        }
    }

    public record Npc(String id, String role, String quest, Point location, String model) {
        public Npc {
            Objects.requireNonNull(id);
            Objects.requireNonNull(location);
            role = role == null ? "QUEST" : role.toUpperCase(Locale.ROOT);
            quest = quest == null ? "" : quest;
            model = model == null ? "" : model;
        }
    }

    public record Runtime(int saveIntervalSeconds, int maxDirtyAgeSeconds, int leaderboardCacheSeconds, int maxSaveFailuresBeforePause,
                          int goldenMaxPerPlayer, boolean npcsEnabled, boolean goldenEnabled) {
    }

    public static CookieConfiguration load(YamlConfiguration yaml, String lobbyWorldName) {
        ConfigurationSection worldSection = req(yaml, "world");
        boolean useLobbyWorld = worldSection.getBoolean("use-lobby-world", false);
        String worldName = useLobbyWorld ? lobbyWorldName : worldSection.getString("name", "cookie");
        World world = new World(worldName, worldSection.getBoolean("create-if-missing", true),
                point(req(worldSection, "entry"), worldName), point(req(worldSection, "main-cookie"), worldName), useLobbyWorld);

        Map<String, Zone> zones = new LinkedHashMap<>();
        ConfigurationSection zoneSection = yaml.getConfigurationSection("zones");
        if (zoneSection == null) {
            throw new IllegalArgumentException("cookie-clicker.yml: missing 'zones'");
        }
        for (String id : zoneSection.getKeys(false)) {
            ConfigurationSection z = zoneSection.getConfigurationSection(id);
            if (z == null) continue;
            LobbyConfiguration.Region region = region(req(z, "region"), worldName);
            Point entry = point(req(z, "entry"), worldName);
            Point gate = z.isConfigurationSection("gate-return") ? point(z.getConfigurationSection("gate-return"), worldName) : entry;
            if (zones.putIfAbsent(id.toLowerCase(Locale.ROOT), new Zone(id.toLowerCase(Locale.ROOT), region, entry, gate)) != null) {
                throw new IllegalArgumentException("cookie-clicker.yml: duplicate zone " + id);
            }
        }
        Map<String, Poi> pois = new LinkedHashMap<>();
        ConfigurationSection poiSection = yaml.getConfigurationSection("pois");
        if (poiSection != null) {
            for (String id : poiSection.getKeys(false)) {
                ConfigurationSection p = poiSection.getConfigurationSection(id);
                if (p == null) continue;
                String poiId = id.startsWith("cookie.") ? id : "cookie." + id;
                if (pois.putIfAbsent(poiId, new Poi(poiId, p.getString("type", "GENERIC"), point(p, worldName), p.getDouble("radius", 4.0))) != null) {
                    throw new IllegalArgumentException("cookie-clicker.yml: duplicate poi " + id);
                }
            }
        }
        Map<String, Npc> npcs = new LinkedHashMap<>();
        ConfigurationSection npcSection = yaml.getConfigurationSection("npcs");
        if (npcSection != null) {
            for (String id : npcSection.getKeys(false)) {
                ConfigurationSection n = npcSection.getConfigurationSection(id);
                if (n == null) continue;
                npcs.put(id, new Npc(id, n.getString("role"), n.getString("quest"), point(req(n, "location"), worldName), n.getString("model")));
            }
        }
        List<LobbyConfiguration.Region> golden = new ArrayList<>();
        ConfigurationSection goldenSection = yaml.getConfigurationSection("golden-cookie.areas");
        if (goldenSection != null) {
            for (String id : goldenSection.getKeys(false)) {
                ConfigurationSection g = goldenSection.getConfigurationSection(id);
                if (g != null) golden.add(region(g, worldName));
            }
        }
        Runtime runtime = new Runtime(yaml.getInt("runtime.save-interval-seconds", 10), yaml.getInt("runtime.max-dirty-age-seconds", 30),
                yaml.getInt("runtime.leaderboard-cache-seconds", 60), yaml.getInt("runtime.max-save-failures-before-pause", 30),
                yaml.getInt("golden-cookie.max-per-player", 1), yaml.getBoolean("npcs-enabled", true), yaml.getBoolean("golden-cookie.enabled", true));

        CookieBalancing.Builder b = CookieBalancing.defaults().toBuilder();
        ConfigurationSection bal = yaml.getConfigurationSection("balancing");
        if (bal != null) {
            if (bal.contains("cost-growth")) b.costGrowth(bal.getDouble("cost-growth"));
            if (bal.contains("max-clicks-per-second")) b.maxClicksPerSecond(bal.getInt("max-clicks-per-second"));
            if (bal.contains("combo-window-millis")) b.comboWindowMillis(bal.getLong("combo-window-millis"));
            if (bal.contains("combo-decay-millis")) b.comboDecayMillis(bal.getLong("combo-decay-millis"));
            if (bal.contains("clicks-per-combo-stage")) b.clicksPerComboStage(bal.getInt("clicks-per-combo-stage"));
            if (bal.contains("combo-stages")) b.comboStages(bal.getDoubleList("combo-stages"));
            if (bal.contains("offline.enabled")) b.offlineEnabled(bal.getBoolean("offline.enabled"));
            if (bal.contains("offline.max-seconds")) b.offlineMaxSeconds(bal.getLong("offline.max-seconds"));
            if (bal.contains("offline.efficiency")) b.offlineEfficiency(bal.getDouble("offline.efficiency"));
            if (bal.contains("golden.base-interval-seconds")) b.goldenBaseIntervalSeconds(bal.getDouble("golden.base-interval-seconds"));
            if (bal.contains("golden.lifetime-seconds")) b.goldenLifetimeSeconds(bal.getInt("golden.lifetime-seconds"));
            if (bal.contains("golden.buff-seconds")) b.goldenBuffSeconds(bal.getInt("golden.buff-seconds"));
        }
        return new CookieConfiguration(world, zones, pois, npcs, golden, runtime, b.build());
    }

    private static ConfigurationSection req(ConfigurationSection parent, String path) {
        ConfigurationSection s = parent.getConfigurationSection(path);
        if (s == null) throw new IllegalArgumentException("cookie-clicker.yml: missing section " + parent.getCurrentPath() + "." + path);
        return s;
    }

    private static Point point(ConfigurationSection s, String defaultWorld) {
        return new Point(s.getString("world", defaultWorld), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
    }

    private static LobbyConfiguration.Region region(ConfigurationSection s, String defaultWorld) {
        return new LobbyConfiguration.Region(s.getString("world", defaultWorld), s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"),
                s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"));
    }
}
