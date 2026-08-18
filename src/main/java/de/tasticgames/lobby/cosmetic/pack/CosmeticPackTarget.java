package de.tasticgames.lobby.cosmetic.pack;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a single entry of a cosmetic pack archive has to be installed. The mapping is pure string work so it
 * can be unit tested without touching the disk or a real archive.
 * <p>
 * The packs are not consistent: the plugin folder may carry a suffix ({@code ItemsAdder Setup/},
 * {@code IA Configs/ItemsAdder/}), the resource pack folder is spelled {@code resource_pack} in older packs, and
 * most archives ship the same content several times – once per item plugin and sometimes once per Minecraft
 * generation. Only the ItemsAdder flavour of the current generation is installed.
 *
 * @param plugin        plugin folder the file belongs to ({@code ItemsAdder}, {@code HMCCosmetics}, {@code ModelEngine})
 * @param relativePath  path below that plugin folder, always with {@code /} separators
 * @param protectedFile file the installer must never overwrite once it exists (menus the operator tuned)
 */
public record CosmeticPackTarget(String plugin, String relativePath, boolean protectedFile) {

    public CosmeticPackTarget {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(relativePath, "relativePath");
    }

    /** Variants of the same content for other item plugins – we only install the ItemsAdder flavour. */
    private static final String[] FOREIGN_VARIANTS = {
            "oraxen", "nexo", "magiccosmetics", "cosmeticscore", "mythicart", "mcpets"
    };
    /** Setups for older Minecraft generations shipped next to the current ones. */
    private static final String[] LEGACY_VARIANTS = {
            "1.20.1 or lower", "1.19", "1.18", "1.16", "1.12", "datapack 1.20+", "shader setup"
    };

    /** {@code .../ItemsAdder[ Setup|...]/} – the folder name may carry a suffix. */
    private static final Pattern ITEMS_ADDER = Pattern.compile("(?:^|(?<=/))itemsadder[^/]*/", Pattern.CASE_INSENSITIVE);
    private static final Pattern HMC_COSMETICS = Pattern.compile("(?:^|(?<=/))hmccosmetics[^/]*/", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_ENGINE = Pattern.compile("(?:^|(?<=/))modelengine[^/]*/", Pattern.CASE_INSENSITIVE);

    /**
     * Maps an archive entry to its installation target.
     *
     * @return empty for directories, foreign/legacy variants and anything that is not content we install
     */
    public static Optional<CosmeticPackTarget> of(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return Optional.empty();
        }
        String path = entryName.replace('\\', '/').replaceAll("/+", "/");
        if (path.endsWith("/")) {
            return Optional.empty();
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String variant : FOREIGN_VARIANTS) {
            if (lower.contains(variant)) {
                return Optional.empty();
            }
        }
        for (String variant : LEGACY_VARIANTS) {
            if (lower.contains(variant)) {
                return Optional.empty();
            }
        }
        for (String rest : remainders(ITEMS_ADDER, path)) {
            Optional<CosmeticPackTarget> target = itemsAdder(rest);
            if (target.isPresent()) {
                return target;
            }
        }
        for (String rest : remainders(HMC_COSMETICS, path)) {
            String restLower = rest.toLowerCase(Locale.ROOT);
            if (restLower.startsWith("cosmetics/") && restLower.endsWith(".yml")) {
                return Optional.of(new CosmeticPackTarget("HMCCosmetics", rest, false));
            }
            if (restLower.startsWith("menus/") && restLower.endsWith(".yml")) {
                // menus are usually customised by the operator: install once, never overwrite
                return Optional.of(new CosmeticPackTarget("HMCCosmetics", rest, true));
            }
        }
        for (String rest : remainders(MODEL_ENGINE, path)) {
            if (rest.toLowerCase(Locale.ROOT).startsWith("blueprints/") && lower.endsWith(".bbmodel")) {
                return Optional.of(new CosmeticPackTarget("ModelEngine", rest, false));
            }
        }
        return Optional.empty();
    }

    /** Everything after each occurrence of the plugin folder, innermost (last) match first. */
    private static java.util.List<String> remainders(Pattern pattern, String path) {
        java.util.List<String> rests = new java.util.ArrayList<>(2);
        Matcher matcher = pattern.matcher(path);
        while (matcher.find()) {
            rests.add(path.substring(matcher.end()));
        }
        java.util.Collections.reverse(rests);
        return rests;
    }

    /** Normalises an ItemsAdder content path ({@code resource_pack} is the old spelling of {@code resourcepack}). */
    private static Optional<CosmeticPackTarget> itemsAdder(String rest) {
        String normalised = rest.replace("/resource_pack/", "/resourcepack/");
        String lower = normalised.toLowerCase(Locale.ROOT);
        if (lower.startsWith("contents/")) {
            return lower.substring("contents/".length()).contains("/")
                    ? Optional.of(new CosmeticPackTarget("ItemsAdder", normalised, false))
                    : Optional.empty();
        }
        // older layout without the "contents" folder: <pack>/configs|resourcepack/...
        if (lower.contains("/configs/") || lower.contains("/resourcepack/")) {
            return Optional.of(new CosmeticPackTarget("ItemsAdder", "contents/" + normalised, false));
        }
        return Optional.empty();
    }

    /** Pack folder name ({@code contents/<pack>/...}) for reporting, or {@code ""}. */
    public String contentPack() {
        if (!"ItemsAdder".equals(plugin) || !relativePath.startsWith("contents/")) {
            return "";
        }
        String rest = relativePath.substring("contents/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
