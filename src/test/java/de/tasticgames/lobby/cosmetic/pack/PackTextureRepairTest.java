package de.tasticgames.lobby.cosmetic.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Packs ship textures one folder too deep; the installer copies them where the models look for them. */
class PackTextureRepairTest {

    private static final String MODEL = """
            {
              "texture_size": [64, 64],
              "textures": {
                "1": "cosmetic_expansion_v1:phantom_king_crown_animation",
                "3": "cosmetic_expansion_v1:phantom_king_crown",
                "particle": "minecraft:item/leather_horse_armor"
              }
            }
            """;

    private Path namespace(Path assets) throws IOException {
        Path ns = assets.resolve("cosmetic_expansion_v1");
        Files.createDirectories(ns.resolve("models"));
        Files.writeString(ns.resolve("models").resolve("phantom_king_crown.json"), MODEL, StandardCharsets.UTF_8);
        Files.createDirectories(ns.resolve("textures").resolve("cosmetic_expansion_v1"));
        return ns;
    }

    private void texture(Path directory, String name) throws IOException {
        Files.write(directory.resolve(name), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }

    @Test
    void texturesAreCopiedToThePathTheModelReferences(@TempDir Path assets) throws IOException {
        Path ns = namespace(assets);
        Path deep = ns.resolve("textures").resolve("cosmetic_expansion_v1");
        texture(deep, "phantom_king_crown.png");
        texture(deep, "phantom_king_crown_animation.png");
        Files.writeString(deep.resolve("phantom_king_crown_animation.png.mcmeta"), "{}", StandardCharsets.UTF_8);

        List<PackTextureRepair.Repair> repairs = PackTextureRepair.repair(assets);

        assertEquals(2, repairs.size());
        assertTrue(Files.exists(ns.resolve("textures").resolve("phantom_king_crown.png")));
        assertTrue(Files.exists(ns.resolve("textures").resolve("phantom_king_crown_animation.png")));
        assertTrue(Files.exists(ns.resolve("textures").resolve("phantom_king_crown_animation.png.mcmeta")),
                "an animated texture keeps its .mcmeta");
        assertTrue(Files.exists(deep.resolve("phantom_king_crown.png")), "the original file stays where it was");
    }

    @Test
    void aConsistentPackIsLeftAlone(@TempDir Path assets) throws IOException {
        Path ns = namespace(assets);
        texture(ns.resolve("textures"), "phantom_king_crown.png");
        texture(ns.resolve("textures"), "phantom_king_crown_animation.png");

        assertEquals(List.of(), PackTextureRepair.repair(assets));
    }

    @Test
    void aTextureThatDoesNotExistAtAllIsNotInvented(@TempDir Path assets) throws IOException {
        Path ns = namespace(assets);
        texture(ns.resolve("textures").resolve("cosmetic_expansion_v1"), "phantom_king_crown.png");

        List<PackTextureRepair.Repair> repairs = PackTextureRepair.repair(assets);

        assertEquals(1, repairs.size());
        assertFalse(Files.exists(ns.resolve("textures").resolve("phantom_king_crown_animation.png")));
    }

    @Test
    void anAmbiguousNameIsLeftToTheOperator(@TempDir Path assets) throws IOException {
        Path ns = namespace(assets);
        Path deep = ns.resolve("textures").resolve("cosmetic_expansion_v1");
        texture(deep, "phantom_king_crown.png");
        Path other = ns.resolve("textures").resolve("other");
        Files.createDirectories(other);
        texture(other, "phantom_king_crown.png");

        assertEquals(List.of(), PackTextureRepair.repair(assets).stream()
                .filter(r -> r.reference().endsWith("phantom_king_crown")).toList());
    }

    @Test
    void onlyOwnNamespaceReferencesAreConsidered() {
        Set<String> references = PackTextureRepair.referencesOf(MODEL, "cosmetic_expansion_v1");
        assertEquals(Set.of("phantom_king_crown_animation", "phantom_king_crown"), references);
        assertEquals(Set.of("item/leather_horse_armor"), PackTextureRepair.referencesOf(MODEL, "minecraft"));
    }
}
