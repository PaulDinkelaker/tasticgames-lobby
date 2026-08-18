package de.tasticgames.lobby.hud;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.integration.item.CustomItemProvider;
import de.tasticgames.lobby.placeholder.HudContextProvider;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.player.TasticPlayer;
import de.tasticgames.service.Service;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Native top-screen HUD: up to four boss bars (always drawn above the held item), each showing one
 * row of the context HUD ({@link HudContextProvider}). With ItemsAdder the cells get font-image
 * icons and dark rounded box glyphs behind them – the boxes are drawn first, then the cursor is
 * moved back with {@link PixelOffsets} so the text sits centered inside the box. Without
 * ItemsAdder the HUD falls back to unicode icons and plain text. Per-player toggle:
 * {@link LobbySettings#HUD_ENABLED}.
 * <p>
 * Geometry: a font image rendered via ItemsAdder advances the cursor by exactly the width ItemsAdder
 * reports ({@code FontImageWrapper#getWidth}) plus {@code itemsadder.glyph-spacing} pixels (0 by
 * default – measured against the exported box glyphs whose pixel widths are known). Text widths come
 * from {@link FontWidths}.
 */
public final class HudService implements Service {

    /** Text plus its exact pixel width (needed to centre it inside a box). */
    private record Measured(Component component, int width) {
        Measured append(Component other, int otherWidth) {
            return new Measured(component.append(other), width + otherWidth);
        }
    }

    private final Plugin plugin;
    private final TasticCoreApi coreApi;
    private final LobbyConfigurationService configurationService;
    private final HudContextProvider context;
    private final CustomItemProvider customItems;
    private final LobbyPlayerService players;
    private final Function<Player, String> rankDisplay;
    private final Function<Player, String> playtime;
    private final Supplier<Integer> online;
    private final java.util.function.BooleanSupplier externalContentPending;
    private final Logger logger;
    private final Map<UUID, List<BossBar>> bars = new ConcurrentHashMap<>();
    private volatile HudConfiguration configuration;
    private BukkitTask task;
    private volatile boolean boxesAvailable;
    private volatile boolean boxesPendingPack;
    private volatile boolean zipRequested;
    private volatile boolean zipDispatched;
    private volatile boolean assetsExported;
    private volatile HudAssetExporter exporter;
    private volatile long lastBoxNotice;

    public HudService(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, HudContextProvider context,
                      CustomItemProvider customItems, LobbyPlayerService players, Function<Player, String> rankDisplay,
                      Function<Player, String> playtime, Supplier<Integer> online,
                      java.util.function.BooleanSupplier externalContentPending, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.context = Objects.requireNonNull(context);
        this.customItems = Objects.requireNonNull(customItems);
        this.players = Objects.requireNonNull(players);
        this.rankDisplay = Objects.requireNonNull(rankDisplay);
        this.playtime = Objects.requireNonNull(playtime);
        this.online = Objects.requireNonNull(online);
        this.externalContentPending = Objects.requireNonNull(externalContentPending);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override
    public String id() {
        return "hud-service";
    }

    @Override
    public void start() {
        configuration = HudConfiguration.load(configurationService.raw("hud"));
        if (!configuration.enabled()) {
            logger.info("Top-screen HUD disabled by configuration.");
            return;
        }
        exportAssets();
        customItems.onReady(() -> {
            if (boxesPendingPack) {
                if (configuration.iaAutoZip()) {
                    if (!zipRequested) {
                        // ItemsAdder finished loading its content – regenerate the pack once so the exported files ship
                        scheduleZip();
                        return;
                    }
                    if (!zipDispatched) {
                        return; // this load event predates our /iazip (e.g. FIRST_LOAD) – the regeneration follows
                    }
                }
                // pack regenerated (our /iazip or a manual one): the exported files are shipped now
                boxesPendingPack = false;
                if (exporter != null) {
                    exporter.markPackCurrent();
                }
                logger.info("ItemsAdder pack regenerated – HUD boxes and the player-head profile item are enabled.");
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                hide(player);
            }
        });
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, configuration.refreshTicks());
        logger.info("Top-screen HUD started (boss bars, refresh every " + configuration.refreshTicks() + " ticks, ItemsAdder icons "
                + (configuration.iaIcons() && customItems.available() ? "on" : "off") + ").");
    }

    @Override
    public void stop() {
        if (task != null) task.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
        bars.clear();
    }

    /** Re-reads hud.yml and re-exports missing assets (used by /tasticlobby reload). */
    public void reload() {
        configuration = HudConfiguration.load(configurationService.raw("hud"));
        exportAssets();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
    }

    /**
     * Writes the ItemsAdder content (box glyphs, offset font, transparent boss bar). When files had
     * to be created the pack does not contain them yet, so the boxes stay off until the operator ran
     * {@code /iazip} and {@code /tasticlobby reload} – otherwise every box would render misaligned.
     */
    private void exportAssets() {
        if (!configuration.iaExportContent() || !customItems.available()) {
            return;
        }
        File itemsAdder = new File(plugin.getDataFolder().getParentFile(), "ItemsAdder");
        if (!itemsAdder.isDirectory()) {
            return;
        }
        try {
            HudAssetExporter current = new HudAssetExporter(itemsAdder, logger);
            HudAssetExporter.Result result = current.export(configuration.bossbarColor().name());
            exporter = current;
            assetsExported = true;
            // cosmetic packs installed by the CosmeticPackInstaller need the same /iazip rebuild
            if (result.packPending() || externalContentPending.getAsBoolean()) {
                boxesPendingPack = true;
                zipRequested = false;
                zipDispatched = false;
                logger.warning("HUD background boxes and the player-head profile item stay disabled until the resource pack ships the exported files"
                        + (configuration.iaAutoZip() ? " – /iazip runs automatically" + (customItems.ready() ? " now." : " once ItemsAdder is loaded.")
                        : " – run /iazip once."));
                if (configuration.iaAutoZip() && customItems.ready()) {
                    scheduleZip();
                }
            } else {
                boxesPendingPack = false;
            }
        } catch (Exception e) {
            logger.warning("HUD asset export failed: " + e.getMessage());
        }
    }

    private void scheduleZip() {
        if (zipRequested) {
            return;
        }
        zipRequested = true;
        zipDispatched = false;
        logger.info("Regenerating the ItemsAdder pack (/iazip) for the exported TasticLobby content...");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            zipDispatched = true;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iazip");
        }, 40L);
    }

    /**
     * Item model of the front-facing player head for the profile item – present once the exported
     * content is part of the resource pack (otherwise the client would show a missing model).
     */
    public Optional<String> profileHeadModel() {
        return assetsExported && !boxesPendingPack ? Optional.of(HudAssetExporter.PROFILE_HEAD_MODEL) : Optional.empty();
    }

    public HudConfiguration configuration() {
        return configuration;
    }

    public boolean boxesAvailable() {
        return boxesAvailable;
    }

    public int activeHuds() {
        return bars.size();
    }

    /** Shows the HUD (idempotent) – called after the lobby initialization. */
    public void show(Player player) {
        if (configuration == null || !configuration.enabled() || !enabledFor(player)) {
            hide(player);
            return;
        }
        List<BossBar> list = bars.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
        int rows = rowCount(player);
        while (list.size() < rows) {
            list.add(BossBar.bossBar(Component.empty(), 0f, configuration.bossbarColor(), BossBar.Overlay.PROGRESS));
        }
        while (list.size() > rows) {
            player.hideBossBar(list.remove(list.size() - 1));
        }
        for (BossBar bar : list) {
            player.showBossBar(bar);
        }
        refresh(player);
    }

    public void hide(Player player) {
        List<BossBar> list = bars.remove(player.getUniqueId());
        if (list != null) {
            for (BossBar bar : list) {
                player.hideBossBar(bar);
            }
        }
    }

    public void refresh(Player player) {
        List<BossBar> list = bars.get(player.getUniqueId());
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Component> rows = render(player);
        for (int i = 0; i < list.size() && i < rows.size(); i++) {
            BossBar bar = list.get(i);
            Component name = rows.get(i);
            if (!name.equals(bar.name())) {
                bar.name(name);
            }
        }
    }

    private void refreshAll() {
        boxesAvailable = boxesReady();
        for (Player player : Bukkit.getOnlinePlayers()) {
            LobbyPlayer lobbyPlayer = players.find(player.getUniqueId()).orElse(null);
            if (lobbyPlayer == null || !lobbyPlayer.initialized()) {
                continue;
            }
            if (!enabledFor(player)) {
                hide(player);
                continue;
            }
            List<BossBar> current = bars.get(player.getUniqueId());
            if (current == null || current.size() != rowCount(player)) {
                show(player); // first HUD or the player changed the HUD mode – rebuild the bars
            } else {
                refresh(player);
            }
        }
    }

    private boolean enabledFor(Player player) {
        return coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(LobbySettings.HUD_ENABLED)).orElse(true);
    }

    /** How many bars this player gets: the configured rows minus the ones their settings switch off. */
    private int rowCount(Player player) {
        Layout layout = layout(player);
        int rows = 0;
        if (configuration.rowValues()) rows++;
        if (configuration.rowHint() && layout.hints()) rows++;
        if (configuration.rowObjective() && !layout.minimal()) rows++;
        if (configuration.rowStatus() && layout.status()) rows++;
        return rows;
    }

    /** What a single player wants to see: HUD mode plus the two row toggles. */
    private record Layout(boolean minimal, boolean compact, boolean hints, boolean status) {
    }

    private Layout layout(Player player) {
        var settings = coreApi.playerManager().find(player.getUniqueId()).map(TasticPlayer::settings).orElse(null);
        String mode = settings == null ? "FULL" : settings.get(LobbySettings.HUD_MODE);
        boolean minimal = "MINIMAL".equals(mode);
        boolean compact = minimal || "COMPACT".equals(mode);
        boolean hints = !compact && (settings == null || settings.get(LobbySettings.HUD_HINTS));
        boolean status = !minimal && (settings == null || settings.get(LobbySettings.HUD_STATUS_ROW));
        return new Layout(minimal, compact, hints, status);
    }

    private boolean boxesReady() {
        if (!configuration.iaBoxes() || boxesPendingPack || !customItems.fontImagesSupported()) {
            return false;
        }
        boolean ready = width(configuration.boxLeft()) > 0 && width(configuration.boxMiddle()) > 0 && width(configuration.boxRight()) > 0;
        if (!ready) {
            long now = System.currentTimeMillis();
            if (now - lastBoxNotice > 300_000) {
                lastBoxNotice = now;
                logger.info("HUD box glyphs (" + configuration.boxMiddle() + ") are not in the ItemsAdder pack – run /iazip; rendering without boxes.");
            }
        }
        return ready;
    }

    private int width(String fontImageId) {
        return customItems.fontImage(fontImageId).map(CustomItemProvider.FontGlyph::width).orElse(0);
    }

    // ------------------------------------------------------------------ rendering

    private List<Component> render(Player player) {
        List<Component> rows = new ArrayList<>();
        HudContextProvider.Context ctx = context.contextOf(player);
        boolean icons = configuration.iaIcons() && customItems.fontImagesSupported();
        boolean boxes = boxesAvailable;
        Layout layout = layout(player);
        if (configuration.rowValues()) {
            List<Measured> cells = new ArrayList<>();
            cells.add(cell(context.contextIcon(ctx), context.title(player), null, configuration.titleColor(), icons));
            for (int i = 1; i <= 4; i++) {
                String label = context.label(player, i);
                if (label.isEmpty()) continue;
                cells.add(cell(context.valueIcon(ctx, i), label + ":", context.value(player, i), configuration.valueColor(i), icons));
            }
            rows.add(row(cells, boxes));
        }
        if (configuration.rowHint() && layout.hints()) {
            String hint = context.hint(player);
            rows.add(hint.isEmpty() ? Component.empty() : row(List.of(cell(null, hint, null, configuration.hintColor(), icons)), boxes));
        }
        if (configuration.rowObjective() && !layout.minimal()) {
            String objective = context.objective(player);
            // with the hint row switched off the objective row carries the hint whenever there is no objective,
            // so the HUD keeps its controls without spending a fourth line on them
            boolean fallback = objective.isEmpty() && !(configuration.rowHint() && layout.hints()) && layout.hints();
            String text = fallback ? context.hint(player) : objective;
            TextColor color = fallback ? configuration.hintColor() : configuration.objectiveColor();
            rows.add(text.isEmpty() ? Component.empty() : row(List.of(cell(null, text, null, color, icons)), boxes));
        }
        if (configuration.rowStatus() && layout.status()) {
            List<Measured> cells = List.of(
                    cell("rank", null, rankDisplay.apply(player), configuration.statusColor(), icons),
                    cell("playtime", null, playtime.apply(player), configuration.statusColor(), icons),
                    cell("online", null, String.valueOf(online.get()), configuration.statusColor(), icons));
            rows.add(row(cells, boxes));
        }
        return rows;
    }

    /** One row: every cell optionally wrapped in a box, separated by a pixel gap (or a text divider). */
    private Component row(List<Measured> cells, boolean boxes) {
        TextComponent.Builder row = Component.text();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                row.append(boxes ? PixelOffsets.of(configuration.boxGap()) : Component.text("  ·  ", NamedTextColor.DARK_GRAY));
            }
            Measured cell = cells.get(i);
            row.append(boxes ? boxed(cell) : cell.component());
        }
        return row.build();
    }

    /**
     * Builds a measured cell: {@code [icon] label value}. {@code label} is drawn in the label colour,
     * {@code value} in the cell colour; both are optional.
     */
    private Measured cell(String iconKey, String label, String value, TextColor color, boolean icons) {
        Measured measured = new Measured(Component.empty(), 0);
        if (iconKey != null && !iconKey.isEmpty()) {
            Optional<CustomItemProvider.FontGlyph> glyph = icons ? customItems.fontImage(configuration.iconId(iconKey)) : Optional.empty();
            if (glyph.isPresent() && glyph.get().width() > 0) {
                measured = measured.append(Component.text(glyph.get().text(), NamedTextColor.WHITE), advance(glyph.get()));
                measured = measured.append(Component.text(" "), FontWidths.width(" "));
            } else {
                String unicode = configuration.unicodeIcon(iconKey);
                if (!unicode.isEmpty()) {
                    measured = measured.append(Component.text(unicode + " ", color), FontWidths.width(unicode + " "));
                }
            }
        }
        if (label != null && !label.isEmpty()) {
            measured = measured.append(Component.text(label, configuration.labelColor()), FontWidths.width(label));
            if (value != null && !value.isEmpty()) {
                measured = measured.append(Component.text(" "), FontWidths.width(" "));
            }
        }
        if (value != null && !value.isEmpty()) {
            measured = measured.append(Component.text(value, color), FontWidths.width(value));
        }
        return measured;
    }

    /** Cursor advance of an ItemsAdder font image: the reported width plus the configured glyph spacing. */
    private int advance(CustomItemProvider.FontGlyph glyph) {
        return glyph.width() + configuration.glyphSpacing();
    }

    /**
     * Draws left cap + middle tiles + right cap, then jumps back and renders the content centered on
     * top of the box. Each glyph advances by the width ItemsAdder reports; a configured extra glyph
     * spacing is compensated with a negative offset after every glyph, so the box is exactly
     * {@code boxWidth} pixels wide and the text sits {@code padding} px from its left edge.
     */
    private Component boxed(Measured content) {
        CustomItemProvider.FontGlyph left = customItems.fontImage(configuration.boxLeft()).orElse(null);
        CustomItemProvider.FontGlyph mid = customItems.fontImage(configuration.boxMiddle()).orElse(null);
        CustomItemProvider.FontGlyph right = customItems.fontImage(configuration.boxRight()).orElse(null);
        if (left == null || mid == null || right == null || mid.width() <= 0) {
            return content.component();
        }
        int padding = configuration.boxPadding();
        int inner = Math.max(mid.width(), content.width() + 2 * padding - left.width() - right.width());
        int tiles = (int) Math.ceil(inner / (double) mid.width());
        int boxWidth = left.width() + tiles * mid.width() + right.width();

        TextComponent.Builder box = Component.text();
        Component back = PixelOffsets.of(-configuration.glyphSpacing());
        box.append(Component.text(left.text(), NamedTextColor.WHITE)).append(back);
        for (int i = 0; i < tiles; i++) {
            box.append(Component.text(mid.text(), NamedTextColor.WHITE)).append(back);
        }
        box.append(Component.text(right.text(), NamedTextColor.WHITE)).append(back);

        int leadIn = (boxWidth - content.width()) / 2;
        box.append(PixelOffsets.of(leadIn - boxWidth));
        box.append(content.component());
        box.append(PixelOffsets.of(boxWidth - leadIn - content.width()));
        return box.build();
    }
}
