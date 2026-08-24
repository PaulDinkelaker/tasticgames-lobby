package de.tasticgames.lobby.integration.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import de.tasticgames.lobby.integration.Integration;
import de.tasticgames.service.Service;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Baut fertige Welten aus Schematics, die der Betreiber in {@code plugins/TasticLobby/schematics/}
 * ablegt – über FastAsyncWorldEdit, sonst gar nicht.
 *
 * <p>„Sonst gar nicht" ist Absicht. Gekaufte Karten sind groß: die Candyland-Vorlage der offenen
 * Cookie-Welt misst 879 × 338 × 862 Blöcke, also gut 250 Millionen Blockpositionen. FastAsyncWorldEdit
 * verarbeitet so etwas gestreamt und nebenläufig; das gewöhnliche WorldEdit lädt die Zwischenablage
 * vollständig in den Speicher und würde den Server im besten Fall minutenlang einfrieren. Deshalb
 * wird ohne FAWE mit einer klaren Meldung abgelehnt, statt es zu versuchen.</p>
 *
 * <p>Jede Einfügung wird in {@code .pasted.properties} vermerkt und danach nie wiederholt: ein
 * zweites Einfügen würde die Bauten der Spieler überschreiben. Der Betreiber kann den Eintrag
 * löschen oder das Einfügen ausdrücklich erzwingen.</p>
 */
public final class SchematicPasteService implements Service {

    private static final String MARKER_FILE = ".pasted.properties";

    /** Ab dieser Blockzahl gilt eine Vorlage als groß; ohne FAWE wird sie nicht angefasst. */
    private static final long LARGE_VOLUME = 5_000_000L;

    public enum Outcome {
        PASTED,
        /** Schon einmal eingefügt – nichts getan. */
        ALREADY_DONE,
        DISABLED,
        NO_WORLDEDIT,
        /** Zu groß für gewöhnliches WorldEdit. */
        NEEDS_FAWE,
        MISSING_FILE,
        MISSING_WORLD,
        FAILED
    }

    /**
     * @param worldName Zielwelt
     * @param file      Dateiname in {@code plugins/TasticLobby/schematics/}
     * @param origin    Position, an die die Vorlage gesetzt wird
     * @param pasteAir  ob Luftblöcke der Vorlage bestehende Blöcke löschen
     */
    public record Request(String worldName, String file, int x, int y, int z, boolean pasteAir) {

        public String markerKey() {
            return worldName + "|" + file + "|" + x + "," + y + "," + z;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private volatile boolean worldEditAvailable;
    private volatile boolean fawe;

    public SchematicPasteService(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "schematic-paste-service";
    }

    @Override
    public void start() throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);
        Path readme = directory.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme, readme(), StandardCharsets.UTF_8);
        }
        fawe = Integration.pluginEnabled("FastAsyncWorldEdit");
        boolean worldEdit = fawe || Integration.pluginEnabled("WorldEdit");
        if (!worldEdit) {
            logger.info("Schematics: neither FastAsyncWorldEdit nor WorldEdit is installed – prepared builds stay unused.");
            return;
        }
        try {
            worldEditAvailable = WorldEdit.getInstance() != null;
        } catch (Throwable throwable) {
            worldEditAvailable = false;
            logger.warning("Schematics: the WorldEdit hook failed (" + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage() + ").");
        }
    }

    @Override
    public void stop() {
        worldEditAvailable = false;
    }

    public boolean available() {
        return worldEditAvailable;
    }

    public boolean fastAsyncWorldEdit() {
        return fawe;
    }

    /** Ob diese Einfügung schon einmal gelaufen ist. */
    public boolean alreadyPasted(Request request) {
        return marker().containsKey(request.markerKey());
    }

    /**
     * Fügt eine Vorlage ein. Läuft mit FAWE nebenläufig und blockiert den Hauptthread nicht.
     *
     * @param force auch dann einfügen, wenn es laut Vermerk schon geschehen ist
     */
    public CompletableFuture<Outcome> paste(Request request, boolean force) {
        Objects.requireNonNull(request, "request");
        if (!worldEditAvailable) {
            return CompletableFuture.completedFuture(Outcome.NO_WORLDEDIT);
        }
        if (!force && alreadyPasted(request)) {
            return CompletableFuture.completedFuture(Outcome.ALREADY_DONE);
        }
        Path file = directory().resolve(request.file());
        if (!Files.isRegularFile(file)) {
            logger.warning("Schematic " + request.file() + " was not found in " + directory() + ".");
            return CompletableFuture.completedFuture(Outcome.MISSING_FILE);
        }
        World world = Bukkit.getWorld(request.worldName());
        if (world == null) {
            return CompletableFuture.completedFuture(Outcome.MISSING_WORLD);
        }

        CompletableFuture<Outcome> result = new CompletableFuture<>();
        Runnable work = () -> result.complete(pasteNow(request, file, world));
        if (fawe) {
            // FAWE arbeitet nebenläufig; ein Einfügen dieser Größe gehört nicht auf den Hauptthread
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
        return result;
    }

    private Outcome pasteNow(Request request, Path file, World world) {
        long start = System.nanoTime();
        try {
            ClipboardFormat format = ClipboardFormats.findByFile(file.toFile());
            if (format == null) {
                logger.warning("Schematic " + request.file() + ": unknown format (.schem and .schematic are supported).");
                return Outcome.FAILED;
            }
            Clipboard clipboard;
            try (InputStream in = Files.newInputStream(file);
                 ClipboardReader reader = format.getReader(in)) {
                clipboard = reader.read();
            }
            long volume = clipboard.getRegion().getVolume();
            if (!fawe && volume > LARGE_VOLUME) {
                logger.severe("Schematic " + request.file() + " covers " + volume + " blocks. Plain WorldEdit would"
                        + " load all of it into memory and freeze the server – install FastAsyncWorldEdit to paste it.");
                return Outcome.NEEDS_FAWE;
            }
            BlockVector3 target = BlockVector3.at(request.x(), request.y(), request.z());
            try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(session)
                        .to(target)
                        .ignoreAirBlocks(!request.pasteAir())
                        .build();
                Operations.complete(operation);
            }
            remember(request, volume);
            logger.info("Schematic " + request.file() + " pasted into '" + world.getName() + "' at "
                    + request.x() + "/" + request.y() + "/" + request.z() + " (" + volume + " blocks, "
                    + (System.nanoTime() - start) / 1_000_000_000L + " s).");
            return Outcome.PASTED;
        } catch (Throwable throwable) {
            logger.warning("Schematic " + request.file() + " could not be pasted: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return Outcome.FAILED;
        }
    }

    private Path directory() {
        return plugin.getDataFolder().toPath().resolve("schematics");
    }

    private Properties marker() {
        Properties marker = new Properties();
        Path file = directory().resolve(MARKER_FILE);
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                marker.load(reader);
            } catch (IOException exception) {
                logger.warning("Schematics: paste marker could not be read (" + exception.getMessage() + ").");
            }
        }
        return marker;
    }

    private void remember(Request request, long volume) {
        Properties marker = marker();
        marker.setProperty(request.markerKey(), java.time.Instant.now() + " blocks=" + volume);
        try (var writer = Files.newBufferedWriter(directory().resolve(MARKER_FILE), StandardCharsets.UTF_8)) {
            marker.store(writer, "TasticLobby schematic pastes - delete an entry to allow pasting it again");
        } catch (IOException exception) {
            logger.warning("Schematics: paste marker could not be written (" + exception.getMessage() + ") -"
                    + " the schematic may be pasted again on the next start.");
        }
    }

    private static String readme() {
        return """
                TasticLobby – schematics
                ========================

                Drop .schem files here. They are pasted once, by FastAsyncWorldEdit, into the world the
                configuration names – for the Cookie open world see open-world.schematic in
                config/cookie-clicker.yml.

                Why FastAsyncWorldEdit is required for big builds: a bought map easily covers a few hundred
                million block positions. FAWE streams that; plain WorldEdit loads the whole clipboard into
                memory and freezes the server. Builds above five million blocks are therefore refused
                without FAWE instead of being attempted.

                Every paste is recorded in .pasted.properties and never repeated - otherwise a restart
                would overwrite whatever players built there. Delete the line to allow it again.

                The builds are your licensed content and are never bundled with the plugin.
                """;
    }
}
