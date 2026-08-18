package de.tasticgames.lobby.config;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed, validated lobby configuration (lobby.yml, items.yml, music.yml).
 * Cookie clicker and cosmetics have their own configuration classes.
 */
public record LobbyConfiguration(
        World world,
        Movement movement,
        Items items,
        Music music,
        Api api,
        Telemetry telemetry
) {

    public LobbyConfiguration {
        Objects.requireNonNull(world);
        Objects.requireNonNull(movement);
        Objects.requireNonNull(items);
        Objects.requireNonNull(music);
        Objects.requireNonNull(api);
        Objects.requireNonNull(telemetry);
    }

    public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {
        public SpawnPoint {
            Objects.requireNonNull(world, "world");
        }

        public Location toLocation(org.bukkit.World bukkitWorld) {
            return new Location(bukkitWorld, x, y, z, yaw, pitch);
        }
    }

    public record World(
            String name,
            SpawnPoint spawn,
            double voidRescueY,
            boolean fixedTime,
            long fixedTimeTicks,
            boolean clearWeather,
            boolean disableMobSpawning,
            boolean disablePlayerCollision,
            boolean suppressJoinQuitMessages,
            boolean allowStaffFlight,
            boolean disableLocatorBar
    ) {
        public World {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(spawn, "spawn");
            if (name.isBlank()) {
                throw new IllegalArgumentException("world.name must not be blank");
            }
        }
    }

    public record LaunchpadDefinition(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                      double velocityX, double velocityY, double velocityZ) {
        public boolean contains(Location location) {
            return location.getWorld() != null && location.getWorld().getName().equals(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }
    }

    public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(Location location) {
            return location.getWorld() != null && location.getWorld().getName().equals(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }
    }

    public record Movement(
            boolean launchpadsEnabled,
            Material launchpadMaterial,
            double launchVerticalVelocity,
            double launchForwardVelocity,
            long launchCooldownMillis,
            String launchSound,
            List<LaunchpadDefinition> directionalLaunchpads,
            boolean teleportPadsEnabled,
            Material teleportPadMaterial,
            boolean teleportPadsEverywhere,
            List<Region> teleportPadRegions,
            long teleportCooldownMillis,
            String teleportSound,
            boolean doubleJumpEnabled,
            double doubleJumpVelocity,
            long doubleJumpCooldownMillis
    ) {
        public Movement {
            Objects.requireNonNull(launchpadMaterial);
            Objects.requireNonNull(teleportPadMaterial);
            directionalLaunchpads = directionalLaunchpads == null ? List.of() : List.copyOf(directionalLaunchpads);
            teleportPadRegions = teleportPadRegions == null ? List.of() : List.copyOf(teleportPadRegions);
            if (launchVerticalVelocity <= 0) {
                throw new IllegalArgumentException("movement.launchpad.vertical-velocity must be positive");
            }
        }
    }

    /**
     * @param assetId      optional vanilla item model key (resource pack), e.g. {@code tasticgames:gateway_compass}
     * @param customItemId optional ItemsAdder item id ({@code namespace:id}); used when ItemsAdder is present
     */
    public record ItemSlot(String id, int slot, Material material, String assetId, String customItemId, boolean enabled) {
        public ItemSlot {
            Objects.requireNonNull(id);
            Objects.requireNonNull(material);
            assetId = assetId == null ? "" : assetId.trim();
            customItemId = customItemId == null ? "" : customItemId.trim();
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException("Lobby item slot must be 0..8: " + id);
            }
        }
    }

    public record Items(Map<String, ItemSlot> slots, long interactionCooldownMillis) {
        public Items {
            slots = Map.copyOf(slots);
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (ItemSlot slot : slots.values()) {
                if (slot.enabled() && !used.add(slot.slot())) {
                    throw new IllegalArgumentException("Duplicate lobby item slot " + slot.slot());
                }
            }
        }
    }

    public record Track(String id, String soundKey, int durationSeconds, int weight) {
        public Track {
            Objects.requireNonNull(id);
            Objects.requireNonNull(soundKey);
            if (durationSeconds <= 0) {
                throw new IllegalArgumentException("music track duration must be positive: " + id);
            }
        }
    }

    /**
     * @param ostEnabled        install and play the soundtrack from {@code plugins/TasticLobby/ost/}
     * @param ostSilenceVanilla replace Minecraft's own music events with silence, so the two never overlap
     * @param ostAutoPlaylist   build the playlist from the installed tracks instead of the lists below
     */
    public record Music(boolean enabled, List<Track> lobbyPlaylist, List<Track> cookiePlaylist, int gapSeconds, boolean shuffle,
                        boolean ostEnabled, boolean ostSilenceVanilla, boolean ostAutoPlaylist) {
        public Music {
            lobbyPlaylist = lobbyPlaylist == null ? List.of() : List.copyOf(lobbyPlaylist);
            cookiePlaylist = cookiePlaylist == null ? List.of() : List.copyOf(cookiePlaylist);
        }
    }

    /**
     * @param credentialSource where base url/key came from: {@code environment}, {@code config/api.yml},
     *                         {@code TasticCore/config/api.yml} or {@code none}
     */
    public record Api(boolean enabled, String baseUrl, String serviceName, String apiKey, int connectTimeoutSeconds,
                      int requestTimeoutSeconds, String backendServerId, String credentialSource) {
        public Api {
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            serviceName = serviceName == null ? "" : serviceName.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            backendServerId = backendServerId == null ? "lobby" : backendServerId.trim().toLowerCase(java.util.Locale.ROOT);
            credentialSource = credentialSource == null ? "none" : credentialSource;
        }

        public boolean credentialsConfigured() {
            return enabled && !baseUrl.isBlank() && !serviceName.isBlank() && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("CHANGE_ME");
        }
    }

    public record Telemetry(boolean enabled, int queueCapacity, int batchSize, int flushIntervalSeconds, int positionSampleSeconds) {
    }
}
