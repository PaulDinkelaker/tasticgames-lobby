package de.tasticgames.lobby.integration.cosmetic;

import com.hibiscusmc.hmccosmetics.api.HMCCosmeticsAPI;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import de.tasticgames.lobby.cosmetic.CosmeticCategory;
import de.tasticgames.lobby.cosmetic.CosmeticDefinition;
import de.tasticgames.lobby.cosmetic.CosmeticRenderer;
import de.tasticgames.lobby.integration.Integration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Renders TasticGames cosmetics through HMCCosmetics: catalog entries whose {@code render} data is
 * {@code hmc:<hmccosmetics-id>} are equipped/unequipped via the HMCCosmetics API. Ownership stays
 * with the TasticGames API (no permission checks here).
 */
public final class HmcCosmeticsRenderer implements CosmeticRenderer, Integration {

    public static final String PREFIX = "hmc:";

    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, EnumMap<CosmeticCategory, String>> equipped = new ConcurrentHashMap<>();
    private final java.util.Set<String> unknownWarned = ConcurrentHashMap.newKeySet();
    private volatile boolean available;
    private volatile boolean warned;

    public HmcCosmeticsRenderer(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    public void hook() {
        if (!Integration.pluginEnabled(pluginName())) {
            return;
        }
        try {
            HMCCosmeticsAPI.getAllCosmetics();
            available = true;
        } catch (Throwable t) {
            available = false;
            logger.warning("HMCCosmetics hook failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – native cosmetics only.");
        }
    }

    public void unhook() {
        equipped.clear();
        available = false;
    }

    @Override
    public String pluginName() {
        return "HMCCosmetics";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String id() {
        return "hmccosmetics";
    }

    /** True for every category – the HMC cosmetic itself decides the slot. */
    @Override
    public boolean supports(CosmeticCategory category) {
        return available;
    }

    /** Whether the definition is rendered by HMCCosmetics. */
    public static boolean handles(CosmeticDefinition definition) {
        return definition != null && definition.renderData().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static String hmcId(CosmeticDefinition definition) {
        return definition.renderData().substring(PREFIX.length()).trim();
    }

    @Override
    public void apply(Player player, CosmeticCategory category, CosmeticDefinition definition, boolean reducedEffects) {
        if (!available || player == null) return;
        EnumMap<CosmeticCategory, String> mine = equipped.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(CosmeticCategory.class));
        String previous = mine.get(category);
        if (!handles(definition)) {
            if (previous != null) {
                unequip(player, previous);
                mine.remove(category);
            }
            return;
        }
        String id = hmcId(definition);
        if (id.equals(previous)) {
            ensureEquipped(player, id);
            return;
        }
        if (previous != null) {
            unequip(player, previous);
            mine.remove(category);
        }
        if (equip(player, id)) {
            mine.put(category, id);
        }
    }

    private boolean equip(Player player, String id) {
        try {
            Cosmetic cosmetic = HMCCosmeticsAPI.getCosmetic(id);
            if (cosmetic == null) {
                if (unknownWarned.add(id)) {
                    logger.warning("HMCCosmetics cosmetic '" + id + "' referenced by cosmetics.yml does not exist.");
                }
                return false;
            }
            CosmeticUser user = HMCCosmeticsAPI.getUser(player.getUniqueId());
            if (user == null) {
                return false; // user data not loaded yet; the next render pass retries
            }
            Cosmetic current = user.getCosmetic(cosmetic.getSlot());
            if (current != null && id.equals(current.getId())) {
                return true;
            }
            HMCCosmeticsAPI.equipCosmetic(user, cosmetic);
            return true;
        } catch (Throwable t) {
            warnOnce("equip " + id, t);
            return false;
        }
    }

    private void ensureEquipped(Player player, String id) {
        try {
            Cosmetic cosmetic = HMCCosmeticsAPI.getCosmetic(id);
            CosmeticUser user = cosmetic == null ? null : HMCCosmeticsAPI.getUser(player.getUniqueId());
            if (user == null) return;
            Cosmetic current = user.getCosmetic(cosmetic.getSlot());
            if (current == null || !id.equals(current.getId())) {
                HMCCosmeticsAPI.equipCosmetic(user, cosmetic);
            }
        } catch (Throwable t) {
            warnOnce("re-equip " + id, t);
        }
    }

    private void unequip(Player player, String id) {
        try {
            Cosmetic cosmetic = HMCCosmeticsAPI.getCosmetic(id);
            CosmeticUser user = HMCCosmeticsAPI.getUser(player.getUniqueId());
            if (cosmetic == null || user == null) return;
            Cosmetic current = user.getCosmetic(cosmetic.getSlot());
            if (current != null && id.equals(current.getId())) {
                HMCCosmeticsAPI.unequipCosmetic(user, cosmetic.getSlot());
            }
        } catch (Throwable t) {
            warnOnce("unequip " + id, t);
        }
    }

    @Override
    public void clear(Player player) {
        if (player == null) return;
        EnumMap<CosmeticCategory, String> mine = equipped.remove(player.getUniqueId());
        if (mine == null || !available) return;
        for (String id : mine.values()) {
            unequip(player, id);
        }
    }

    public boolean cosmeticExists(String hmcId) {
        if (!available || hmcId == null || hmcId.isBlank()) return false;
        try {
            return HMCCosmeticsAPI.getCosmetic(hmcId) != null;
        } catch (Throwable t) {
            warnOnce("lookup", t);
            return false;
        }
    }

    public List<String> cosmeticIds() {
        if (!available) return List.of();
        try {
            List<String> ids = new ArrayList<>();
            for (Cosmetic cosmetic : HMCCosmeticsAPI.getAllCosmetics()) {
                ids.add(cosmetic.getId());
            }
            Collections.sort(ids);
            return ids;
        } catch (Throwable t) {
            warnOnce("list", t);
            return List.of();
        }
    }

    public Optional<String> slotOf(String hmcId) {
        if (!available) return Optional.empty();
        try {
            Cosmetic cosmetic = HMCCosmeticsAPI.getCosmetic(hmcId);
            CosmeticSlot slot = cosmetic == null ? null : cosmetic.getSlot();
            return slot == null ? Optional.empty() : Optional.of(slot.getName());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private void warnOnce(String operation, Throwable t) {
        if (!warned) {
            warned = true;
            logger.warning("HMCCosmetics " + operation + " failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") – further failures are silent.");
        }
    }
}
