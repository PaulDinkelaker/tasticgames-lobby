package de.tasticgames.lobby.cosmetic.pack;

import de.tasticgames.lobby.integration.item.CustomItemProvider;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs cosmetic asset packs the operator drops into {@code plugins/TasticLobby/cosmetic-packs/} – the ZIPs
 * as they are sold, which ship an {@code ItemsAdder/contents/<pack>} folder plus the matching
 * {@code HMCCosmetics/cosmetics} definitions. On every start each archive is installed into the ItemsAdder,
 * HMCCosmetics and ModelEngine plugin folders; afterwards the ItemsAdder pack is rebuilt (see the HUD service,
 * which owns {@code /iazip}) and HMCCosmetics is reloaded.
 * <p>
 * Properties that matter in production:
 * <ul>
 *   <li>idempotent – an archive is only unpacked again when its SHA-256 changed or an installed file is missing,</li>
 *   <li>non-destructive – HMCCosmetics menus are written once and never overwritten, so operator edits survive,</li>
 *   <li>safe – entries that would escape their target directory (zip slip) are refused,</li>
 *   <li>optional – nothing here is required for the lobby to run; failures are logged and skipped.</li>
 * </ul>
 * The archives are never bundled with the plugin: they are the operator's licensed content.
 */
public final class CosmeticPackInstaller implements Service {

    /** Guard against absurd entries (the biggest legitimate file in these packs is a few MB). */
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final String MARKER_FILE = ".installed.properties";
    private static final long HMC_RELOAD_DELAY_TICKS = 60L;

    private final Plugin plugin;
    private final CustomItemProvider customItems;
    private final Logger logger;
    private final boolean enabled;
    private final boolean reloadHmcCosmetics;
    private volatile boolean installedContent;
    private volatile boolean hmcReloadPending;

    public CosmeticPackInstaller(Plugin plugin, CustomItemProvider customItems, boolean enabled,
                                 boolean reloadHmcCosmetics, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.customItems = Objects.requireNonNull(customItems, "customItems");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled;
        this.reloadHmcCosmetics = reloadHmcCosmetics;
    }

    @Override
    public String id() {
        return "cosmetic-pack-installer";
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        Path directory = plugin.getDataFolder().toPath().resolve("cosmetic-packs");
        try {
            Files.createDirectories(directory);
            Path readme = directory.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, readme(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.warning("Cosmetic packs: " + directory + " could not be prepared (" + e.getMessage() + ").");
            return;
        }
        List<Path> archives = archives(directory);
        if (archives.isEmpty()) {
            logger.info("Cosmetic packs: no archives in " + directory + " (drop the pack ZIPs there).");
            return;
        }
        Properties marker = readMarker(directory);
        int installed = 0;
        int current = 0;
        for (Path archive : archives) {
            try {
                if (install(archive, marker)) {
                    installed++;
                } else {
                    current++;
                }
            } catch (java.util.zip.ZipException e) {
                logger.warning("Cosmetic pack " + archive.getFileName() + " could not be read (" + e.getMessage()
                        + ") - password protected or nested archives have to be unpacked once by hand.");
            } catch (IOException | RuntimeException e) {
                logger.warning("Cosmetic pack " + archive.getFileName() + " could not be installed: " + e.getMessage());
            }
        }
        writeMarker(directory, marker);
        logger.info("Cosmetic packs: " + installed + " installed, " + current + " already current ("
                + archives.size() + " archives).");
        if (installedContent && reloadHmcCosmetics) {
            hmcReloadPending = true;
            // HMCCosmetics resolves ItemsAdder items, so it is reloaded only after the pack was rebuilt
            customItems.onReady(this::reloadHmcCosmeticsNow);
        }
    }

    @Override
    public void stop() {
        // nothing to release: installation happens once during start
    }

    /** Whether this start installed new or changed content, so the ItemsAdder pack has to be rebuilt. */
    public boolean installedContent() {
        return installedContent;
    }

    private List<Path> archives(Path directory) {
        List<Path> archives = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".zip")) {
                    archives.add(path);
                } else if (name.endsWith(".rar") || name.endsWith(".7z")) {
                    logger.warning("Cosmetic packs: " + path.getFileName()
                            + " is not a ZIP – repack it as .zip so it can be installed.");
                }
            });
        } catch (IOException e) {
            logger.warning("Cosmetic packs: " + directory + " could not be listed (" + e.getMessage() + ").");
        }
        return archives;
    }

    /** @return true when the archive was (re)installed */
    private boolean install(Path archive, Properties marker) throws IOException {
        String key = archive.getFileName().toString();
        String hash = sha256(archive);
        Path pluginsRoot = plugin.getDataFolder().getParentFile().toPath();
        List<String> previous = installedFiles(marker, key);
        boolean upToDate = hash.equals(marker.getProperty(key + ".sha256"))
                && !previous.isEmpty()
                && previous.stream().allMatch(relative -> Files.exists(pluginsRoot.resolve(relative)));
        if (upToDate) {
            return false;
        }
        Set<String> written = new LinkedHashSet<>();
        Set<String> packs = new LinkedHashSet<>();
        Map<String, Integer> perPlugin = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                Optional<CosmeticPackTarget> target = CosmeticPackTarget.of(entry.getName());
                if (target.isEmpty()) {
                    continue;
                }
                if (entry.getSize() > MAX_ENTRY_BYTES) {
                    logger.warning("Cosmetic pack " + key + ": skipping oversized entry " + entry.getName());
                    continue;
                }
                CosmeticPackTarget destinationTarget = target.get();
                Path pluginDirectory = pluginsRoot.resolve(destinationTarget.plugin()).normalize();
                Path destination = pluginDirectory.resolve(destinationTarget.relativePath()).normalize();
                if (!destination.startsWith(pluginDirectory)) {
                    logger.warning("Cosmetic pack " + key + ": refusing an entry outside its target directory ("
                            + entry.getName() + ")");
                    continue;
                }
                if (destinationTarget.protectedFile() && Files.exists(destination)) {
                    continue; // operator owned file (menus)
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(pluginsRoot.relativize(destination).toString().replace('\\', '/'));
                perPlugin.merge(destinationTarget.plugin(), 1, Integer::sum);
                if (!destinationTarget.contentPack().isEmpty()) {
                    packs.add(destinationTarget.contentPack());
                }
            }
        }
        marker.setProperty(key + ".sha256", hash);
        marker.setProperty(key + ".files", String.join("|", written));
        if (written.isEmpty()) {
            logger.warning("Cosmetic pack " + key
                    + " contains no ItemsAdder/HMCCosmetics content that TasticLobby can install.");
            return false;
        }
        installedContent = true;
        logger.info("Cosmetic pack " + key + " installed: " + perPlugin
                + (packs.isEmpty() ? "" : ", content packs " + packs) + ".");
        return true;
    }

    private void reloadHmcCosmeticsNow() {
        if (!hmcReloadPending) {
            return;
        }
        hmcReloadPending = false;
        if (Bukkit.getPluginManager().getPlugin("HMCCosmetics") == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            logger.info("Reloading HMCCosmetics so the freshly installed cosmetics are registered...");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hmccosmetics reload");
        }, HMC_RELOAD_DELAY_TICKS);
    }

    private static List<String> installedFiles(Properties marker, String key) {
        String value = marker.getProperty(key + ".files", "");
        return value.isBlank() ? List.of() : List.of(value.split("\\|"));
    }

    private Properties readMarker(Path directory) {
        Properties properties = new Properties();
        Path file = directory.resolve(MARKER_FILE);
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                logger.warning("Cosmetic packs: " + MARKER_FILE + " could not be read (" + e.getMessage()
                        + ") – everything is installed again.");
            }
        }
        return properties;
    }

    private void writeMarker(Path directory, Properties marker) {
        try (var out = Files.newOutputStream(directory.resolve(MARKER_FILE))) {
            marker.store(out, "Installed cosmetic packs (SHA-256 + installed files). Remove a pack's lines to reinstall it.");
        } catch (IOException e) {
            logger.warning("Cosmetic packs: state could not be written (" + e.getMessage() + ").");
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String readme() {
        return """
                Drop cosmetic packs (ZIP) into this folder - TasticLobby installs them on every server start.

                What is installed:
                  * ItemsAdder/contents/<pack>/**      -> plugins/ItemsAdder/contents/<pack>/**
                  * HMCCosmetics/cosmetics/<file>.yml  -> plugins/HMCCosmetics/cosmetics/<file>.yml
                  * HMCCosmetics/menus/<file>.yml      -> plugins/HMCCosmetics/menus/<file>.yml (only when missing)
                  * ModelEngine/blueprints/*.bbmodel   -> plugins/ModelEngine/blueprints/
                Variants for other plugins (Oraxen, Nexo, MagicCosmetics, CosmeticsCore) and legacy setups for old
                Minecraft versions are ignored.

                Notes:
                  * Only ZIP archives are read - repack .rar/.7z packs as .zip first.
                  * A pack is unpacked again when its content changed (SHA-256 in .installed.properties) or when an
                    installed file was deleted. HMCCosmetics menus are never overwritten.
                  * After an install the ItemsAdder pack is rebuilt (/iazip) and HMCCosmetics is reloaded.
                  * A cosmetic only shows up in the lobby menu when config/cosmetics.yml lists it with
                    render: "hmc:<hmccosmetics-id>".
                """;
    }
}
