package de.tasticgames.lobby.cosmetic.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Repairs texture paths of an installed ItemsAdder content pack.
 * <p>
 * Several of the shipped cosmetic packs put their PNGs one folder too deep: the models reference
 * {@code <namespace>:phantom_king_crown}, which the client resolves to
 * {@code assets/<namespace>/textures/phantom_king_crown.png}, while the archive ships the file as
 * {@code assets/<namespace>/textures/<namespace>/phantom_king_crown.png}. ItemsAdder then logs
 * {@code Texture '<namespace>:phantom_king_crown' not found for model} and the cosmetic renders untextured.
 * <p>
 * This class reads the model files, looks up every texture reference that has no file, and copies the one
 * matching PNG (plus its {@code .mcmeta}) to the place the model expects. Nothing is moved or overwritten, so
 * a pack that uses the deeper path on purpose keeps working; the copies are a few kilobytes each.
 */
public final class PackTextureRepair {

    /** {@code "cosmetic_expansion_v1:phantom_king_crown"} inside a model file. */
    private static final Pattern REFERENCE = Pattern.compile("\"([a-z0-9_.-]+):([a-z0-9_./-]+)\"");
    private static final int MAX_MODEL_BYTES = 4 * 1024 * 1024;

    private PackTextureRepair() {
    }

    /** One repaired reference: the model that asked for it and the file that was copied. */
    public record Repair(String namespace, String reference, Path from, Path to) {
    }

    /**
     * Repairs every namespace below {@code assetsRoot} ({@code .../resourcepack/assets}).
     *
     * @return the copies that were made (empty when the pack is consistent)
     */
    public static List<Repair> repair(Path assetsRoot) throws IOException {
        Objects.requireNonNull(assetsRoot, "assetsRoot");
        if (!Files.isDirectory(assetsRoot)) {
            return List.of();
        }
        List<Repair> repairs = new ArrayList<>();
        try (Stream<Path> namespaces = Files.list(assetsRoot)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                repairs.addAll(repairNamespace(namespace));
            }
        }
        return List.copyOf(repairs);
    }

    private static List<Repair> repairNamespace(Path namespaceDir) throws IOException {
        String namespace = namespaceDir.getFileName().toString();
        Path models = namespaceDir.resolve("models");
        Path textures = namespaceDir.resolve("textures");
        if (!Files.isDirectory(models) || !Files.isDirectory(textures)) {
            return List.of();
        }
        Set<String> references = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(models)) {
            for (Path model : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                if (Files.size(model) > MAX_MODEL_BYTES) {
                    continue; // a model file that large is not one of ours
                }
                references.addAll(referencesOf(Files.readString(model, StandardCharsets.UTF_8), namespace));
            }
        }
        List<Repair> repairs = new ArrayList<>();
        for (String reference : references) {
            Path expected = textures.resolve(reference + ".png");
            if (Files.exists(expected)) {
                continue;
            }
            Path found = findByName(textures, fileName(reference) + ".png");
            if (found == null) {
                continue; // the texture is genuinely missing – that is the pack's problem, not a path problem
            }
            Files.createDirectories(expected.getParent());
            Files.copy(found, expected);
            copyMeta(found, expected);
            repairs.add(new Repair(namespace, namespace + ":" + reference, found, expected));
        }
        return repairs;
    }

    /** Texture references of one model file that belong to {@code namespace}. */
    static Set<String> referencesOf(String modelJson, String namespace) {
        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(modelJson);
        while (matcher.find()) {
            if (namespace.equals(matcher.group(1))) {
                references.add(matcher.group(2));
            }
        }
        return references;
    }

    private static void copyMeta(Path from, Path to) throws IOException {
        Path meta = from.resolveSibling(from.getFileName() + ".mcmeta");
        if (Files.isRegularFile(meta)) {
            Files.copy(meta, to.resolveSibling(to.getFileName() + ".mcmeta"));
        }
    }

    /** The single file with that name below {@code root}, or {@code null} when there is none or several. */
    private static Path findByName(Path root, String fileName) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> matches = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .limit(2)
                    .toList();
            return matches.size() == 1 ? matches.getFirst() : null;
        }
    }

    private static String fileName(String reference) {
        int slash = reference.lastIndexOf('/');
        return slash < 0 ? reference : reference.substring(slash + 1);
    }
}
