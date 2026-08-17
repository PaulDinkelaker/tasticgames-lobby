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
 * icons and dark rounded box glyphs behind them (offset-composed); otherwise unicode icons and
 * plain text. Per-player toggle: {@link LobbySettings#HUD_ENABLED}.
 */
public final class HudService implements Service {

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
    private volatile long lastBoxWarning;

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

    /** Re-reads hud.yml and re-exports assets (used by /tasticlobby reload). */
    public void reload() {
        configuration = HudConfiguration.load(configurationService.raw("hud"));
        exportAssets();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
    }

    private void exportAssets() {
        if (!configuration.iaExportContent() || !customItems.available()) {
            return;
        }
        File itemsAdder = new File(plugin.getDataFolder().getParentFile(), "ItemsAdder");
        if (!itemsAdder.isDirectory()) {
            return;
        }
        try {
            new HudAssetExporter(itemsAdder, logger).export(configuration.bossbarColor().name());
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
            BossBar bar = BossBar.bossBar(Component.empty(), 0f, configuration.bossbarColor(), BossBar.Overlay.PROGRESS);
            list.add(bar);
        }
        while (list.size() > rows) {
            BossBar removed = list.remove(list.size() - 1);
            player.hideBossBar(removed);
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
        if (!configuration.iaBoxes() || !customItems.fontImagesSupported()) {
            return false;
        }
        boolean ready = customItems.fontImage(configuration.boxLeft()).isPresent()
                && customItems.fontImage(configuration.boxMiddle()).isPresent()
                && customItems.fontImage(configuration.boxRight()).isPresent();
        if (!ready) {
            long now = System.currentTimeMillis();
            if (now - lastBoxWarning > 300_000) {
                lastBoxWarning = now;
                logger.info("HUD box glyphs (" + configuration.boxMiddle() + ") are not in the ItemsAdder pack yet – run /iazip after the export; rendering without boxes.");
            }
        }
        return ready;
    }

    // ------------------------------------------------------------------ rendering

    private List<Component> render(Player player) {
        List<Component> rows = new ArrayList<>();
        HudContextProvider.Context ctx = context.contextOf(player);
        boolean icons = configuration.iaIcons() && customItems.fontImagesSupported();
        boolean boxes = boxesAvailable;
        if (configuration.rowValues()) {
            List<Component> cells = new ArrayList<>();
            cells.add(cell(context.contextIcon(ctx), context.title(player), configuration.titleColor(), icons, boxes, false));
            for (int i = 1; i <= 4; i++) {
                String label = context.label(player, i);
                String value = context.value(player, i);
                if (label.isEmpty()) continue;
                cells.add(cell(context.valueIcon(ctx, i), label + ": " + value, configuration.valueColor(i), icons, boxes, true));
            }
            rows.add(join(cells, boxes));
        }
        if (configuration.rowHint()) {
            String hint = context.hint(player);
            rows.add(hint.isEmpty() ? Component.empty() : join(List.of(cell(null, hint, configuration.hintColor(), icons, boxes, false)), boxes));
        }
        if (configuration.rowObjective()) {
            String objective = context.objective(player);
            rows.add(objective.isEmpty() ? Component.empty() : join(List.of(cell(null, objective, configuration.objectiveColor(), icons, boxes, false)), boxes));
        }
        if (configuration.rowStatus()) {
            List<Component> cells = new ArrayList<>();
            cells.add(cell("rank", rankDisplay.apply(player), configuration.statusColor(), icons, boxes, false));
            cells.add(cell("playtime", playtime.apply(player), configuration.statusColor(), icons, boxes, false));
            cells.add(cell("online", String.valueOf(online.get()), configuration.statusColor(), icons, boxes, false));
            rows.add(join(cells, boxes));
        }
        return rows;
    }

    private Component join(List<Component> cells, boolean boxes) {
        TextComponent.Builder row = Component.text();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                row.append(boxes ? Component.text(customItems.pixelOffset(configuration.boxGap())) : Component.text("  ·  ", NamedTextColor.DARK_GRAY));
            }
            row.append(cells.get(i));
        }
        return row.build();
    }

    /**
     * One HUD cell: [box glyphs][back offset][icon] text[end offset]. Label/value cells colour the
     * label grey and the value in the slot colour.
     */
    private Component cell(String iconKey, String text, TextColor color, boolean icons, boolean boxes, boolean labelValue) {
        TextComponent.Builder content = Component.text();
        int contentWidth = 0;
        if (iconKey != null && !iconKey.isEmpty()) {
            Optional<CustomItemProvider.FontGlyph> glyph = icons ? customItems.fontImage(configuration.iconId(iconKey)) : Optional.empty();
            if (glyph.isPresent()) {
                content.append(Component.text(glyph.get().text(), NamedTextColor.WHITE)).append(Component.text(" "));
                contentWidth += glyph.get().width() + 1 + FontWidths.width(" ");
            } else {
                String unicode = configuration.unicodeIcon(iconKey);
                if (!unicode.isEmpty()) {
                    content.append(Component.text(unicode + " ", color));
                    contentWidth += FontWidths.width(unicode + " ");
                }
            }
        }
        if (labelValue) {
            int colon = text.indexOf(": ");
            if (colon > 0) {
                String label = text.substring(0, colon + 2);
                String value = text.substring(colon + 2);
                content.append(Component.text(label, configuration.labelColor())).append(Component.text(value, color));
            } else {
                content.append(Component.text(text, color));
            }
        } else {
            content.append(Component.text(text, color));
        }
        contentWidth += FontWidths.width(text);
        if (!boxes) {
            return content.build();
        }
        return boxed(content.build(), contentWidth);
    }

    /** Draws left cap + n middle tiles + right cap, moves back and draws the content on top. */
    private Component boxed(Component content, int contentWidth) {
        CustomItemProvider.FontGlyph left = customItems.fontImage(configuration.boxLeft()).orElse(null);
        CustomItemProvider.FontGlyph mid = customItems.fontImage(configuration.boxMiddle()).orElse(null);
        CustomItemProvider.FontGlyph right = customItems.fontImage(configuration.boxRight()).orElse(null);
        if (left == null || mid == null || right == null || mid.width() <= 0) {
            return content;
        }
        int padding = configuration.boxPadding();
        int inner = Math.max(mid.width(), contentWidth + 2 * padding - left.width() - right.width());
        int tiles = (int) Math.ceil(inner / (double) mid.width());
        int boxWidth = left.width() + tiles * mid.width() + right.width();
        TextComponent.Builder box = Component.text();
        // bitmap glyphs advance width+1: pull back one pixel after every glyph so the tiles touch
        box.append(Component.text(left.text(), NamedTextColor.WHITE)).append(Component.text(customItems.pixelOffset(-1)));
        StringBuilder mids = new StringBuilder();
        String midShift = customItems.pixelOffset(-1);
        for (int i = 0; i < tiles; i++) {
            mids.append(mid.text()).append(midShift);
        }
        box.append(Component.text(mids.toString(), NamedTextColor.WHITE));
        box.append(Component.text(right.text(), NamedTextColor.WHITE)).append(Component.text(customItems.pixelOffset(-1)));
        // cursor now sits at the right edge of the box: go back to the left padding, draw the content, jump to the end
        int leftOffset = -(boxWidth) + (boxWidth - contentWidth) / 2;
        box.append(Component.text(customItems.pixelOffset(leftOffset)));
        box.append(content);
        int rest = boxWidth - ((boxWidth - contentWidth) / 2) - contentWidth;
        if (rest != 0) {
            box.append(Component.text(customItems.pixelOffset(rest)));
        }
        return box.build();
    }
}
