package de.tasticgames.lobby.config;

import de.tasticgames.lobby.TasticLobbyPlugin;
import de.tasticgames.service.Service;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Loads config/*.yml (copies defaults from the JAR on first start), validates fail-fast
 * and exposes typed configuration. Secrets can be supplied via environment variables
 * (TASTIC_API_BASE_URL, TASTIC_API_SERVICE, TASTIC_API_KEY, TASTIC_LOBBY_SERVER_ID).
 * When the lobby has no API key of its own, the credentials of TasticCore
 * ({@code plugins/TasticCore/config/api.yml}) are reused so both plugins talk to the same API.
 */
public final class LobbyConfigurationService implements Service {

    private final TasticLobbyPlugin plugin;
    private final Function<String, String> environment;
    private final File coreApiFile;
    private final Map<String, YamlConfiguration> files = new LinkedHashMap<>();
    private volatile LobbyConfiguration configuration;
    private YamlConfiguration coreApi;

    public LobbyConfigurationService(TasticLobbyPlugin plugin) {
        this(plugin, System::getenv, new File(plugin.getDataFolder().getParentFile(),
                "TasticCore" + File.separator + "config" + File.separator + "api.yml"));
    }

    public LobbyConfigurationService(TasticLobbyPlugin plugin, Function<String, String> environment, File coreApiFile) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.coreApiFile = coreApiFile;
    }

    @Override
    public String id() {
        return "lobby-configuration-service";
    }

    @Override
    public void start() throws IOException {
        reload();
    }

    @Override
    public void stop() {
        files.clear();
        configuration = null;
    }

    public synchronized void reload() throws IOException {
        files.clear();
        coreApi = null;
        for (String name : List.of("lobby", "items", "music", "api", "cookie-clicker", "cosmetics", "hud")) {
            files.put(name, load(name));
        }
        configuration = parse();
    }

    public LobbyConfiguration configuration() {
        LobbyConfiguration current = configuration;
        if (current == null) {
            throw new IllegalStateException("Lobby configuration is not loaded.");
        }
        return current;
    }

    /** Raw access for domain specific loaders (cookie-clicker.yml, cosmetics.yml). */
    public YamlConfiguration raw(String name) {
        YamlConfiguration file = files.get(name);
        if (file == null) {
            throw new IllegalStateException("Unknown configuration file: " + name);
        }
        return file;
    }

    public File configDirectory() {
        return new File(plugin.getDataFolder(), "config");
    }

    // ------------------------------------------------------------------ parsing

    private LobbyConfiguration parse() {
        YamlConfiguration lobby = files.get("lobby");
        YamlConfiguration items = files.get("items");
        YamlConfiguration music = files.get("music");
        YamlConfiguration api = files.get("api");

        ConfigurationSection spawn = require(lobby, "world.spawn");
        String worldName = lobby.getString("world.name", "world");
        LobbyConfiguration.SpawnPoint spawnPoint = new LobbyConfiguration.SpawnPoint(
                spawn.getString("world", worldName), spawn.getDouble("x", 0.5), spawn.getDouble("y", 100), spawn.getDouble("z", 0.5),
                (float) spawn.getDouble("yaw", 0), (float) spawn.getDouble("pitch", 0));
        LobbyConfiguration.World world = new LobbyConfiguration.World(
                worldName, spawnPoint, lobby.getDouble("world.void-rescue-y", 0),
                lobby.getBoolean("world.fixed-time.enabled", true), lobby.getLong("world.fixed-time.ticks", 6000),
                lobby.getBoolean("world.clear-weather", true), lobby.getBoolean("world.disable-mob-spawning", true),
                lobby.getBoolean("world.disable-player-collision", true), lobby.getBoolean("world.suppress-join-quit-messages", true),
                lobby.getBoolean("world.allow-staff-flight", true));

        List<LobbyConfiguration.LaunchpadDefinition> pads = new ArrayList<>();
        ConfigurationSection padSection = lobby.getConfigurationSection("movement.launchpads.directional");
        if (padSection != null) {
            for (String id : padSection.getKeys(false)) {
                ConfigurationSection s = padSection.getConfigurationSection(id);
                if (s == null) continue;
                pads.add(new LobbyConfiguration.LaunchpadDefinition(id, s.getString("world", worldName),
                        s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"), s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"),
                        s.getDouble("velocity.x", 0), s.getDouble("velocity.y", 1.2), s.getDouble("velocity.z", 0)));
            }
        }
        List<LobbyConfiguration.Region> teleportRegions = new ArrayList<>();
        ConfigurationSection regionSection = lobby.getConfigurationSection("movement.teleport-pads.regions");
        if (regionSection != null) {
            for (String id : regionSection.getKeys(false)) {
                ConfigurationSection s = regionSection.getConfigurationSection(id);
                if (s == null) continue;
                teleportRegions.add(new LobbyConfiguration.Region(s.getString("world", worldName),
                        s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"), s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z")));
            }
        }
        LobbyConfiguration.Movement movement = new LobbyConfiguration.Movement(
                lobby.getBoolean("movement.launchpads.enabled", true),
                material(lobby.getString("movement.launchpads.material", "SLIME_BLOCK"), Material.SLIME_BLOCK),
                lobby.getDouble("movement.launchpads.vertical-velocity", 1.35),
                lobby.getDouble("movement.launchpads.forward-velocity", 0.9),
                lobby.getLong("movement.launchpads.cooldown-millis", 600),
                lobby.getString("movement.launchpads.sound", "minecraft:entity.firework_rocket.launch"),
                pads,
                lobby.getBoolean("movement.teleport-pads.enabled", true),
                material(lobby.getString("movement.teleport-pads.material", "GOLD_BLOCK"), Material.GOLD_BLOCK),
                lobby.getBoolean("movement.teleport-pads.everywhere", false),
                teleportRegions,
                lobby.getLong("movement.teleport-pads.cooldown-millis", 1500),
                lobby.getString("movement.teleport-pads.sound", "minecraft:entity.enderman.teleport"),
                lobby.getBoolean("movement.double-jump.enabled", false),
                lobby.getDouble("movement.double-jump.velocity", 0.9),
                lobby.getLong("movement.double-jump.cooldown-millis", 1500));

        Map<String, LobbyConfiguration.ItemSlot> slots = new LinkedHashMap<>();
        ConfigurationSection slotSection = items.getConfigurationSection("items");
        if (slotSection != null) {
            for (String id : slotSection.getKeys(false)) {
                ConfigurationSection s = slotSection.getConfigurationSection(id);
                if (s == null) continue;
                slots.put(id, new LobbyConfiguration.ItemSlot(id, s.getInt("slot"), material(s.getString("material", "STONE"), Material.STONE),
                        s.getString("asset-id", ""), s.getString("itemsadder", ""), s.getBoolean("enabled", true)));
            }
        }
        LobbyConfiguration.Items itemConfig = new LobbyConfiguration.Items(slots, items.getLong("interaction-cooldown-millis", 400));

        LobbyConfiguration.Music musicConfig = new LobbyConfiguration.Music(
                music.getBoolean("enabled", true), tracks(music, "playlists.lobby"), tracks(music, "playlists.cookie"),
                music.getInt("gap-seconds", 8), music.getBoolean("shuffle", true));

        LobbyConfiguration.Api apiConfig = apiConfiguration(api);

        LobbyConfiguration.Telemetry telemetry = new LobbyConfiguration.Telemetry(lobby.getBoolean("telemetry.enabled", true),
                lobby.getInt("telemetry.queue-capacity", 5000), lobby.getInt("telemetry.batch-size", 200),
                lobby.getInt("telemetry.flush-interval-seconds", 5), lobby.getInt("telemetry.position-sample-seconds", 4));

        return new LobbyConfiguration(world, movement, itemConfig, musicConfig, apiConfig, telemetry);
    }

    /**
     * Credential precedence: environment → config/api.yml → TasticCore/config/api.yml. The
     * TasticCore fallback is what makes a freshly installed lobby work without a second key.
     */
    private LobbyConfiguration.Api apiConfiguration(YamlConfiguration api) {
        boolean enabled = api.getBoolean("enabled", true);
        int connect = api.getInt("timeouts.connect-seconds", 5);
        int request = api.getInt("timeouts.request-seconds", 10);
        String serverId = firstNonBlank(env("TASTIC_LOBBY_SERVER_ID"), api.getString("server-id", "lobby"));
        String ownBase = api.getString("base-url", "");
        String ownService = api.getString("authentication.service-name", "");
        String ownKey = api.getString("authentication.api-key", "");
        if (ownKey != null && ownKey.trim().equalsIgnoreCase("CHANGE_ME")) {
            ownKey = "";
        }

        String envKey = env("TASTIC_API_KEY");
        if (!envKey.isBlank()) {
            return new LobbyConfiguration.Api(enabled,
                    firstNonBlank(env("TASTIC_API_BASE_URL"), ownBase, coreValue("base-url")),
                    firstNonBlank(env("TASTIC_API_SERVICE"), ownService, coreValue("authentication.service-name"), "tastic-core-lobby"),
                    envKey, connect, request, serverId, "environment");
        }
        if (ownKey != null && !ownKey.isBlank()) {
            return new LobbyConfiguration.Api(enabled,
                    firstNonBlank(env("TASTIC_API_BASE_URL"), ownBase),
                    firstNonBlank(env("TASTIC_API_SERVICE"), ownService, "tastic-core-lobby"),
                    ownKey, connect, request, serverId, "config/api.yml");
        }
        String coreKey = coreValue("authentication.api-key");
        if (!coreKey.isBlank()) {
            return new LobbyConfiguration.Api(enabled,
                    firstNonBlank(env("TASTIC_API_BASE_URL"), coreValue("base-url"), ownBase),
                    firstNonBlank(env("TASTIC_API_SERVICE"), coreValue("authentication.service-name"), "tastic-core-lobby"),
                    coreKey, connect, request, serverId, "TasticCore/config/api.yml");
        }
        return new LobbyConfiguration.Api(enabled,
                firstNonBlank(env("TASTIC_API_BASE_URL"), ownBase),
                firstNonBlank(env("TASTIC_API_SERVICE"), ownService, "tastic-core-lobby"),
                "", connect, request, serverId, "none");
    }

    /** Value from TasticCore's api.yml (empty when the file is missing/unreadable). Read lazily per reload. */
    private String coreValue(String path) {
        if (coreApi == null) {
            coreApi = new YamlConfiguration();
            if (coreApiFile != null && coreApiFile.isFile()) {
                try (var reader = Files.newBufferedReader(coreApiFile.toPath(), StandardCharsets.UTF_8)) {
                    coreApi.load(reader);
                } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
                    plugin.getLogger().warning("TasticCore api.yml could not be read for the credential fallback: " + e.getMessage());
                }
            }
        }
        String value = coreApi.getString(path, "");
        return value == null ? "" : value.trim();
    }

    private static List<LobbyConfiguration.Track> tracks(YamlConfiguration music, String path) {
        List<LobbyConfiguration.Track> tracks = new ArrayList<>();
        for (Map<?, ?> raw : music.getMapList(path)) {
            Object id = raw.get("id");
            Object sound = raw.get("sound");
            Object duration = raw.get("duration-seconds");
            Object weight = raw.get("weight");
            if (id == null || sound == null || duration == null) {
                throw new IllegalArgumentException("music track needs id, sound and duration-seconds: " + raw);
            }
            tracks.add(new LobbyConfiguration.Track(String.valueOf(id), String.valueOf(sound),
                    Integer.parseInt(String.valueOf(duration)), weight == null ? 1 : Integer.parseInt(String.valueOf(weight))));
        }
        return tracks;
    }

    private static Material material(String name, Material fallback) {
        Material material = Material.matchMaterial(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            throw new IllegalArgumentException("Unknown material in configuration: " + name);
        }
        return material;
    }

    private static ConfigurationSection require(YamlConfiguration file, String path) {
        ConfigurationSection section = file.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Missing configuration section: " + path);
        }
        return section;
    }

    private String env(String name) {
        String value = environment.apply(name);
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private YamlConfiguration load(String name) throws IOException {
        File directory = configDirectory();
        Files.createDirectories(directory.toPath());
        File file = new File(directory, name + ".yml");
        if (!file.exists()) {
            try (InputStream in = plugin.getResource("config/" + name + ".yml")) {
                if (in == null) {
                    throw new IOException("Missing default configuration resource config/" + name + ".yml");
                }
                Files.copy(in, file.toPath());
            }
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            configuration.load(reader);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException("Invalid YAML in config/" + name + ".yml: " + e.getMessage(), e);
        }
        // defaults from the bundled resource so newer keys are always available
        try (InputStream in = plugin.getResource("config/" + name + ".yml")) {
            if (in != null) {
                configuration.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        }
        return configuration;
    }
}
