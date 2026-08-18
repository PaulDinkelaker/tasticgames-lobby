package de.tasticgames.lobby.npc;

import de.tasticgames.client.dto.network.ServerTypeResponse;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.gateway.GatewayService;
import de.tasticgames.lobby.integration.npc.NpcProvider;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.service.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Lobby service NPCs from config/npcs.yml: the season pass greeter, the game mode NPCs and the
 * games/events NPC. They are spawned through the existing NPC backend (Citizens when installed,
 * native entities otherwise) into an in-memory registry and are never persisted. Left and right
 * click trigger the same action, guarded by a short per-player cooldown. The pass NPC additionally
 * respects {@code npc} from config/pass.yml, the pass module's own switch.
 * <p>
 * Everything degrades gracefully: a world that is not loaded yet is retried on {@link WorldLoadEvent},
 * a backend that cannot spawn an NPC only produces a warning, and a transfer that the network refuses
 * ends in the same localized feedback the gateway dialog gives.
 */
public final class LobbyNpcService implements Service, Listener {

    /** Minimum time between two NPC interactions of the same player (left and right click share it). */
    private static final long CLICK_COOLDOWN_MILLIS = 750;

    private final LobbyConfigurationService configurationService;
    private final NpcProvider npcs;
    private final GatewayService gateway;
    private final LobbyMessages messages;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final MainThread mainThread;
    private final BooleanSupplier passNpcEnabled;
    private final Consumer<Player> passOpener;
    private final Consumer<Player> cookieOpener;
    private final Logger logger;
    private final AtomicReference<LobbyNpcConfiguration> configuration = new AtomicReference<>();
    private final Map<String, NpcProvider.NpcHandle> handles = new LinkedHashMap<>();
    private final Set<String> pendingWorlds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    /**
     * @param passNpcEnabled {@code pass.npc} from config/pass.yml – the pass module keeps its own switch
     *                       for the NPC that opens its dialog; {@code false} skips every {@code PASS} NPC
     */
    public LobbyNpcService(LobbyConfigurationService configurationService, NpcProvider npcs, GatewayService gateway, LobbyMessages messages,
                           LobbySounds sounds, LobbyTelemetryService telemetry, MainThread mainThread, BooleanSupplier passNpcEnabled,
                           Consumer<Player> passOpener, Consumer<Player> cookieOpener, Logger logger) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.npcs = Objects.requireNonNull(npcs);
        this.gateway = Objects.requireNonNull(gateway);
        this.messages = Objects.requireNonNull(messages);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.passNpcEnabled = Objects.requireNonNull(passNpcEnabled);
        this.passOpener = Objects.requireNonNull(passOpener);
        this.cookieOpener = Objects.requireNonNull(cookieOpener);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "lobby-npc-service";
    }

    @Override
    public void start() {
        configuration.set(load());
        npcs.onClick((npcId, player, left) -> {
            LobbyNpcConfiguration.Npc npc = configuration.get().npcs().get(npcId);
            if (npc != null) {
                interact(player, npc);
            }
        });
        respawn();
    }

    @Override
    public void stop() {
        for (NpcProvider.NpcHandle handle : handles.values()) {
            npcs.remove(handle);
        }
        handles.clear();
        pendingWorlds.clear();
        lastClick.clear();
    }

    /** Re-reads config/npcs.yml and re-creates every service NPC ({@code /tasticlobby reload}). */
    public void reload() {
        try {
            configurationService.reload();
            configuration.set(load());
            mainThread.run(this::respawn);
        } catch (Exception e) {
            logger.warning("Service NPC reload failed: " + LobbyThrowables.rootMessage(e));
        }
    }

    /** (Re)creates every configured NPC (worlds not loaded yet are retried on {@link WorldLoadEvent}). */
    public void respawn() {
        for (NpcProvider.NpcHandle handle : handles.values()) {
            npcs.remove(handle);
        }
        handles.clear();
        pendingWorlds.clear();
        LobbyNpcConfiguration config = configuration.get();
        if (!config.enabled()) {
            logger.info("Service NPCs are disabled in config/npcs.yml.");
            return;
        }
        int wanted = 0;
        for (LobbyNpcConfiguration.Npc npc : config.npcs().values()) {
            if (skipped(npc)) {
                continue;
            }
            wanted++;
            spawn(npc, config.lookClose());
        }
        logger.info("Service NPCs: " + handles.size() + "/" + wanted + " spawned via " + npcs.pluginName()
                + (pendingWorlds.isEmpty() ? "" : " (waiting for worlds " + pendingWorlds + ")"));
    }

    public int count() {
        return handles.size();
    }

    /** Diagnostics line next to the cookie NPC counts. */
    public String status() {
        LobbyNpcConfiguration config = configuration.get();
        if (config == null) {
            return "not started";
        }
        if (!config.enabled()) {
            return "disabled in config/npcs.yml";
        }
        long wanted = config.npcs().values().stream().filter(npc -> !skipped(npc)).count();
        return handles.size() + "/" + wanted + " via " + npcs.pluginName()
                + (pendingWorlds.isEmpty() ? "" : " (waiting for worlds " + pendingWorlds + ")");
    }

    private LobbyNpcConfiguration load() {
        return LobbyNpcConfiguration.load(configurationService.raw("npcs"), configurationService.configuration().world().name());
    }

    /** The pass module switches its own NPC off through {@code npc: false} in config/pass.yml. */
    private boolean skipped(LobbyNpcConfiguration.Npc npc) {
        return npc.action() == LobbyNpcAction.PASS && !passNpcEnabled.getAsBoolean();
    }

    private void spawn(LobbyNpcConfiguration.Npc npc, boolean lookClose) {
        World world = Bukkit.getWorld(npc.world());
        if (world == null) {
            pendingWorlds.add(npc.world());
            return;
        }
        Component name = messages.get(SupportedLanguage.ENGLISH, "lobby.npc." + npc.id() + ".name", Map.of());
        NpcProvider.NpcSpec spec = new NpcProvider.NpcSpec(npc.id(), name, npc.toLocation(world), EntityType.PLAYER, npc.skinName(),
                npc.skinValue(), npc.skinSignature(), "", OptionalInt.empty(), npc.mirrorSkin(), lookClose);
        npcs.spawn(spec).ifPresentOrElse(handle -> {
            handles.put(npc.id(), handle);
            decorate(npc, handle);
        }, () -> logger.warning("Service NPC '" + npc.id() + "' could not be spawned via " + npcs.pluginName() + "."));
    }

    /**
     * Floating label above the NPC: name, what it is for and how to use it. Citizens holograms are one text
     * for every viewer, so the lines are rendered in English like the nameplate they replace.
     */
    private void decorate(LobbyNpcConfiguration.Npc npc, NpcProvider.NpcHandle handle) {
        SupportedLanguage lang = SupportedLanguage.ENGLISH;
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get(lang, "lobby.npc." + npc.id() + ".name", Map.of()));
        Component tagline = messages.get(lang, "lobby.npc." + npc.id() + ".tagline", Map.of());
        if (!PlainTextComponentSerializer.plainText().serialize(tagline).isBlank()) {
            lines.add(tagline);
        }
        lines.add(messages.get(lang, "lobby.npc.hologram.hint", Map.of()));
        npcs.hologram(handle, lines);
        // the hologram carries the name now – a second floating name on top of it looks broken
        npcs.nameplate(handle, false);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (pendingWorlds.remove(event.getWorld().getName())) {
            LobbyNpcConfiguration config = configuration.get();
            for (LobbyNpcConfiguration.Npc npc : config.npcs().values()) {
                if (npc.world().equals(event.getWorld().getName()) && !handles.containsKey(npc.id()) && !skipped(npc)) {
                    spawn(npc, config.lookClose());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    private void interact(Player player, LobbyNpcConfiguration.Npc npc) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        telemetry.event("lobby.npc.interact", player.getUniqueId(), Map.of("npc", npc.id(), "action", npc.action()));
        switch (npc.action()) {
            case PASS -> open(player, passOpener);
            case COOKIE -> open(player, cookieOpener);
            case TRANSFER -> transfer(player, npc.target());
            case NONE -> {
                sounds.click(player);
                messages.send(player, "lobby.npc.none", Map.of("npc", messages.get(player, "lobby.npc." + npc.id() + ".name")));
            }
        }
    }

    private void open(Player player, Consumer<Player> opener) {
        sounds.click(player);
        opener.accept(player);
    }

    /**
     * Same contract as the gateway dialog: the network has to be reachable, out of maintenance and the
     * mode has to be online – otherwise the player gets the localized reason and no transfer is started.
     */
    private void transfer(Player player, ServerTypeResponse type) {
        if (!gateway.available()) {
            deny(player, "lobby.gateway.unavailable");
            return;
        }
        if (gateway.networkMaintenance()) {
            deny(player, "lobby.gateway.maintenance");
            return;
        }
        if (!gateway.status(type).available()) {
            deny(player, "lobby.gateway.mode_unavailable");
            return;
        }
        sounds.click(player);
        messages.send(player, "lobby.gateway.connecting", Map.of("mode", messages.get(messages.languageOf(player),
                "lobby.gateway.mode." + type.name().toLowerCase(Locale.ROOT), Map.of())));
        gateway.requestTransfer(player, type, false).whenComplete((response, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                logger.warning("Service NPC transfer request failed for " + player.getName() + ": " + LobbyThrowables.rootMessage(throwable));
                deny(player, "lobby.gateway.failed");
                return;
            }
            if (!response.accepted()) {
                deny(player, "COOLDOWN".equals(response.reason()) ? "lobby.gateway.cooldown" : "lobby.gateway.failed");
            }
        }));
    }

    private void deny(Player player, String key) {
        messages.send(player, key);
        sounds.error(player);
    }
}
