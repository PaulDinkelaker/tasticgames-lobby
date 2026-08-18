package de.tasticgames.lobby.npc;

import de.tasticgames.client.dto.network.ServerTypeResponse;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * npcs.yml: the lobby service NPCs (season pass, game modes, games and events). Identity, position
 * and action are data – builders move them without code changes. Completely independent from the
 * Cookie Clicker quest NPCs. Yaw convention: 0 = south, 90 = west, 180 = north, -90 = east.
 */
public record LobbyNpcConfiguration(boolean enabled, boolean lookClose, Map<String, Npc> npcs) {

    public LobbyNpcConfiguration {
        npcs = Map.copyOf(npcs);
    }

    /**
     * @param target        transfer destination, required for {@link LobbyNpcAction#TRANSFER}
     * @param world         world the NPC stands in (defaults to the lobby world)
     * @param skinName      Minecraft player name whose skin is fetched when no texture pair is configured
     * @param skinValue     Base64 texture value (offline/persistent skin) – wins over {@code skinName}
     * @param skinSignature signature belonging to {@code skinValue}
     * @param mirrorSkin    every player sees their own skin (Citizens MirrorTrait) – wins over both skin fields
     */
    public record Npc(String id, LobbyNpcAction action, ServerTypeResponse target, String world, double x, double y, double z, float yaw,
                      String skinName, String skinValue, String skinSignature, boolean mirrorSkin) {
        public Npc {
            Objects.requireNonNull(id);
            Objects.requireNonNull(action);
            Objects.requireNonNull(world);
            skinName = skinName == null ? "" : skinName.trim();
            skinValue = skinValue == null ? "" : skinValue.trim();
            skinSignature = skinSignature == null ? "" : skinSignature.trim();
            if (action == LobbyNpcAction.TRANSFER && target == null) {
                throw new IllegalArgumentException("npcs.yml: npc '" + id + "' with action TRANSFER needs a target");
            }
        }

        public Location toLocation(World w) {
            return new Location(w, x, y, z, yaw, 0f);
        }
    }

    /**
     * @param yaml           the plugin's npcs.yml (defaults from the bundled resource attached)
     * @param lobbyWorldName lobby world, used when an NPC does not name its own world
     */
    public static LobbyNpcConfiguration load(YamlConfiguration yaml, String lobbyWorldName) {
        Objects.requireNonNull(yaml);
        Objects.requireNonNull(lobbyWorldName);
        Map<String, Npc> npcs = new LinkedHashMap<>();
        ConfigurationSection list = yaml.getConfigurationSection("npcs");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection n = list.getConfigurationSection(id);
                if (n == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("npcs.yml: invalid npc id " + id);
                }
                if (!n.isSet("x") || !n.isSet("y") || !n.isSet("z")) {
                    throw new IllegalArgumentException("npcs.yml: npc '" + id + "' needs x, y and z");
                }
                ConfigurationSection skin = n.getConfigurationSection("skin");
                Npc npc = new Npc(key, action(n.getString("action"), id), target(n.getString("target"), id),
                        n.getString("world", lobbyWorldName), n.getDouble("x"), n.getDouble("y"), n.getDouble("z"),
                        (float) n.getDouble("yaw", 0), n.getString("skin-name", ""),
                        skin == null ? "" : skin.getString("value", ""), skin == null ? "" : skin.getString("signature", ""),
                        n.getBoolean("mirror-skin", false));
                if (npcs.putIfAbsent(key, npc) != null) {
                    throw new IllegalArgumentException("npcs.yml: duplicate npc " + id);
                }
            }
        }
        return new LobbyNpcConfiguration(yaml.getBoolean("enabled", true), yaml.getBoolean("look-close", true), npcs);
    }

    private static LobbyNpcAction action(String value, String npcId) {
        try {
            return LobbyNpcAction.valueOf(value == null ? "NONE" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("npcs.yml: unknown action '" + value + "' for npc " + npcId);
        }
    }

    private static ServerTypeResponse target(String value, String npcId) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ServerTypeResponse.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("npcs.yml: unknown target '" + value + "' for npc " + npcId);
        }
    }
}
