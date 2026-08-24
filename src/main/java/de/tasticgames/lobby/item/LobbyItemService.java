package de.tasticgames.lobby.item;

import de.tasticgames.api.TasticCoreApi;
import de.tasticgames.lobby.config.LobbyConfiguration;
import de.tasticgames.lobby.config.LobbyConfigurationService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.player.LobbyPlayer;
import de.tasticgames.lobby.settings.LobbySettings;
import de.tasticgames.lobby.visibility.VisibilityMode;
import de.tasticgames.localization.SupportedLanguage;
import de.tasticgames.service.Service;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Typed lobby items identified via PersistentDataContainer (never by name/lore). Items may come
 * from ItemsAdder ({@code itemsadder: namespace:id} in items.yml) with a vanilla fallback. The profile
 * item is the player's own head: with the exported resource pack content it uses the front-facing
 * item model ({@link #setProfileHeadModel}) that fills the hotbar slot like a 2D icon.
 */
public final class LobbyItemService implements Service {

    private final NamespacedKey itemKey;
    private final LobbyConfigurationService configurationService;
    private final LobbyMessages messages;
    private final TasticCoreApi coreApi;
    private final Function<Player, VisibilityMode> visibility;
    private final de.tasticgames.lobby.integration.item.CustomItemProvider customItems;
    private volatile Supplier<Optional<String>> profileHeadModel = Optional::empty;

    public LobbyItemService(
            Plugin plugin,
            LobbyConfigurationService configurationService,
            LobbyMessages messages,
            TasticCoreApi coreApi,
            Function<Player, VisibilityMode> visibility,
            de.tasticgames.lobby.integration.item.CustomItemProvider customItems
    ) {
        this.itemKey = new NamespacedKey(plugin, "lobby-item");
        this.configurationService = Objects.requireNonNull(configurationService);
        this.messages = Objects.requireNonNull(messages);
        this.coreApi = Objects.requireNonNull(coreApi);
        this.visibility = Objects.requireNonNull(visibility);
        this.customItems = Objects.requireNonNull(customItems);
    }

    @Override
    public String id() {
        return "lobby-item-service";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    public NamespacedKey key() {
        return itemKey;
    }

    /** Item model applied to the player-head profile item once the resource pack ships it (empty = vanilla head). */
    public void setProfileHeadModel(Supplier<Optional<String>> profileHeadModel) {
        this.profileHeadModel = Objects.requireNonNull(profileHeadModel);
    }

    public Optional<LobbyItemType> typeOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }

        String value = stack.getItemMeta()
                .getPersistentDataContainer()
                .get(itemKey, PersistentDataType.STRING);

        return LobbyItemType.find(value);
    }

    public boolean isLobbyItem(ItemStack stack) {
        return typeOf(stack).isPresent();
    }

    /** Gives the lobby hotbar (idempotent; the same hotbar is used inside the cookie open world). */
    public void giveItems(Player player, LobbyPlayer lobbyPlayer) {
        if (lobbyPlayer.buildMode()) {
            return;
        }

        boolean enabled = coreApi.playerManager()
                .find(player.getUniqueId())
                .map(p -> p.settings().get(LobbySettings.ITEMS_ENABLED))
                .orElse(true);

        var inventory = player.getInventory();

        // remove previous lobby items first (idempotent)
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isLobbyItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }

        if (isLobbyItem(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
        }

        if (!enabled) {
            return;
        }

        SupportedLanguage language = messages.languageOf(player);

        for (LobbyConfiguration.ItemSlot slot
                : configurationService.configuration().items().slots().values()) {

            LobbyItemType type = LobbyItemType.find(slot.id()).orElse(null);

            if (type == null || !slot.enabled()) {
                continue;
            }

            inventory.setItem(
                    slot.slot(),
                    build(type, slot, player, language)
            );
        }

        inventory.setHeldItemSlot(0);
    }

    public void refresh(Player player, LobbyPlayer lobbyPlayer) {
        giveItems(player, lobbyPlayer);
    }

    public void clearItems(Player player) {
        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isLobbyItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
    }

    private ItemStack build(
            LobbyItemType type,
            LobbyConfiguration.ItemSlot slot,
            Player player,
            SupportedLanguage language
    ) {
        Material material = slot.material();

        if (type == LobbyItemType.VISIBILITY) {
            material = visibility.apply(player).material();
        }

        ItemStack stack = null;

        if (!slot.customItemId().isBlank()
                && type != LobbyItemType.VISIBILITY) {
            stack = customItems.item(slot.customItemId()).orElse(null);
        }

        if (stack == null) {
            stack = new ItemStack(material);
        }

        ItemMeta meta = stack.getItemMeta();

        String nameKey =
                "lobby.item."
                        + type.key().replace('_', '-')
                        + ".name";

        String loreKey =
                "lobby.item."
                        + type.key().replace('_', '-')
                        + ".lore";

        Map<String, Object> placeholders =
                type == LobbyItemType.VISIBILITY
                        ? Map.of(
                        "mode",
                        messages.get(
                                language,
                                "lobby.visibility."
                                        + visibility.apply(player)
                                        .name()
                                        .toLowerCase(java.util.Locale.ROOT),
                                Map.of()
                        )
                )
                        : Map.of();

        meta.displayName(
                messages.get(language, nameKey, placeholders)
                        .decoration(TextDecoration.ITALIC, false)
        );

        if (messages.contains(loreKey)) {
            meta.lore(
                    List.of(
                            messages.get(language, loreKey, placeholders)
                                    .decoration(TextDecoration.ITALIC, false)
                    )
            );
        }

        meta.getPersistentDataContainer()
                .set(
                        itemKey,
                        PersistentDataType.STRING,
                        type.name()
                );

        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS
        );

        if (!slot.assetId().isBlank()) {
            try {
                meta.setItemModel(
                        NamespacedKey.fromString(slot.assetId())
                );
            } catch (Exception ignored) {
                // invalid asset id: vanilla fallback
            }
        }

        if (meta instanceof SkullMeta skull
                && type == LobbyItemType.PROFILE) {

            // the player's own skin; the exported front-facing model turns the 3D head into a slot-filling 2D face
            skull.setPlayerProfile(player.getPlayerProfile());

            if (slot.assetId().isBlank()) {
                profileHeadModel.get().ifPresent(model -> {
                    NamespacedKey key =
                            NamespacedKey.fromString(model);

                    if (key != null) {
                        skull.setItemModel(key);
                    }
                });
            }
        }

        stack.setItemMeta(meta);

        return stack;
    }
}
