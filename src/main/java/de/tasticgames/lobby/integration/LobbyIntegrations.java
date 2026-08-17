package de.tasticgames.lobby.integration;

import de.tasticgames.lobby.integration.cosmetic.HmcCosmeticsRenderer;
import de.tasticgames.lobby.integration.item.CustomItemProvider;
import de.tasticgames.lobby.integration.item.ItemsAdderItemProvider;
import de.tasticgames.lobby.integration.mob.MobProvider;
import de.tasticgames.lobby.integration.mob.MythicMobsMobProvider;
import de.tasticgames.lobby.integration.model.ModelEngineModelProvider;
import de.tasticgames.lobby.integration.model.ModelProvider;
import de.tasticgames.lobby.integration.npc.CitizensNpcProvider;
import de.tasticgames.lobby.integration.npc.NativeNpcProvider;
import de.tasticgames.lobby.integration.npc.NpcProvider;
import de.tasticgames.lobby.integration.placeholder.PlaceholderBridge;
import de.tasticgames.lobby.integration.placeholder.TabPlaceholderBridge;
import de.tasticgames.lobby.integration.rank.BukkitRankProvider;
import de.tasticgames.lobby.integration.rank.LuckPermsRankProvider;
import de.tasticgames.lobby.integration.rank.RankProvider;
import de.tasticgames.lobby.integration.selection.SelectionProvider;
import de.tasticgames.lobby.integration.selection.WorldEditSelectionProvider;
import de.tasticgames.service.Service;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Hooks every optional third-party integration (soft dependencies) and exposes the active
 * backend per capability. Missing plugins degrade to native fallbacks; a failing hook never
 * prevents the lobby from starting.
 */
public final class LobbyIntegrations implements Service {

    private final Plugin plugin;
    private final Logger logger;

    private LuckPermsRankProvider luckPerms;
    private BukkitRankProvider bukkitRanks;
    private TabPlaceholderBridge tab;
    private ModelEngineModelProvider modelEngine;
    private MythicMobsMobProvider mythicMobs;
    private CitizensNpcProvider citizens;
    private NativeNpcProvider nativeNpcs;
    private HmcCosmeticsRenderer hmcCosmetics;
    private ItemsAdderItemProvider itemsAdder;
    private WorldEditSelectionProvider worldEdit;

    public LobbyIntegrations(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-integrations";
    }

    @Override
    public void start() {
        luckPerms = new LuckPermsRankProvider(plugin, logger);
        luckPerms.hook();
        bukkitRanks = new BukkitRankProvider(logger);
        tab = new TabPlaceholderBridge(plugin, logger);
        tab.hook();
        modelEngine = new ModelEngineModelProvider(plugin, logger);
        modelEngine.hook();
        mythicMobs = new MythicMobsMobProvider(plugin, logger);
        mythicMobs.hook();
        citizens = new CitizensNpcProvider(plugin, logger, modelEngine::attach);
        citizens.hook();
        nativeNpcs = new NativeNpcProvider(plugin, logger, modelEngine::attach);
        nativeNpcs.hook();
        hmcCosmetics = new HmcCosmeticsRenderer(plugin, logger);
        hmcCosmetics.hook();
        itemsAdder = new ItemsAdderItemProvider(plugin, logger);
        itemsAdder.hook();
        worldEdit = new WorldEditSelectionProvider(plugin, logger);
        worldEdit.hook();
        logger.info("Integrations: " + summary());
    }

    @Override
    public void stop() {
        for (Runnable unhook : new Runnable[]{worldEdit::unhook, itemsAdder::unhook, hmcCosmetics::unhook, nativeNpcs::unhook, citizens::unhook,
                mythicMobs::unhook, modelEngine::unhook, tab::unhook, luckPerms::unhook}) {
            try {
                unhook.run();
            } catch (RuntimeException e) {
                logger.warning("Integration shutdown failed: " + e.getMessage());
            }
        }
    }

    public RankProvider ranks() {
        return luckPerms.available() ? luckPerms : bukkitRanks;
    }

    public PlaceholderBridge tab() {
        return tab;
    }

    public ModelProvider models() {
        return modelEngine;
    }

    public MobProvider mobs() {
        return mythicMobs;
    }

    public NpcProvider npcs() {
        return citizens.available() ? citizens : nativeNpcs;
    }

    public HmcCosmeticsRenderer hmcCosmetics() {
        return hmcCosmetics;
    }

    public CustomItemProvider customItems() {
        return itemsAdder;
    }

    public SelectionProvider selections() {
        return worldEdit;
    }

    /** Diagnostics: capability → active backend and status. */
    public Map<String, String> status() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("Ranks", ranks().pluginName() + " – " + ranks().status());
        status.put("TAB", tab.status() + (tab.available() ? ", " + tab.count() + " placeholders" : ""));
        status.put("ModelEngine", modelEngine.status());
        status.put("MythicMobs", mythicMobs.status());
        status.put("NPCs", npcs().pluginName() + " – " + npcs().status());
        status.put("HMCCosmetics", hmcCosmetics.status());
        status.put("ItemsAdder", itemsAdder.status() + (itemsAdder.available() ? (itemsAdder.ready() ? ", data loaded" : ", waiting for data") : ""));
        status.put("WorldEdit", worldEdit.status());
        return status;
    }

    private String summary() {
        StringBuilder sb = new StringBuilder();
        for (Integration integration : new Integration[]{luckPerms, tab, modelEngine, mythicMobs, citizens, hmcCosmetics, itemsAdder, worldEdit}) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(integration.pluginName()).append('=').append(integration.available() ? "on" : "off");
        }
        return sb.toString();
    }
}
