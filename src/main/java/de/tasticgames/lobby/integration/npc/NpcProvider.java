package de.tasticgames.lobby.integration.npc;

import de.tasticgames.lobby.integration.Integration;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * NPC backend (Citizens preferred, native entities as fallback). Quest logic lives outside; the
 * provider only spawns/links entities and reports clicks.
 */
public interface NpcProvider extends Integration {

    /**
     * @param id          stable logical id (config key)
     * @param displayName name shown above the NPC
     * @param location    spawn location (world must be loaded)
     * @param entityType  entity type when a new NPC is created (PLAYER for player-skinned NPCs)
     * @param skin          optional Minecraft player name whose skin is used (PLAYER type only), may be empty
     * @param skinValue     optional Base64 texture value (PLAYER type); with {@code skinSignature} it wins over {@code skin}
     * @param skinSignature signature for {@code skinValue}
     * @param model         optional ModelEngine model id to attach, may be empty
     * @param externalId    optional id of an NPC created by an admin in the backend (Citizens id); when
     *                      present the NPC is linked instead of created and never destroyed by us
     */
    record NpcSpec(String id, Component displayName, Location location, EntityType entityType, String skin, String skinValue, String skinSignature,
                   String model, OptionalInt externalId) {
        public boolean hasSkinData() {
            return skinValue != null && !skinValue.isBlank() && skinSignature != null && !skinSignature.isBlank();
        }
    }

    /** @param externalId backend id (Citizens id) or -1 */
    record NpcHandle(String id, UUID entityId, int externalId, boolean linked) {
    }

    @FunctionalInterface
    interface ClickHandler {
        /** Called on the main thread. */
        void onClick(String npcId, org.bukkit.entity.Player player, boolean leftClick);
    }

    /** Spawns or links the NPC; returns empty when the backend could not do it (caller may fall back). */
    Optional<NpcHandle> spawn(NpcSpec spec);

    void remove(NpcHandle handle);

    void removeAll();

    void onClick(ClickHandler handler);

    /** Logical id when the entity belongs to one of our NPCs. */
    Optional<String> npcIdOf(Entity entity);

    /** Whether the entity is any NPC of the backend (ours or foreign), used to skip protection/damage logic. */
    boolean isNpc(Entity entity);

    int count();
}
