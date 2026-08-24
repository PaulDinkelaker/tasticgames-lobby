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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Native renderer without external plugins: hats (helmet slot with a PDC marker), auras/trails/
 * join effects via particles. renderData formats:
 * HAT: {@code material:PUMPKIN[;model:namespace:key]} – AURA/TRAIL: {@code particle:FLAME[;count:6][;color:RRGGBB][;value:1.0]}.
 * <p>
 * Which extra data a particle needs is decided by the server version, not by us: {@code DUST} wants a colour,
 * {@code DRAGON_BREATH} a float, others nothing at all. {@link #data} asks the particle itself, so a Minecraft
 * update that adds a requirement does not turn the cosmetic tick into an exception per tick.
 */
public final class NativeCosmeticRenderer implements CosmeticRenderer {

    /** Particles whose data we cannot build; warned about once, then rendered as END_ROD. */
    private static final Object UNSUPPORTED = new Object();

    private final NamespacedKey hatKey;
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public NativeCosmeticRenderer(Plugin plugin) {
        this.hatKey = new NamespacedKey(plugin, "cosmetic-hat");
        this.logger = plugin.getLogger();
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
        Object data = data(particle, definition);
        if (data == UNSUPPORTED) {
            warnOnce(definition, particle, "needs " + particle.getDataType().getSimpleName() + " data we cannot build");
            particle = Particle.END_ROD;
            data = null;
        }
        try {
            if (data == null) {
                location.getWorld().spawnParticle(particle, location, count, dx, dy, dz, 0.01);
            } else {
                location.getWorld().spawnParticle(particle, location, count, dx, dy, dz, 0, data);
            }
        } catch (RuntimeException exception) {
            warnOnce(definition, particle, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    /** The extra data a particle requires, {@code null} when it needs none. */
    private Object data(Particle particle, CosmeticDefinition definition) {
        Class<?> type = particle.getDataType();
        if (type == Void.class) {
            return null;
        }
        if (type == Particle.DustOptions.class) {
            return new Particle.DustOptions(color(definition), 1.0f);
        }
        if (type == Color.class) {
            return color(definition);
        }
        if (type == Float.class) {
            return (float) number(definition, 1.0);
        }
        if (type == Double.class) {
            return number(definition, 1.0);
        }
        if (type == Integer.class) {
            return (int) number(definition, 0);
        }
        return UNSUPPORTED;
    }

    private static Color color(CosmeticDefinition definition) {
        String hex = value(definition.renderData(), "color", "FFFFFF");
        try {
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (NumberFormatException exception) {
            return Color.WHITE;
        }
    }

    private static double number(CosmeticDefinition definition, double fallback) {
        try {
            return Double.parseDouble(value(definition.renderData(), "value", Double.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** One line per cosmetic, not one per tick. */
    private void warnOnce(CosmeticDefinition definition, Particle particle, String reason) {
        if (warned.add(definition.id())) {
            logger.warning("Cosmetic " + definition.id() + " uses particle " + particle.name() + " which " + reason
                    + " – it is rendered as END_ROD, further failures are silent.");
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
