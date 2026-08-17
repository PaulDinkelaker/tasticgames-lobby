package de.tasticgames.lobby.hud;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.integration.item.CustomItemProvider;
import de.tasticgames.lobby.placeholder.HudContextProvider;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.player.LobbyPlayerService;
import de.tasticgames.lobby.settings.LobbySettings;
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
    private final Logger logger;
    private final Map<UUID, List<BossBar>> bars = new ConcurrentHashMap<>();
    private volatile HudConfiguration configuration;
    private BukkitTask task;
    private volatile boolean boxesAvailable;
    private volatile boolean boxesPendingPack;
    private volatile boolean zipRequested;
    private volatile long lastBoxNotice;

    public HudService(Plugin plugin, TasticCoreApi coreApi, LobbyConfigurationService configurationService, HudContextProvider context,
                      CustomItemProvider customItems, LobbyPlayerService players, Function<Player, String> rankDisplay,
                      Function<Player, String> playtime, Supplier<Integer> online, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.configurationService = Objects.requireNonNull(configurationService);
        this.context = Objects.requireNonNull(context);
        this.customItems = Objects.requireNonNull(customItems);
        this.players = Objects.requireNonNull(players);
        this.rankDisplay = Objects.requireNonNull(rankDisplay);
        this.playtime = Objects.requireNonNull(playtime);
        this.online = Objects.requireNonNull(online);
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
            if (boxesPendingPack && configuration.iaAutoZip() && !zipRequested) {
                // ItemsAdder finished loading its content – regenerate the pack once so the exported files ship
                zipRequested = true;
                logger.info("Regenerating the ItemsAdder pack (/iazip) for the exported TasticLobby content...");
                Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iazip"), 40L);
                return;
            }
            if (boxesPendingPack) {
                boxesPendingPack = false;
                logger.info("ItemsAdder pack regenerated – HUD boxes enabled.");
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
        boxesPendingPack = false;
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
            List<File> created = new HudAssetExporter(itemsAdder, logger).export(configuration.bossbarColor().name());
            if (!created.isEmpty()) {
                boxesPendingPack = true;
                zipRequested = false;
                logger.warning("HUD background boxes stay disabled until the resource pack ships the new files"
                        + (configuration.iaAutoZip() ? " – /iazip runs automatically once ItemsAdder is loaded." : " – run /iazip once."));
            }
        } catch (Exception e) {
            logger.warning("HUD asset export failed: " + e.getMessage());
        }
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
        int rows = rowCount();
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
            if (!bars.containsKey(player.getUniqueId())) {
                show(player);
            } else {
                refresh(player);
            }
        }
    }

    private boolean enabledFor(Player player) {
        return coreApi.playerManager().find(player.getUniqueId()).map(p -> p.settings().get(LobbySettings.HUD_ENABLED)).orElse(true);
    }

    private int rowCount() {
        int rows = 0;
        if (configuration.rowValues()) rows++;
        if (configuration.rowHint()) rows++;
        if (configuration.rowObjective()) rows++;
        if (configuration.rowStatus()) rows++;
        return rows;
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
        if (configuration.rowHint()) {
            String hint = context.hint(player);
            rows.add(hint.isEmpty() ? Component.empty() : row(List.of(cell(null, hint, null, configuration.hintColor(), icons)), boxes));
        }
        if (configuration.rowObjective()) {
            String objective = context.objective(player);
            rows.add(objective.isEmpty() ? Component.empty() : row(List.of(cell(null, objective, null, configuration.objectiveColor(), icons)), boxes));
        }
        if (configuration.rowStatus()) {
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
                // bitmap glyphs advance width + 1 px
                measured = measured.append(Component.text(glyph.get().text(), NamedTextColor.WHITE), glyph.get().width() + 1);
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

    /**
     * Draws left cap + middle tiles + right cap, then jumps back and renders the content centered on
     * top of the box. Bitmap glyphs advance one pixel more than they are wide, so every glyph is
     * followed by a -1 px offset – the box is then exactly {@code boxWidth} pixels wide.
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
        Component back = PixelOffsets.of(-1);
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
