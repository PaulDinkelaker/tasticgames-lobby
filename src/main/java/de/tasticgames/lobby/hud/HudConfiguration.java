package de.tasticgames.lobby.hud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * hud.yml – native top-screen HUD (boss bars) with optional ItemsAdder icons and box glyphs.
 */
public record HudConfiguration(
        boolean enabled,
        int refreshTicks,
        boolean rowValues,
        boolean rowHint,
        boolean rowObjective,
        boolean rowStatus,
        boolean iaIcons,
        boolean iaBoxes,
        boolean iaExportContent,
        boolean iaAutoZip,
        BossBar.Color bossbarColor,
        String boxLeft,
        String boxMiddle,
        String boxRight,
        int boxPadding,
        int boxGap,
        int glyphSpacing,
        Map<String, String> iconMap,
        Map<String, String> unicodeIcons,
        TextColor titleColor,
        TextColor labelColor,
        List<TextColor> valueColors,
        TextColor hintColor,
        TextColor objectiveColor,
        TextColor statusColor
) {

    public HudConfiguration {
        refreshTicks = Math.max(2, refreshTicks);
        boxPadding = Math.max(0, boxPadding);
        boxGap = Math.max(0, boxGap);
        glyphSpacing = Math.max(0, Math.min(2, glyphSpacing));
        iconMap = Map.copyOf(iconMap);
        unicodeIcons = Map.copyOf(unicodeIcons);
        valueColors = valueColors == null || valueColors.isEmpty() ? List.of(TextColor.color(0x24ff2b)) : List.copyOf(valueColors);
        Objects.requireNonNull(bossbarColor);
    }

    public String iconId(String key) {
        return iconMap.getOrDefault(key, "");
    }

    public String unicodeIcon(String key) {
        return unicodeIcons.getOrDefault(key, "");
    }

    public TextColor valueColor(int index) {
        return valueColors.get(Math.floorMod(index - 1, valueColors.size()));
    }

    public static HudConfiguration load(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml);
        Map<String, String> icons = new LinkedHashMap<>();
        ConfigurationSection iconSection = yaml.getConfigurationSection("itemsadder.icons-map");
        if (iconSection != null) {
            for (String key : iconSection.getKeys(false)) {
                icons.put(key.toLowerCase(Locale.ROOT), iconSection.getString(key, ""));
            }
        }
        Map<String, String> unicode = new LinkedHashMap<>();
        ConfigurationSection unicodeSection = yaml.getConfigurationSection("unicode-icons");
        if (unicodeSection != null) {
            for (String key : unicodeSection.getKeys(false)) {
                unicode.put(key.toLowerCase(Locale.ROOT), unicodeSection.getString(key, ""));
            }
        }
        List<TextColor> values = new ArrayList<>();
        for (String hex : yaml.getStringList("colors.values")) {
            values.add(color(hex, 0x24ff2b));
        }
        return new HudConfiguration(
                yaml.getBoolean("enabled", true),
                yaml.getInt("refresh-ticks", 10),
                yaml.getBoolean("rows.values", true),
                yaml.getBoolean("rows.hint", true),
                yaml.getBoolean("rows.objective", true),
                yaml.getBoolean("rows.status", true),
                yaml.getBoolean("itemsadder.icons", true),
                yaml.getBoolean("itemsadder.boxes", true),
                yaml.getBoolean("itemsadder.export-content", true),
                yaml.getBoolean("itemsadder.auto-zip", true),
                bossbarColor(yaml.getString("itemsadder.bossbar-color", "PINK")),
                yaml.getString("itemsadder.box.left", "tasticgames:hud_box_left"),
                yaml.getString("itemsadder.box.middle", "tasticgames:hud_box_mid"),
                yaml.getString("itemsadder.box.right", "tasticgames:hud_box_right"),
                yaml.getInt("itemsadder.box.padding", 6),
                yaml.getInt("itemsadder.box.gap", 6),
                yaml.getInt("itemsadder.glyph-spacing", 0),
                icons, unicode,
                color(yaml.getString("colors.title", "#ffd82b"), 0xffd82b),
                color(yaml.getString("colors.label", "#aaaaaa"), 0xaaaaaa),
                values,
                color(yaml.getString("colors.hint", "#bbbbbb"), 0xbbbbbb),
                color(yaml.getString("colors.objective", "#dbb039"), 0xdbb039),
                color(yaml.getString("colors.status", "#ffffff"), 0xffffff));
    }

    private static BossBar.Color bossbarColor(String name) {
        try {
            return BossBar.Color.valueOf(name == null ? "PINK" : name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("hud.yml: unknown itemsadder.bossbar-color '" + name + "' (PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE)");
        }
    }

    private static TextColor color(String hex, int fallback) {
        if (hex == null || hex.isBlank()) return TextColor.color(fallback);
        TextColor parsed = TextColor.fromHexString(hex.trim().startsWith("#") ? hex.trim() : "#" + hex.trim());
        return parsed == null ? TextColor.color(fallback) : parsed;
    }
}
