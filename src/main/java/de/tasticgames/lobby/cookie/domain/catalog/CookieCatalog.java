package de.tasticgames.lobby.cookie.domain.catalog;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable aggregate of all game content definitions with lookup maps.
 * The constructor validates the content via {@link CatalogValidator}.
 */
public final class CookieCatalog {

    private final List<GeneratorDefinition> generators;
    private final List<UpgradeDefinition> upgrades;
    private final List<PrestigeDefinition> prestiges;
    private final List<PrestigeTreeNode> prestigeTree;
    private final List<ZoneDefinition> zones;
    private final List<AchievementDefinition> achievements;

    private final Map<String, GeneratorDefinition> generatorsById;
    private final Map<String, UpgradeDefinition> upgradesById;
    private final Map<Integer, PrestigeDefinition> prestigesByLevel;
    private final Map<String, PrestigeTreeNode> treeNodesById;
    private final Map<String, ZoneDefinition> zonesById;
    private final Map<String, AchievementDefinition> achievementsById;

    public CookieCatalog(List<GeneratorDefinition> generators,
                         List<UpgradeDefinition> upgrades,
                         List<PrestigeDefinition> prestiges,
                         List<PrestigeTreeNode> prestigeTree,
                         List<ZoneDefinition> zones,
                         List<AchievementDefinition> achievements) {
        this.generators = List.copyOf(Objects.requireNonNull(generators, "generators")).stream()
                .sorted(Comparator.comparingInt(GeneratorDefinition::order)).toList();
        this.upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        this.prestiges = List.copyOf(Objects.requireNonNull(prestiges, "prestiges")).stream()
                .sorted(Comparator.comparingInt(PrestigeDefinition::level)).toList();
        this.prestigeTree = List.copyOf(Objects.requireNonNull(prestigeTree, "prestigeTree"));
        this.zones = List.copyOf(Objects.requireNonNull(zones, "zones")).stream()
                .sorted(Comparator.comparingInt(ZoneDefinition::order)).toList();
        this.achievements = List.copyOf(Objects.requireNonNull(achievements, "achievements"));

        CatalogValidator.validate(this.generators, this.upgrades, this.prestiges, this.prestigeTree, this.zones, this.achievements);

        this.generatorsById = index(this.generators, GeneratorDefinition::id);
        this.upgradesById = index(this.upgrades, UpgradeDefinition::id);
        this.treeNodesById = index(this.prestigeTree, PrestigeTreeNode::id);
        this.zonesById = index(this.zones, ZoneDefinition::id);
        this.achievementsById = index(this.achievements, AchievementDefinition::id);
        Map<Integer, PrestigeDefinition> byLevel = new LinkedHashMap<>();
        for (PrestigeDefinition p : this.prestiges) byLevel.put(p.level(), p);
        this.prestigesByLevel = Map.copyOf(byLevel);
    }

    private static <T> Map<String, T> index(List<T> items, java.util.function.Function<T, String> idFn) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) map.put(idFn.apply(item), item);
        return Map.copyOf(map);
    }

    /** The default content shipped with the game. */
    public static CookieCatalog defaults() {
        return new CookieCatalog(
                DefaultCatalog.generators(),
                DefaultCatalog.upgrades(),
                DefaultCatalog.prestiges(),
                DefaultCatalog.prestigeTree(),
                DefaultCatalog.zones(),
                DefaultCatalog.achievements());
    }

    // ------------------------------------------------------------------ lists

    public List<GeneratorDefinition> generators() { return generators; }
    public List<UpgradeDefinition> upgrades() { return upgrades; }
    public List<PrestigeDefinition> prestiges() { return prestiges; }
    public List<PrestigeTreeNode> prestigeTree() { return prestigeTree; }
    public List<ZoneDefinition> zones() { return zones; }
    public List<AchievementDefinition> achievements() { return achievements; }

    // ------------------------------------------------------------------ lookups

    public Optional<GeneratorDefinition> generator(String id) { return Optional.ofNullable(generatorsById.get(id)); }
    public Optional<UpgradeDefinition> upgrade(String id) { return Optional.ofNullable(upgradesById.get(id)); }
    public Optional<PrestigeDefinition> prestige(int level) { return Optional.ofNullable(prestigesByLevel.get(level)); }
    public Optional<PrestigeTreeNode> treeNode(String id) { return Optional.ofNullable(treeNodesById.get(id)); }
    public Optional<ZoneDefinition> zone(String id) { return Optional.ofNullable(zonesById.get(id)); }
    public Optional<AchievementDefinition> achievement(String id) { return Optional.ofNullable(achievementsById.get(id)); }

    public GeneratorDefinition requireGenerator(String id) {
        return generator(id).orElseThrow(() -> new IllegalArgumentException("Unknown generator: " + id));
    }

    public UpgradeDefinition requireUpgrade(String id) {
        return upgrade(id).orElseThrow(() -> new IllegalArgumentException("Unknown upgrade: " + id));
    }

    public PrestigeDefinition requirePrestige(int level) {
        return prestige(level).orElseThrow(() -> new IllegalArgumentException("Unknown prestige level: " + level));
    }

    public PrestigeTreeNode requireTreeNode(String id) {
        return treeNode(id).orElseThrow(() -> new IllegalArgumentException("Unknown prestige tree node: " + id));
    }

    public ZoneDefinition requireZone(String id) {
        return zone(id).orElseThrow(() -> new IllegalArgumentException("Unknown zone: " + id));
    }

    /** Highest defined prestige level. */
    public int maxPrestigeLevel() {
        return prestiges.getLast().level();
    }

    /** Upgrades whose effect targets the given generator. */
    public List<UpgradeDefinition> upgradesForGenerator(String generatorId) {
        return upgrades.stream()
                .filter(u -> u.effect().type() == UpgradeEffectType.GENERATOR_MULTIPLIER
                        && generatorId.equals(u.effect().generatorId()))
                .toList();
    }
}
