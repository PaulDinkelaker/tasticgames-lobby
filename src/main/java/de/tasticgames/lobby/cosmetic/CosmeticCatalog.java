package de.tasticgames.lobby.cosmetic;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Cosmetic catalog loaded from cosmetics.yml (validated: unique ids, known category/rarity/material).
 */
public final class CosmeticCatalog {

    private final Map<String, CosmeticDefinition> definitions;

    private CosmeticCatalog(Map<String, CosmeticDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public static CosmeticCatalog load(YamlConfiguration yaml) {
        Map<String, CosmeticDefinition> definitions = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("cosmetics");
        if (root == null) {
            throw new IllegalArgumentException("cosmetics.yml: missing 'cosmetics' section");
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            CosmeticCategory category = CosmeticCategory.find(s.getString("category")).orElseThrow(
                    () -> new IllegalArgumentException("cosmetics.yml: unknown category for " + id + ": " + s.getString("category")));
            CosmeticRarity rarity = CosmeticRarity.find(s.getString("rarity", "COMMON")).orElseThrow(
                    () -> new IllegalArgumentException("cosmetics.yml: unknown rarity for " + id));
            Material material = Material.matchMaterial(s.getString("preview", "PAPER").toUpperCase(Locale.ROOT));
            if (material == null) {
                throw new IllegalArgumentException("cosmetics.yml: unknown preview material for " + id);
            }
            CosmeticDefinition definition = new CosmeticDefinition(id.toLowerCase(Locale.ROOT), category, rarity,
                    s.getString("name-key", "lobby.cosmetic." + id + ".name"), s.getString("description-key"),
                    material, s.getString("unlock", "ADMIN"), s.getString("unlock-requirement-key"),
                    s.getString("render", ""), s.getBoolean("enabled", true), s.getInt("sort", 100));
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("cosmetics.yml: duplicate cosmetic id " + id);
            }
        }
        return new CosmeticCatalog(definitions);
    }

    public Optional<CosmeticDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(definitions.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<CosmeticDefinition> all() {
        return definitions.values();
    }

    public List<CosmeticDefinition> byCategory(CosmeticCategory category) {
        List<CosmeticDefinition> list = new ArrayList<>();
        for (CosmeticDefinition definition : definitions.values()) {
            if (definition.category() == category && definition.enabled()) {
                list.add(definition);
            }
        }
        list.sort(java.util.Comparator.comparingInt(CosmeticDefinition::sortPriority).thenComparing(CosmeticDefinition::id));
        return list;
    }

    public int size() {
        return definitions.size();
    }
}
