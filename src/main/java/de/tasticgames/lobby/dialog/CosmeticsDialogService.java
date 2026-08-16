package de.tasticgames.lobby.dialog;

import de.tasticgames.lobby.cosmetic.CosmeticCategory;
import de.tasticgames.lobby.cosmetic.CosmeticDefinition;
import de.tasticgames.lobby.cosmetic.CosmeticService;
import de.tasticgames.lobby.locale.LobbyMessages;
import de.tasticgames.lobby.sound.LobbySounds;
import de.tasticgames.lobby.telemetry.LobbyTelemetryService;
import de.tasticgames.lobby.util.LobbyThrowables;
import de.tasticgames.lobby.util.MainThread;
import de.tasticgames.localization.SupportedLanguage;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Cosmetics UI: categories → owned/locked list → equip/unequip, unlock requirement shown for locked items.
 */
public final class CosmeticsDialogService {

    private final CosmeticService cosmetics;
    private final LobbyMessages messages;
    private final DialogSupport dialogs;
    private final MainThread mainThread;
    private final LobbySounds sounds;
    private final LobbyTelemetryService telemetry;
    private final Logger logger;

    public CosmeticsDialogService(CosmeticService cosmetics, LobbyMessages messages, DialogSupport dialogs, MainThread mainThread,
                                  LobbySounds sounds, LobbyTelemetryService telemetry, Logger logger) {
        this.cosmetics = Objects.requireNonNull(cosmetics);
        this.messages = Objects.requireNonNull(messages);
        this.dialogs = Objects.requireNonNull(dialogs);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.sounds = Objects.requireNonNull(sounds);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.logger = Objects.requireNonNull(logger);
    }

    public void openMain(Player player) {
        telemetry.event("lobby.cosmetics_open", player.getUniqueId(), Map.of());
        SupportedLanguage lang = messages.languageOf(player);
        if (!cosmetics.available()) {
            dialogs.show(player, dialogs.notice(messages.get(lang, "lobby.cosmetics.title", Map.of()),
                    List.of(messages.get(lang, "lobby.cosmetics.unavailable", Map.of())), dialogs.close(messages.get(lang, "common.close", Map.of()))));
            return;
        }
        cosmetics.load(player.getUniqueId()).whenComplete((owned, throwable) -> mainThread.run(() -> {
            if (!player.isOnline()) return;
            if (throwable != null) {
                messages.send(player, "lobby.cosmetics.unavailable");
                return;
            }
            List<ActionButton> buttons = new ArrayList<>();
            for (CosmeticCategory category : CosmeticCategory.values()) {
                List<CosmeticDefinition> definitions = cosmetics.catalog().byCategory(category);
                if (definitions.isEmpty()) continue;
                long ownedCount = definitions.stream().filter(owned::owns).count();
                Component label = messages.get(lang, "lobby.cosmetics.category." + category.name().toLowerCase(java.util.Locale.ROOT), Map.of())
                        .append(Component.text(" " + ownedCount + "/" + definitions.size(), NamedTextColor.GRAY));
                buttons.add(dialogs.button(player, label, null, DialogSupport.BUTTON_WIDTH, p -> openCategory(p, category)));
            }
            dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.cosmetics.title", Map.of()),
                    List.of(messages.get(lang, "lobby.cosmetics.description", Map.of())), buttons,
                    dialogs.close(messages.get(lang, "common.close", Map.of())), 2, true));
        }));
    }

    public void openCategory(Player player, CosmeticCategory category) {
        SupportedLanguage lang = messages.languageOf(player);
        var owned = cosmetics.cached(player.getUniqueId()).orElse(null);
        if (owned == null) {
            openMain(player);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        String equippedId = owned.equipped().get(category);
        for (CosmeticDefinition definition : cosmetics.catalog().byCategory(category)) {
            boolean has = owned.owns(definition);
            boolean equipped = definition.id().equals(equippedId);
            Component name = messages.get(lang, definition.nameKey(), Map.of()).color(definition.rarity().color());
            Component label = equipped ? messages.get(lang, "lobby.cosmetics.equipped_label", Map.of("name", name))
                    : has ? name : messages.get(lang, "lobby.cosmetics.locked_label", Map.of("name", name));
            Component tooltip = has
                    ? messages.get(lang, definition.descriptionKey(), Map.of())
                    : messages.get(lang, "lobby.cosmetics.unlock_hint", Map.of("requirement",
                    definition.unlockRequirementKey().isBlank() ? messages.raw(lang, "lobby.cosmetics.unlock." + definition.unlockSource().toLowerCase(java.util.Locale.ROOT))
                            : messages.raw(lang, definition.unlockRequirementKey())));
            buttons.add(dialogs.button(player, label, tooltip, DialogSupport.WIDE_BUTTON_WIDTH, p -> {
                if (!has) {
                    sounds.error(p);
                    messages.send(p, "lobby.cosmetics.locked");
                    openCategory(p, category);
                    return;
                }
                var future = equipped ? cosmetics.unequip(p, category) : cosmetics.equip(p, definition);
                future.whenComplete((response, t) -> mainThread.run(() -> {
                    if (!p.isOnline()) return;
                    if (t != null) {
                        logger.warning("Cosmetic change failed for " + p.getName() + ": " + LobbyThrowables.rootMessage(t));
                        messages.send(p, "lobby.cosmetics.unavailable");
                        sounds.error(p);
                    } else {
                        sounds.success(p);
                        messages.send(p, equipped ? "lobby.cosmetics.unequipped" : "lobby.cosmetics.equipped", Map.of("name", name));
                    }
                    openCategory(p, category);
                }));
            }));
        }
        buttons.add(dialogs.button(player, messages.get(lang, "common.back", Map.of()), null, DialogSupport.WIDE_BUTTON_WIDTH, this::openMain));
        dialogs.show(player, dialogs.menu(messages.get(lang, "lobby.cosmetics.category." + category.name().toLowerCase(java.util.Locale.ROOT), Map.of()),
                List.of(), buttons, dialogs.close(messages.get(lang, "common.close", Map.of())), 1, true));
    }
}
