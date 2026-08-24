package de.tasticgames.lobby.hud.pack;

import de.tasticgames.service.Service;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installiert UI-Icon-Packs, die der Betreiber in {@code plugins/TasticLobby/icon-packs/} ablegt –
 * als ZIP oder als entpackter Ordner – nach {@code plugins/ItemsAdder/contents/<namensraum>/}.
 *
 * <p>Warum eigener Code statt „Dateien einfach kopieren": die Packs kommen in zwei Formaten (fertige
 * ItemsAdder-Konfiguration oder lose PNGs mit Hersteller-YAML), sie legen Texturen nur für eine der
 * beiden ItemsAdder-Generationen ab, und sie enthalten Beiwerk (Vorschauen, 312-Pixel-Exporte,
 * Aseprite-Quellen), das nichts auf dem Server zu suchen hat. Hier wird beides auf die Form gebracht,
 * die {@code HudAssetExporter} für die eigenen Grafiken benutzt: Konfiguration unter
 * {@code configs/}, Texturen sowohl unter {@code textures/} (ItemsAdder 3.x) als auch unter
 * {@code resourcepack/assets/<ns>/textures/} (4.x).</p>
 *
 * <p>Eigenschaften, die im Betrieb zählen: idempotent (ein Pack wird nur bei geänderter Prüfsumme
 * neu geschrieben), zip-slip-sicher, und vollständig optional – fehlt ItemsAdder oder das Verzeichnis,
 * läuft die Lobby unverändert weiter. Die Packs selbst gehören dem Betreiber und werden nie mit dem
 * Plugin ausgeliefert.</p>
 */
public final class UiIconPackInstaller implements Service {

    private static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;
    private static final String MARKER_FILE = ".installed.properties";

    private final Plugin plugin;
    private final Logger logger;
    private final boolean enabled;
    private final Map<String, List<String>> installedPacks = new LinkedHashMap<>();
    private volatile boolean installedContent;

    public UiIconPackInstaller(Plugin plugin, boolean enabled, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled;
    }

    @Override
    public String id() {
        return "ui-icon-pack-installer";
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        Path directory = plugin.getDataFolder().toPath().resolve("icon-packs");
        try {
            Files.createDirectories(directory);
            Path readme = directory.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, readme(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            logger.warning("Icon packs: " + directory + " could not be prepared (" + exception.getMessage() + ").");
            return;
        }

        List<Path> sources = sources(directory);
        if (sources.isEmpty()) {
            logger.info("Icon packs: nothing in " + directory + " (drop the pack ZIPs or folders there).");
            return;
        }
        Path itemsAdder = plugin.getDataFolder().getParentFile().toPath().resolve("ItemsAdder");
        if (!Files.isDirectory(itemsAdder)) {
            logger.info("Icon packs: ItemsAdder is not installed – " + sources.size() + " pack(s) stay unused.");
            return;
        }

        Properties marker = readMarker(directory);
        int installed = 0;
        int current = 0;
        for (Path source : sources) {
            try {
                if (install(source, itemsAdder, marker)) {
                    installed++;
                } else {
                    current++;
                }
            } catch (IOException | RuntimeException exception) {
                logger.warning("Icon pack " + source.getFileName() + " could not be installed: " + exception.getMessage());
            }
        }
        writeMarker(directory, marker);

        if (!installedPacks.isEmpty()) {
            installedPacks.forEach((namespace, ids) -> logger.info("Icon pack '" + namespace + "': " + ids.size()
                    + " icons available as <" + namespace + ":" + ids.getFirst() + "> ... (see docs/tasticlobby-integrations.md)"));
        }
        logger.info("Icon packs: " + installed + " installed, " + current + " already current ("
                + sources.size() + " source(s))."
                + (installedContent ? " The ItemsAdder pack has to be regenerated (/iazip)." : ""));
    }

    @Override
    public void stop() {
        // nothing to release: installation happens once during start
    }

    /** Ob dieser Start neue Icons geschrieben hat – dann muss das ItemsAdder-Pack neu gebaut werden. */
    public boolean installedContent() {
        return installedContent;
    }

    /** Namensraum -> installierte Icon-IDs, für Statusausgaben. */
    public Map<String, List<String>> installedPacks() {
        return Map.copyOf(installedPacks);
    }

    private List<Path> sources(Path directory) {
        List<Path> sources = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.sorted().forEach(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isDirectory(path)) {
                    sources.add(path);
                } else if (name.endsWith(".zip")) {
                    sources.add(path);
                } else if (name.endsWith(".rar") || name.endsWith(".7z")) {
                    logger.warning("Icon packs: " + path.getFileName() + " is not a ZIP – unpack it into a folder"
                            + " next to it, folders are installed as well.");
                }
            });
        } catch (IOException exception) {
            logger.warning("Icon packs: " + directory + " could not be listed (" + exception.getMessage() + ").");
        }
        return sources;
    }

    /** @return true, wenn dieses Pack (neu) geschrieben wurde */
    private boolean install(Path source, Path itemsAdder, Properties marker) throws IOException {
        String key = source.getFileName().toString();
        String hash = Files.isDirectory(source) ? hashFolder(source) : sha256(source);
        String namespace = marker.getProperty(key + ".namespace", "");
        boolean upToDate = hash.equals(marker.getProperty(key + ".sha256"))
                && !namespace.isBlank()
                && Files.isRegularFile(itemsAdder.resolve("contents").resolve(namespace)
                        .resolve("configs").resolve(namespace + ".yml"));
        if (upToDate) {
            return false;
        }

        PackContent content = Files.isDirectory(source) ? readFolder(source) : readArchive(source);
        if (content == null) {
            logger.warning("Icon pack " + key + ": no usable configuration found (a YAML with info.namespace"
                    + " and font_images/textures is required).");
            return false;
        }
        IconPackDefinition definition = content.definition();
        Path contents = itemsAdder.resolve("contents").resolve(definition.namespace());
        Path configs = contents.resolve("configs");
        Path textures = contents.resolve("textures").resolve("font");
        Path modern = contents.resolve("resourcepack").resolve("assets")
                .resolve(definition.namespace()).resolve("textures").resolve("font");
        Files.createDirectories(configs);
        Files.createDirectories(textures);
        Files.createDirectories(modern);

        List<String> written = new ArrayList<>();
        for (IconPackDefinition.Entry entry : definition.entries().values()) {
            byte[] png = content.png(entry);
            if (png == null) {
                continue;
            }
            Files.write(textures.resolve(entry.id() + ".png"), png);
            Files.write(modern.resolve(entry.id() + ".png"), png);
            written.add(entry.id());
        }
        if (written.isEmpty()) {
            logger.warning("Icon pack " + key + ": the configuration lists " + definition.entries().size()
                    + " icons but no matching PNG was found - nothing installed.");
            return false;
        }
        // Nur die Icons in die Konfiguration schreiben, deren Bild wirklich da ist: ein Font-Image ohne
        // Textur lässt ItemsAdder beim Packen mit einer Fehlermeldung abbrechen.
        Map<String, IconPackDefinition.Entry> present = new LinkedHashMap<>();
        definition.entries().forEach((id, entry) -> {
            if (written.contains(id)) {
                present.put(id, entry);
            }
        });
        IconPackDefinition installable = new IconPackDefinition(definition.namespace(), present);
        Files.writeString(configs.resolve(definition.namespace() + ".yml"),
                installable.toItemsAdderConfig(), StandardCharsets.UTF_8);

        marker.setProperty(key + ".sha256", hash);
        marker.setProperty(key + ".namespace", definition.namespace());
        marker.setProperty(key + ".icons", String.valueOf(written.size()));
        installedPacks.put(definition.namespace(), List.copyOf(written));
        installedContent = true;
        logger.info("Icon pack " + key + " installed as '" + definition.namespace() + "' ("
                + written.size() + " icons) into " + contents + ".");
        return true;
    }

    // ------------------------------------------------------------------ Quellen lesen

    /** Konfiguration und PNG-Daten eines Packs, unabhängig davon, ob es Ordner oder Archiv war. */
    private record PackContent(IconPackDefinition definition, Map<String, byte[]> pngByFileName,
                               Map<String, byte[]> pngById) {

        byte[] png(IconPackDefinition.Entry entry) {
            byte[] byName = pngByFileName.get(entry.sourceName().toLowerCase(Locale.ROOT));
            return byName != null ? byName : pngById.get(entry.id());
        }
    }

    private PackContent readArchive(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<String> names = new ArrayList<>();
            zip.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> names.add(entry.getName()));
            IconPackLayout layout = IconPackLayout.detect(names);
            if (layout.isEmpty()) {
                return null;
            }
            IconPackDefinition definition = null;
            for (String candidate : layout.configCandidates()) {
                ZipEntry entry = zip.getEntry(candidate);
                if (entry == null || entry.getSize() > MAX_ENTRY_BYTES) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    definition = parse(in).orElse(null);
                }
                if (definition != null) {
                    break;
                }
            }
            if (definition == null) {
                return null;
            }
            Map<String, byte[]> byFileName = new LinkedHashMap<>();
            Map<String, byte[]> byId = new LinkedHashMap<>();
            for (Map.Entry<String, String> icon : layout.icons().entrySet()) {
                ZipEntry entry = zip.getEntry(icon.getValue());
                if (entry == null || entry.getSize() > MAX_ENTRY_BYTES) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] data = in.readAllBytes();
                    byId.put(icon.getKey(), data);
                    byFileName.put(fileName(icon.getValue()), data);
                }
            }
            return new PackContent(definition, byFileName, byId);
        }
    }

    private PackContent readFolder(Path folder) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, Path> byRelative = new LinkedHashMap<>();
        try (var stream = Files.walk(folder)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String relative = folder.relativize(path).toString().replace('\\', '/');
                names.add(relative);
                byRelative.put(relative, path);
            }
        }
        IconPackLayout layout = IconPackLayout.detect(names);
        if (layout.isEmpty()) {
            return null;
        }
        IconPackDefinition definition = null;
        for (String candidate : layout.configCandidates()) {
            Path path = byRelative.get(candidate);
            if (path == null || Files.size(path) > MAX_ENTRY_BYTES) {
                continue;
            }
            try (InputStream in = Files.newInputStream(path)) {
                definition = parse(in).orElse(null);
            }
            if (definition != null) {
                break;
            }
        }
        if (definition == null) {
            return null;
        }
        Map<String, byte[]> byFileName = new LinkedHashMap<>();
        Map<String, byte[]> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> icon : layout.icons().entrySet()) {
            Path path = byRelative.get(icon.getValue());
            if (path == null || Files.size(path) > MAX_ENTRY_BYTES) {
                continue;
            }
            byte[] data = Files.readAllBytes(path);
            byId.put(icon.getKey(), data);
            byFileName.put(fileName(icon.getValue()), data);
        }
        return new PackContent(definition, byFileName, byId);
    }

    private Optional<IconPackDefinition> parse(InputStream in) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return IconPackDefinition.parse(yaml);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ Marker

    private Properties readMarker(Path directory) {
        Properties marker = new Properties();
        Path file = directory.resolve(MARKER_FILE);
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                marker.load(reader);
            } catch (IOException exception) {
                logger.warning("Icon packs: install marker could not be read (" + exception.getMessage() + ").");
            }
        }
        return marker;
    }

    private void writeMarker(Path directory, Properties marker) {
        try (var writer = Files.newBufferedWriter(directory.resolve(MARKER_FILE), StandardCharsets.UTF_8)) {
            marker.store(writer, "TasticLobby icon pack installation state - delete to force a reinstall");
        } catch (IOException exception) {
            logger.warning("Icon packs: install marker could not be written (" + exception.getMessage() + ").");
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /** Ordner werden über Namen, Größen und Änderungszeiten erkannt – schnell und ausreichend. */
    private static String hashFolder(Path folder) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.walk(folder)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(folder.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
                    digest.update(Long.toString(Files.getLastModifiedTime(path).toMillis())
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String readme() {
        return """
                TasticLobby – UI icon packs
                ===========================

                Drop your purchased UI icon packs here, either as .zip or as an unpacked folder.
                On every start TasticLobby installs them into plugins/ItemsAdder/contents/<namespace>/:

                  configs/<namespace>.yml                                  the font image definitions
                  textures/font/<icon>.png                                 ItemsAdder 3.x
                  resourcepack/assets/<namespace>/textures/font/<icon>.png  ItemsAdder 4.x

                Both pack layouts are understood: a ready made ItemsAdder folder
                (itemsadder/contents/<pack>/...) and the simplified vendor format
                (configs/ItemsAdder/icons.yml plus icons/*.png). Previews, 312 px exports,
                Aseprite sources and "needs_review" folders are skipped.

                A pack is only rewritten when it changed. Afterwards the ItemsAdder pack has to be
                regenerated - the HUD service runs /iazip for you when the config allows it.

                Use an icon in hud.yml like this:

                  icons-map:
                    settings: "minimalui:settings"
                    prestige: "narra_icons_items:gold_star"

                The packs are your licensed content and are never bundled with the plugin.
                """;
    }
}
