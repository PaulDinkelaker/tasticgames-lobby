package de.tasticgames.lobby.cookie;

import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.cookie.domain.catalog.CookieBalancing;
import de.tasticgames.lobby.cookie.domain.catalog.SpecialCookieTuning;
import de.tasticgames.lobby.cookie.domain.model.GoldenRewardType;
import de.tasticgames.lobby.cookie.domain.model.SpecialCookieRarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * cookie-clicker.yml (config-version 4): main cookie in the lobby world, quest NPCs, the
 * prestige-10 open world with zones/POIs, runtime and balancing. Layout is data – builders
 * replace coordinates without code changes. Files are migrated in place ({@link #migrate}):
 * version 5 removed the quest NPCs altogether,
 * version 3 removed the NPCs {@code babette} and {@code king_frosting}, version 4 renamed the
 * {@code golden-cookies} sections to {@code special-cookies} and replaced
 * {@code balancing.golden} with {@code balancing.special} (rarities instead of one golden cookie).
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

    public static final int CURRENT_VERSION = 5;
    /** First version with the current layout (main cookie in the lobby); older files are replaced by the bundled defaults. */
    public static final int LAYOUT_VERSION = 2;
    /** NPC ids removed with config-version 3 (only the baker and the merchant remain). */
    public static final List<String> REMOVED_NPCS_V3 = List.of("babette", "king_frosting");
    /** Quest NPCs dropped with config-version 5; NPCs the operator added stay untouched. */
    public static final List<String> REMOVED_NPCS_V5 = List.of("mama_bakewell", "gustave");
    /** Section renamed to {@link #SPECIAL_SECTION} with config-version 4; still read as a fallback. */
    public static final String GOLDEN_SECTION = "golden-cookies";
    public static final String SPECIAL_SECTION = "special-cookies";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_SPAWN = "spawn";

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
     * @param specialAreas     special cookies only trigger inside these regions (empty = anywhere in the cookie's world)
     * @param specialMode      {@code auto} = the special cookie activates for the player directly, {@code spawn} = it appears
     *                         nearby in the world and has to be clicked
     */
    public record MainCookie(Point location, String mythicMobsType, String clickSkill, String model, double hitboxWidth, double hitboxHeight, boolean label,
                             double zoneRadius, long actionbarIntervalMillis, boolean specialEnabled,
                             List<LobbyConfiguration.Region> specialAreas, String specialMode) {
        public MainCookie {
            Objects.requireNonNull(location);
            mythicMobsType = mythicMobsType == null ? "" : mythicMobsType.trim();
            clickSkill = clickSkill == null ? "" : clickSkill.trim();
            model = model == null ? "" : model.trim();
            specialAreas = specialAreas == null ? List.of() : List.copyOf(specialAreas);
            specialMode = normalizeMode(specialMode);
            if (hitboxWidth <= 0 || hitboxHeight <= 0) {
                throw new IllegalArgumentException("main-cookie.hitbox must be positive");
            }
            if (zoneRadius <= 0) {
                throw new IllegalArgumentException("main-cookie.zone-radius must be positive");
            }
            actionbarIntervalMillis = Math.max(250, actionbarIntervalMillis);
        }

        public String world() {
            return location.world();
        }

        public boolean specialAuto() {
            return MODE_AUTO.equals(specialMode);
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
     * @param specialAreas     special cookie areas inside the open world (empty = anywhere)
     * @param specialMode      {@code auto} (activates directly) or {@code spawn} (appears in the world)
     */
    public record OpenWorld(boolean enabled, String name, boolean createIfMissing, int requiredPrestige, Point entry,
                            boolean specialEnabled, List<LobbyConfiguration.Region> specialAreas, String specialMode) {
        public OpenWorld {
            Objects.requireNonNull(name);
            Objects.requireNonNull(entry);
            specialAreas = specialAreas == null ? List.of() : List.copyOf(specialAreas);
            specialMode = normalizeMode(specialMode);
            if (name.isBlank()) {
                throw new IllegalArgumentException("open-world.world must not be blank");
            }
            requiredPrestige = Math.max(0, requiredPrestige);
        }

        public boolean specialAuto() {
            return MODE_AUTO.equals(specialMode);
        }
    }

    static String normalizeMode(String value) {
        String mode = value == null ? MODE_AUTO : value.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) {
            return MODE_AUTO;
        }
        if (!mode.equals(MODE_AUTO) && !mode.equals(MODE_SPAWN)) {
            throw new IllegalArgumentException("cookie-clicker.yml: " + SPECIAL_SECTION + ".mode must be 'auto' or 'spawn', got '" + value + "'");
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
        if (version < 4) {
            for (String parent : List.of("main-cookie", "open-world")) {
                if (moveSection(yaml, parent + "." + GOLDEN_SECTION, parent + "." + SPECIAL_SECTION)) {
                    changes.add("renamed " + parent + "." + GOLDEN_SECTION + " -> " + SPECIAL_SECTION);
                }
            }
            if (yaml.isSet("balancing.golden.lifetime-seconds")) {
                yaml.set("balancing.special.lifetime-seconds", yaml.getInt("balancing.golden.lifetime-seconds"));
            }
            if (yaml.isSet("balancing.golden.buff-seconds")) {
                // the single golden buff duration becomes the GOLDEN rarity's frenzy duration
                yaml.set("balancing.special.rarities.golden.frenzy.seconds", yaml.getInt("balancing.golden.buff-seconds"));
            }
            if (yaml.isSet("balancing.golden")) {
                yaml.set("balancing.golden", null);
                // base-interval-seconds has no successor: the wait is now drawn from min/max/floor-interval-seconds
                changes.add("replaced balancing.golden with balancing.special (base-interval-seconds dropped, "
                        + "the wait is now drawn from min/max/floor-interval-seconds)");
            }
        }
        if (version < 5) {
            // the quest NPCs were dropped: the Cookie Clicker is played at the main cookie and in its menus
            for (String id : REMOVED_NPCS_V5) {
                if (yaml.isConfigurationSection("npcs.list." + id)) {
                    yaml.set("npcs.list." + id, null);
                    changes.add("removed NPC '" + id + "'");
                }
            }
            // the section itself is switched off; an operator who wants NPCs back turns it on again
            yaml.set("npcs.enabled", false);
        }
        yaml.set("config-version", CURRENT_VERSION);
        changes.add("config-version " + version + " -> " + CURRENT_VERSION);
        return changes;
    }

    /** Moves every value of {@code from} under {@code to} and deletes the old section. Returns whether anything moved. */
    private static boolean moveSection(YamlConfiguration yaml, String from, String to) {
        ConfigurationSection section = yaml.getConfigurationSection(from);
        if (section == null) {
            return false;
        }
        for (String key : section.getKeys(true)) {
            Object value = section.get(key);
            if (!(value instanceof ConfigurationSection)) {
                yaml.set(to + "." + key, value);
            }
        }
        yaml.set(from, null);
        return true;
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
        ConfigurationSection specialMain = specialSection(mainSection);
        MainCookie mainCookie = new MainCookie(mainLocation,
                mainSection.getString("mythicmobs-type", ""), mainSection.getString("click-skill", ""), mainSection.getString("model", ""),
                mainSection.getDouble("hitbox.width", 2.2), mainSection.getDouble("hitbox.height", 2.4), mainSection.getBoolean("label", false),
                mainSection.getDouble("zone-radius", 8.0), mainSection.getLong("actionbar.interval-millis", 1000),
                specialMain == null || specialMain.getBoolean("enabled", true),
                regions(specialMain == null ? null : specialMain.getConfigurationSection("areas"), mainWorld),
                specialMain == null ? MODE_AUTO : specialMain.getString("mode", MODE_AUTO));

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
        ConfigurationSection specialOpen = specialSection(openSection);
        OpenWorld openWorld = new OpenWorld(openSection.getBoolean("enabled", true), openWorldName, openSection.getBoolean("create-if-missing", true),
                openSection.getInt("required-prestige", 10), point(req(openSection, "entry"), openWorldName),
                specialOpen == null || specialOpen.getBoolean("enabled", true),
                regions(specialOpen == null ? null : specialOpen.getConfigurationSection("areas"), openWorldName),
                specialOpen == null ? MODE_AUTO : specialOpen.getString("mode", MODE_AUTO));

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
            special(b, bal);
        }
        return new CookieConfiguration(mainCookie, npcs, openWorld, zones, pois, runtime, b.build(), legacy);
    }

    /**
     * {@code special-cookies} of a layout section, falling back to the pre-4 {@code golden-cookies}
     * of a file that was not migrated yet. Empty sections are ignored: asking for a section the
     * bundled defaults define materializes an empty one, which would hide the file's own settings.
     */
    private static ConfigurationSection specialSection(ConfigurationSection parent) {
        ConfigurationSection section = parent.getConfigurationSection(SPECIAL_SECTION);
        if (section != null && !section.getKeys(false).isEmpty()) {
            return section;
        }
        ConfigurationSection legacy = parent.getConfigurationSection(GOLDEN_SECTION);
        if (legacy != null && !legacy.getKeys(false).isEmpty()) {
            return legacy;
        }
        return section != null ? section : legacy;
    }

    /** Applies {@code balancing.special} (intervals, spawn lifetime and the per-rarity tuning). */
    private static void special(CookieBalancing.Builder b, ConfigurationSection bal) {
        ConfigurationSection special = bal.getConfigurationSection("special");
        if (special == null || special.getKeys(false).isEmpty()) {
            ConfigurationSection legacy = bal.getConfigurationSection("golden"); // pre-4, not migrated yet
            if (legacy != null && !legacy.getKeys(false).isEmpty()) special = legacy;
        }
        if (special == null) {
            return;
        }
        if (special.contains("min-interval-seconds")) b.specialMinIntervalSeconds(special.getLong("min-interval-seconds"));
        if (special.contains("max-interval-seconds")) b.specialMaxIntervalSeconds(special.getLong("max-interval-seconds"));
        if (special.contains("floor-interval-seconds")) b.specialFloorIntervalSeconds(special.getLong("floor-interval-seconds"));
        if (special.contains("lifetime-seconds")) b.specialLifetimeSeconds(special.getInt("lifetime-seconds"));
        if (special.contains("click-clamp-cps-seconds")) b.specialClickClampCpsSeconds(special.getLong("click-clamp-cps-seconds"));
        ConfigurationSection rarities = special.getConfigurationSection("rarities");
        if (rarities == null) {
            return;
        }
        for (String key : rarities.getKeys(false)) {
            ConfigurationSection r = rarities.getConfigurationSection(key);
            if (r == null) continue;
            SpecialCookieRarity rarity = rarity(key);
            b.rarity(rarity, tuning(b.rarity(rarity).toBuilder(), r).build());
        }
    }

    private static SpecialCookieRarity rarity(String key) {
        try {
            return SpecialCookieRarity.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cookie-clicker.yml: unknown special cookie rarity '" + key + "'");
        }
    }

    private static SpecialCookieTuning.Builder tuning(SpecialCookieTuning.Builder t, ConfigurationSection r) {
        if (r.contains("weight")) t.weight(r.getDouble("weight"));
        ConfigurationSection weights = r.getConfigurationSection("reward-weights");
        if (weights != null && !weights.getKeys(false).isEmpty()) {
            Map<GoldenRewardType, Integer> map = new LinkedHashMap<>();
            for (String type : weights.getKeys(false)) {
                try {
                    map.put(GoldenRewardType.valueOf(type.trim().toUpperCase(Locale.ROOT)), weights.getInt(type));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("cookie-clicker.yml: unknown special cookie reward type '" + type + "'");
                }
            }
            t.rewardWeights(map);
        }
        if (r.contains("lucky.bank-fraction")) t.luckyBankFraction(r.getDouble("lucky.bank-fraction"));
        if (r.contains("lucky.cps-seconds")) t.luckyCpsSeconds(r.getLong("lucky.cps-seconds"));
        if (r.contains("lucky.flat-bonus")) t.luckyFlatBonus(r.getLong("lucky.flat-bonus"));
        if (r.contains("chain.bank-fraction")) t.chainBankFraction(r.getDouble("chain.bank-fraction"));
        if (r.contains("chain.cps-seconds")) t.chainCpsSeconds(r.getLong("chain.cps-seconds"));
        if (r.contains("frenzy.multiplier")) t.frenzyMultiplier(r.getDouble("frenzy.multiplier"));
        if (r.contains("frenzy.seconds")) t.frenzySeconds(r.getInt("frenzy.seconds"));
        if (r.contains("click-frenzy.multiplier")) t.clickFrenzyMultiplier(r.getDouble("click-frenzy.multiplier"));
        if (r.contains("click-frenzy.seconds")) t.clickFrenzySeconds(r.getInt("click-frenzy.seconds"));
        if (r.contains("blessing.cps-multiplier")) t.blessingCpsMultiplier(r.getDouble("blessing.cps-multiplier"));
        if (r.contains("blessing.click-multiplier")) t.blessingClickMultiplier(r.getDouble("blessing.click-multiplier"));
        if (r.contains("blessing.seconds")) t.blessingSeconds(r.getInt("blessing.seconds"));
        return t;
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
