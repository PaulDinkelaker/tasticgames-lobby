package de.tasticgames.lobby.cosmetic;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Objects;

/**
 * Native renderer without external plugins: hats (helmet slot with a PDC marker), auras/trails/
 * join effects via particles. renderData formats:
 * HAT: {@code material:PUMPKIN[;model:namespace:key]} – AURA/TRAIL: {@code particle:FLAME[;count:6][;color:RRGGBB]}.
 */
public final class NativeCosmeticRenderer implements CosmeticRenderer {

    private final NamespacedKey hatKey;

    public NativeCosmeticRenderer(Plugin plugin) {
        this.hatKey = new NamespacedKey(plugin, "cosmetic-hat");
    }

    @Override
    public String id() {
        return "native";
    }

    @Override
    public boolean supports(CosmeticCategory category) {
        return switch (category) {
            case HAT, AURA, TRAIL, JOIN_EFFECT -> true;
            default -> false;
        };
    }

    @Override
    public void apply(Player player, CosmeticCategory category, CosmeticDefinition definition, boolean reducedEffects) {
        if (category == CosmeticCategory.HAT) {
            if (definition == null) {
                clearHat(player);
                return;
            }
            Material material = Material.matchMaterial(value(definition.renderData(), "material", "PUMPKIN").toUpperCase(Locale.ROOT));
            ItemStack stack = new ItemStack(material == null ? Material.PUMPKIN : material);
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(hatKey, PersistentDataType.STRING, definition.id());
            String model = value(definition.renderData(), "model", "");
            if (!model.isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(model);
                if (key != null) meta.setItemModel(key);
            }
            meta.setUnbreakable(true);
            stack.setItemMeta(meta);
            player.getInventory().setHelmet(stack);
        } else if (category == CosmeticCategory.JOIN_EFFECT && definition != null) {
            burst(player.getLocation().add(0, 1, 0), definition, reducedEffects ? 10 : 40);
        }
    }

    @Override
    public void tick(Player player, CosmeticDefinition definition, boolean reducedEffects) {
        if (definition.category() == CosmeticCategory.AURA) {
            int count = Math.max(1, Integer.parseInt(value(definition.renderData(), "count", "6")) / (reducedEffects ? 3 : 1));
            double angle = (System.currentTimeMillis() % 2000) / 2000.0 * Math.PI * 2;
            Location base = player.getLocation().add(Math.cos(angle) * 0.9, 1.0, Math.sin(angle) * 0.9);
            spawn(base, definition, count, 0.1, 0.3, 0.1);
        } else if (definition.category() == CosmeticCategory.TRAIL) {
            if (player.getVelocity().lengthSquared() < 0.01) {
                return;
            }
            int count = Math.max(1, Integer.parseInt(value(definition.renderData(), "count", "4")) / (reducedEffects ? 2 : 1));
            spawn(player.getLocation().add(0, 0.2, 0), definition, count, 0.2, 0.05, 0.2);
        }
    }

    @Override
    public void clear(Player player) {
        clearHat(player);
    }

    private void clearHat(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.hasItemMeta() && helmet.getItemMeta().getPersistentDataContainer().has(hatKey, PersistentDataType.STRING)) {
            player.getInventory().setHelmet(null);
        }
    }

    public boolean isCosmeticHat(ItemStack stack) {
        return stack != null && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(hatKey, PersistentDataType.STRING);
    }

    private void burst(Location location, CosmeticDefinition definition, int count) {
        spawn(location, definition, count, 0.6, 0.8, 0.6);
    }

    private void spawn(Location location, CosmeticDefinition definition, int count, double dx, double dy, double dz) {
        String particleName = value(definition.renderData(), "particle", "END_ROD").toUpperCase(Locale.ROOT);
        Particle particle;
        try {
            particle = Particle.valueOf(particleName);
        } catch (IllegalArgumentException e) {
            particle = Particle.END_ROD;
        }
        if (particle == Particle.DUST) {
            String hex = value(definition.renderData(), "color", "FFFFFF");
            Color color = Color.fromRGB(Integer.parseInt(hex, 16));
            location.getWorld().spawnParticle(particle, location, count, dx, dy, dz, 0, new Particle.DustOptions(color, 1.0f));
        } else {
            location.getWorld().spawnParticle(particle, location, count, dx, dy, dz, 0.01);
        }
    }

    private static String value(String data, String key, String fallback) {
        for (String part : data.split(";")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) {
                return kv[1].trim();
            }
        }
        return fallback;
    }
}
