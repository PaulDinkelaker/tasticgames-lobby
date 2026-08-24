package de.tasticgames.lobby.hud.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein UI-Icon-Pack, wie ItemsAdder es am Ende sehen soll: ein Namensraum und die Font-Images darin.
 *
 * <p>Die Hersteller liefern zwei Formate. Entweder eine echte ItemsAdder-Konfiguration mit
 * {@code font_images:} und Pfaden unterhalb von {@code textures/}, oder eine vereinfachte eigene
 * Fassung mit {@code textures:} und Pfaden auf lose PNG-Dateien. Beide werden hier auf dieselbe
 * Form gebracht, damit der Rest des Plugins nur noch einen Fall kennt.</p>
 *
 * @param namespace ItemsAdder-Namensraum, z. B. {@code minimalui}
 * @param entries   Icon-ID -> Eintrag
 */
public record IconPackDefinition(String namespace, Map<String, Entry> entries) {

    /** Voreinstellungen, die zu 16x16-Icons in einer Menüzeile passen. */
    public static final int DEFAULT_SCALE_RATIO = 9;
    public static final int DEFAULT_Y_POSITION = 8;

    /**
     * @param id          Name des Font-Images ({@code minimalui:search})
     * @param texture     Pfad unterhalb von {@code textures/}, immer {@code font/<id>.png}
     * @param sourceName  Dateiname, unter dem das PNG im Pack liegt – für die Zuordnung
     * @param scaleRatio  ItemsAdder {@code scale_ratio}
     * @param yPosition   ItemsAdder {@code y_position}
     */
    public record Entry(String id, String texture, String sourceName, int scaleRatio, int yPosition) {
    }

    public IconPackDefinition {
        Objects.requireNonNull(namespace, "namespace");
        entries = Map.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Liest eine Hersteller- oder ItemsAdder-Konfiguration; leer, wenn kein Namensraum darin steht. */
    public static Optional<IconPackDefinition> parse(YamlConfiguration yaml) {
        String namespace = yaml.getString("info.namespace", "");
        if (namespace == null || namespace.isBlank()) {
            return Optional.empty();
        }
        String normalized = namespace.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");

        ConfigurationSection fontImages = yaml.getConfigurationSection("font_images");
        ConfigurationSection textures = yaml.getConfigurationSection("textures");
        ConfigurationSection source = fontImages != null ? fontImages : textures;
        if (source == null) {
            return Optional.empty();
        }

        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String key : source.getKeys(false)) {
            ConfigurationSection entry = source.getConfigurationSection(key);
            String id = key.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            String configured = entry == null
                    ? source.getString(key, "")
                    : firstNonBlank(entry.getString("path"), entry.getString("file"), entry.getString("texture"));
            String fileName = fileName(configured, id);
            int scale = entry == null ? DEFAULT_SCALE_RATIO : entry.getInt("scale_ratio", DEFAULT_SCALE_RATIO);
            int yPosition = entry == null ? DEFAULT_Y_POSITION : entry.getInt("y_position", DEFAULT_Y_POSITION);
            entries.put(id, new Entry(id, "font/" + id + ".png", fileName, scale, yPosition));
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of(new IconPackDefinition(normalized, entries));
    }

    /** Die ItemsAdder-Konfiguration, die TasticLobby schreibt – ein Format für beide Herstellerfassungen. */
    public String toItemsAdderConfig() {
        StringBuilder yaml = new StringBuilder(256);
        yaml.append("# Installed by TasticLobby from an operator supplied UI icon pack.\n")
                .append("# Do not edit by hand: the file is rewritten whenever the source pack changes.\n")
                .append("info:\n  namespace: ").append(namespace).append('\n')
                .append("font_images:\n");
        for (Entry entry : entries.values()) {
            yaml.append("  ").append(entry.id()).append(":\n")
                    .append("    path: ").append(entry.texture()).append('\n')
                    .append("    scale_ratio: ").append(entry.scaleRatio()).append('\n')
                    .append("    y_position: ").append(entry.yPosition()).append('\n');
        }
        return yaml.toString();
    }

    /** Der Dateiname, unter dem das PNG im Pack gesucht wird. */
    private static String fileName(String configured, String fallbackId) {
        if (configured == null || configured.isBlank()) {
            return fallbackId + ".png";
        }
        String path = configured.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? fallbackId + ".png" : name;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
