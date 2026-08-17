package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * cookie-clicker.yml (config-version 3): main cookie in the lobby world, quest NPCs, the
 * prestige-10 open world with zones/POIs, runtime and balancing. Layout is data – builders
 * replace coordinates without code changes. Files are migrated in place ({@link #migrate}):
 * version 3 removed the NPCs {@code babette} and {@code king_frosting}.
 */
public record CookieConfiguration(
        MainCookie mainCookie,
        Npcs npcs,
        OpenWorld openWorld,
        Map<String, Zone> zones,
        Map<String, Poi> pois,
        Runtime runtime,
        CookieBalancing balancing,
        boolean legacyFile
) {

    public static final int CURRENT_VERSION = 3;
    /** First version with the current layout (main cookie in the lobby); older files are replaced by the bundled defaults. */
    public static final int LAYOUT_VERSION = 2;
    /** NPC ids removed with config-version 3 (only the baker and the merchant remain). */
    public static final List<String> REMOVED_NPCS_V3 = List.of("babette", "king_frosting");
    public static final String GOLDEN_MODE_AUTO = "auto";
    public static final String GOLDEN_MODE_SPAWN = "spawn";

    public CookieConfiguration {
        Objects.requireNonNull(mainCookie);
        Objects.requireNonNull(npcs);
        Objects.requireNonNull(openWorld);
        zones = Map.copyOf(zones);
        pois = Map.copyOf(pois);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(balancing);
    }

    public record Point(String world, double x, double y, double z, float yaw, float pitch) {
        public Point {
            Objects.requireNonNull(world);
        }

        public org.bukkit.Location toLocation(org.bukkit.World w) {
            return new org.bukkit.Location(w, x, y, z, yaw, pitch);
        }

        public Point withWorld(String worldName) {
            return new Point(worldName, x, y, z, yaw, pitch);
        }
    }

    /**
     * @param location         position of the main cookie (world = lobby world by default)
     * @param mythicMobsType   MythicMobs mob type used as visual (empty = skip)
     * @param clickSkill       MythicMobs skill cast on the mob for every bake click (hit animation), empty = none
     * @param model            ModelEngine model id used when MythicMobs is not available (empty = skip)
     * @param zoneRadius       cookie zone radius: generators produce and the actionbar shows only inside it
     * @param goldenAreas      golden cookies only trigger inside these regions (empty = anywhere in the cookie's world)
     * @param goldenMode       {@code auto} = the golden cookie activates for the player directly, {@code spawn} = it appears
     *                         nearby in the world and has to be clicked
     */
    public record MainCookie(Point location, String mythicMobsType, String clickSkill, String model, double hitboxWidth, double hitboxHeight, boolean label,
                             double zoneRadius, long actionbarIntervalMillis, boolean goldenEnabled, int goldenMaxPerPlayer,
                             List<LobbyConfiguration.Region> goldenAreas, String goldenMode) {
        public MainCookie {
            Objects.requireNonNull(location);
            mythicMobsType = mythicMobsType == null ? "" : mythicMobsType.trim();
            clickSkill = clickSkill == null ? "" : clickSkill.trim();
            model = model == null ? "" : model.trim();
            goldenAreas = goldenAreas == null ? List.of() : List.copyOf(goldenAreas);
            goldenMode = normalizeGoldenMode(goldenMode);
            if (hitboxWidth <= 0 || hitboxHeight <= 0) {
                throw new IllegalArgumentException("main-cookie.hitbox must be positive");
            }
            if (zoneRadius <= 0) {
                throw new IllegalArgumentException("main-cookie.zone-radius must be positive");
            }
            actionbarIntervalMillis = Math.max(250, actionbarIntervalMillis);
            goldenMaxPerPlayer = Math.max(1, goldenMaxPerPlayer);
        }

        public String world() {
            return location.world();
        }

        public boolean goldenAuto() {
            return GOLDEN_MODE_AUTO.equals(goldenMode);
        }

        /** Whether the location lies inside the cookie zone (same world, within zoneRadius). */
        public boolean inZone(org.bukkit.Location other) {
            if (other == null || other.getWorld() == null || !other.getWorld().getName().equals(location.world())) {
                return false;
            }
            double dx = other.getX() - location.x();
            double dy = other.getY() - location.y();
            double dz = other.getZ() - location.z();
            return dx * dx + dy * dy + dz * dz <= zoneRadius * zoneRadius;
        }
    }

    /**
     * @param skin          Minecraft player name whose skin is used (PLAYER type, fetched by Citizens)
     * @param skinValue     Base64 texture value (PLAYER type, offline/persistent skin) – wins over {@code skin}
     * @param skinSignature signature belonging to {@code skinValue}
     */
    public record Npc(String id, String role, String quest, Point location, EntityType entityType, String skin, String skinValue, String skinSignature,
                      String model, OptionalInt citizensId) {
        public Npc {
            Objects.requireNonNull(id);
            Objects.requireNonNull(location);
            role = role == null ? "QUEST" : role.toUpperCase(Locale.ROOT);
            quest = quest == null ? "" : quest;
            skin = skin == null ? "" : skin.trim();
            skinValue = skinValue == null ? "" : skinValue.trim();
            skinSignature = skinSignature == null ? "" : skinSignature.trim();
            model = model == null ? "" : model.trim();
            entityType = entityType == null ? EntityType.VILLAGER : entityType;
            citizensId = citizensId == null ? OptionalInt.empty() : citizensId;
        }
    }

    public record Npcs(boolean enabled, Map<String, Npc> list) {
        public Npcs {
            list = Map.copyOf(list);
        }
    }

    /**
     * @param requiredPrestige minimum prestige level to enter the open world
     * @param goldenAreas      golden cookie areas inside the open world (empty = anywhere)
     * @param goldenMode       {@code auto} (activates directly) or {@code spawn} (appears in the world)
     */
    public record OpenWorld(boolean enabled, String name, boolean createIfMissing, int requiredPrestige, Point entry,
                            boolean goldenEnabled, List<LobbyConfiguration.Region> goldenAreas, String goldenMode) {
        public OpenWorld {
            Objects.requireNonNull(name);
            Objects.requireNonNull(entry);
            goldenAreas = goldenAreas == null ? List.of() : List.copyOf(goldenAreas);
            goldenMode = normalizeGoldenMode(goldenMode);
            if (name.isBlank()) {
                throw new IllegalArgumentException("open-world.world must not be blank");
            }
            requiredPrestige = Math.max(0, requiredPrestige);
        }

        public boolean goldenAuto() {
            return GOLDEN_MODE_AUTO.equals(goldenMode);
        }
    }

    static String normalizeGoldenMode(String value) {
        String mode = value == null ? GOLDEN_MODE_AUTO : value.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) {
            return GOLDEN_MODE_AUTO;
        }
        if (!mode.equals(GOLDEN_MODE_AUTO) && !mode.equals(GOLDEN_MODE_SPAWN)) {
            throw new IllegalArgumentException("cookie-clicker.yml: golden-cookies.mode must be 'auto' or 'spawn', got '" + value + "'");
        }
        return mode;
    }

    /**
     * In-place migration of an existing file to {@link #CURRENT_VERSION}; returns the applied changes
     * (empty = nothing to do). Files older than {@link #LAYOUT_VERSION} are not migrated (their layout is
     * replaced by the bundled defaults, see {@link #load}).
     */
    public static List<String> migrate(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml);
        List<String> changes = new ArrayList<>();
        int version = yaml.getInt("config-version", yaml.isSet("world") ? 1 : CURRENT_VERSION);
        if (version < LAYOUT_VERSION || version >= CURRENT_VERSION) {
            return changes;
        }
        if (version < 3) {
            for (String id : REMOVED_NPCS_V3) {
                if (yaml.isConfigurationSection("npcs.list." + id)) {
                    yaml.set("npcs.list." + id, null);
                    changes.add("removed NPC '" + id + "'");
                }
            }
        }
        yaml.set("config-version", CURRENT_VERSION);
        changes.add("config-version " + version + " -> " + CURRENT_VERSION);
        return changes;
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

    public record Runtime(int saveIntervalSeconds, int maxDirtyAgeSeconds, int leaderboardCacheSeconds, int maxSaveFailuresBeforePause) {
        public Runtime {
            saveIntervalSeconds = Math.max(2, saveIntervalSeconds);
            maxDirtyAgeSeconds = Math.max(saveIntervalSeconds, maxDirtyAgeSeconds);
            leaderboardCacheSeconds = Math.max(5, leaderboardCacheSeconds);
            maxSaveFailuresBeforePause = Math.max(3, maxSaveFailuresBeforePause);
        }
    }

    /** Whether the main cookie is placed inside the lobby world (default) rather than the open world. */
    public boolean mainCookieInLobby(String lobbyWorld) {
        return mainCookie.world().equals(lobbyWorld);
    }

    /**
     * @param yaml           the plugin's cookie-clicker.yml (defaults from the bundled resource attached)
     * @param lobbyWorldName lobby world (default world for the main cookie / NPCs)
     */
    public static CookieConfiguration load(YamlConfiguration yaml, String lobbyWorldName) {
        Objects.requireNonNull(yaml);
        Objects.requireNonNull(lobbyWorldName);
        boolean legacy = yaml.getInt("config-version", yaml.isSet("world") ? 1 : CURRENT_VERSION) < LAYOUT_VERSION;
        ConfigurationSection source = yaml;
        if (legacy && yaml.getDefaults() != null) {
            // 1.0.0 layout (main cookie inside the cookie world): use the bundled layout, keep the file's runtime/balancing
            source = yaml.getDefaults();
        }

        ConfigurationSection mainSection = req(source, "main-cookie");
        String mainWorld = mainSection.getString("world", lobbyWorldName);
        Point mainLocation = point(req(mainSection, "location"), mainWorld);
        ConfigurationSection goldenMain = mainSection.getConfigurationSection("golden-cookies");
        MainCookie mainCookie = new MainCookie(mainLocation,
                mainSection.getString("mythicmobs-type", ""), mainSection.getString("click-skill", ""), mainSection.getString("model", ""),
                mainSection.getDouble("hitbox.width", 2.2), mainSection.getDouble("hitbox.height", 2.4), mainSection.getBoolean("label", false),
                mainSection.getDouble("zone-radius", 8.0), mainSection.getLong("actionbar.interval-millis", 1000),
                goldenMain == null || goldenMain.getBoolean("enabled", true), goldenMain == null ? 1 : goldenMain.getInt("max-per-player", 1),
                regions(goldenMain == null ? null : goldenMain.getConfigurationSection("areas"), mainWorld),
                goldenMain == null ? GOLDEN_MODE_AUTO : goldenMain.getString("mode", GOLDEN_MODE_AUTO));

        Map<String, Npc> npcMap = new LinkedHashMap<>();
        ConfigurationSection npcRoot = source.getConfigurationSection("npcs");
        boolean npcsEnabled = npcRoot == null || npcRoot.getBoolean("enabled", true);
        ConfigurationSection npcList = npcRoot == null ? null : npcRoot.getConfigurationSection("list");
        if (npcList != null) {
            for (String id : npcList.getKeys(false)) {
                ConfigurationSection n = npcList.getConfigurationSection(id);
                if (n == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("cookie-clicker.yml: invalid npc id " + id);
                }
                EntityType type = entityType(n.getString("entity-type", "VILLAGER"), id);
                OptionalInt citizensId = n.isInt("citizens-id") && n.getInt("citizens-id") >= 0 ? OptionalInt.of(n.getInt("citizens-id")) : OptionalInt.empty();
                npcMap.put(key, new Npc(key, n.getString("role"), n.getString("quest"), point(req(n, "location"), mainWorld), type,
                        n.getString("skin", ""), n.getString("skin-value", ""), n.getString("skin-signature", ""), n.getString("model", ""), citizensId));
            }
        }
        Npcs npcs = new Npcs(npcsEnabled, npcMap);

        ConfigurationSection openSection = req(source, "open-world");
        String openWorldName = openSection.getString("world", "cookie");
        ConfigurationSection goldenOpen = openSection.getConfigurationSection("golden-cookies");
        OpenWorld openWorld = new OpenWorld(openSection.getBoolean("enabled", true), openWorldName, openSection.getBoolean("create-if-missing", true),
                openSection.getInt("required-prestige", 10), point(req(openSection, "entry"), openWorldName),
                goldenOpen == null || goldenOpen.getBoolean("enabled", true),
                regions(goldenOpen == null ? null : goldenOpen.getConfigurationSection("areas"), openWorldName),
                goldenOpen == null ? GOLDEN_MODE_AUTO : goldenOpen.getString("mode", GOLDEN_MODE_AUTO));

        Map<String, Zone> zones = new LinkedHashMap<>();
        ConfigurationSection zoneSection = source.getConfigurationSection("zones");
        if (zoneSection == null) {
            throw new IllegalArgumentException("cookie-clicker.yml: missing 'zones'");
        }
        for (String id : zoneSection.getKeys(false)) {
            ConfigurationSection z = zoneSection.getConfigurationSection(id);
            if (z == null) continue;
            String key = id.toLowerCase(Locale.ROOT);
            LobbyConfiguration.Region region = region(req(z, "region"), openWorldName);
            Point entry = point(req(z, "entry"), openWorldName);
            Point gate = z.isConfigurationSection("gate-return") ? point(z.getConfigurationSection("gate-return"), openWorldName) : entry;
            if (zones.putIfAbsent(key, new Zone(key, region, entry, gate)) != null) {
                throw new IllegalArgumentException("cookie-clicker.yml: duplicate zone " + id);
            }
        }

        Map<String, Poi> pois = new LinkedHashMap<>();
        ConfigurationSection poiSection = source.getConfigurationSection("pois");
        if (poiSection != null) {
            for (String id : poiSection.getKeys(false)) {
                ConfigurationSection p = poiSection.getConfigurationSection(id);
                if (p == null) continue;
                String poiId = id.startsWith("cookie.") ? id : "cookie." + id;
                if (pois.putIfAbsent(poiId, new Poi(poiId, p.getString("type", "GENERIC"), point(p, openWorldName), p.getDouble("radius", 4.0))) != null) {
                    throw new IllegalArgumentException("cookie-clicker.yml: duplicate poi " + id);
                }
            }
        }

        Runtime runtime = new Runtime(yaml.getInt("runtime.save-interval-seconds", 10), yaml.getInt("runtime.max-dirty-age-seconds", 30),
                yaml.getInt("runtime.leaderboard-cache-seconds", 60), yaml.getInt("runtime.max-save-failures-before-pause", 30));

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
        return new CookieConfiguration(mainCookie, npcs, openWorld, zones, pois, runtime, b.build(), legacy);
    }

    private static EntityType entityType(String name, String npcId) {
        try {
            return EntityType.valueOf(name == null ? "VILLAGER" : name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cookie-clicker.yml: unknown entity-type '" + name + "' for npc " + npcId);
        }
    }

    private static ConfigurationSection req(ConfigurationSection parent, String path) {
        ConfigurationSection s = parent.getConfigurationSection(path);
        if (s == null) throw new IllegalArgumentException("cookie-clicker.yml: missing section " + (parent.getCurrentPath() == null || parent.getCurrentPath().isEmpty() ? "" : parent.getCurrentPath() + ".") + path);
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

    private static List<LobbyConfiguration.Region> regions(ConfigurationSection section, String defaultWorld) {
        List<LobbyConfiguration.Region> regions = new ArrayList<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection g = section.getConfigurationSection(id);
                if (g != null) regions.add(region(g, defaultWorld));
            }
        }
        return regions;
    }
}
