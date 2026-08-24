package de.tasticgames.lobby.dialog;

import de.tasticgames.lobby.util.MainThread;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared Paper Dialog helpers: every callback validates the clicking player (uuid, online) and
 * runs on the main thread. Keeps the domain dialog services small.
 */
public final class DialogSupport {

    public static final int BUTTON_WIDTH = 200;
    /** Characters of a progress bar; the block glyphs exist in the vanilla font in every language. */
    private static final int BAR_WIDTH = 24;
    public static final int WIDE_BUTTON_WIDTH = 300;

    private final MainThread mainThread;

    public DialogSupport(MainThread mainThread) {
        this.mainThread = Objects.requireNonNull(mainThread);
    }

    /**
     * Progress bar for dialog bodies: {@code ▉▉▉▉░░░░░░ 42%}. Dialogs have no widgets of their own, so a bar
     * built from block glyphs is what makes a "how far am I" line readable at a glance.
     */
    public static Component bar(double fraction, TextColor filledColor) {
        double clamped = Math.max(0, Math.min(1, fraction));
        int filled = (int) Math.round(clamped * BAR_WIDTH);
        return Component.text("\u2503", NamedTextColor.DARK_GRAY)
                .append(Component.text("\u2589".repeat(filled), filledColor))
                .append(Component.text("\u2591".repeat(BAR_WIDTH - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text("\u2503 ", NamedTextColor.DARK_GRAY))
                .append(Component.text(Math.round(clamped * 100) + "%", NamedTextColor.GRAY));
    }

    public static Component bar(double fraction) {
        return bar(fraction, NamedTextColor.GREEN);
    }

    public Dialog menu(Component title, List<Component> body, List<ActionButton> buttons, ActionButton exit, int columns, boolean escapable) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component component : body) {
            bodies.add(DialogBody.plainMessage(component));
        }
        if (buttons.isEmpty()) {
            // a multi-action dialog without a single action is rejected ("actions cannot be empty"); a menu
            // whose buttons all fell away (nothing claimable, nothing owned) is simply a notice
            return notice(title, body, exit);
        }
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(bodies).canCloseWithEscape(escapable).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(DialogType.multiAction(buttons).exitAction(exit).columns(Math.max(1, columns)).build()));
    }

    public Dialog notice(Component title, List<Component> body, ActionButton ok) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component component : body) {
            bodies.add(DialogBody.plainMessage(component));
        }
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(bodies).canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(DialogType.notice(ok)));
    }

    public Dialog confirm(Component title, List<Component> body, ActionButton yes, ActionButton no) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component component : body) {
            bodies.add(DialogBody.plainMessage(component));
        }
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(bodies).canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(DialogType.confirmation(yes, no)));
    }

    public Dialog form(Component title, List<Component> body, List<DialogInput> inputs, ActionButton submit, ActionButton cancel) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component component : body) {
            bodies.add(DialogBody.plainMessage(component));
        }
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(bodies).inputs(inputs).canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(DialogType.confirmation(submit, cancel)));
    }

    private volatile BiConsumer<Player, Throwable> errorHandler = (p, t) -> { };

    /** Handler invoked (main thread) when a dialog action throws; the player must get feedback. */
    public void setErrorHandler(BiConsumer<Player, Throwable> handler) {
        this.errorHandler = java.util.Objects.requireNonNull(handler);
    }

    private void guarded(Player player, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            errorHandler.accept(player, e);
        }
    }

    /** Button executing {@code onClick} for the expected player only. */
    public ActionButton button(Player expected, Component label, Component tooltip, int width, Consumer<Player> onClick) {
        UUID uuid = expected.getUniqueId();
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player clicked && clicked.getUniqueId().equals(uuid) && clicked.isOnline()) {
                mainThread.run(() -> guarded(clicked, () -> onClick.accept(clicked)));
            }
        };
        ActionButton.Builder builder = ActionButton.builder(label).width(width).action(DialogAction.customClick(callback, options()));
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return builder.build();
    }

    public ActionButton button(Player expected, Component label, Consumer<Player> onClick) {
        return button(expected, label, null, BUTTON_WIDTH, onClick);
    }

    /** Button receiving the form inputs. */
    public ActionButton formButton(Player expected, Component label, int width, BiConsumer<Player, io.papermc.paper.dialog.DialogResponseView> onSubmit) {
        UUID uuid = expected.getUniqueId();
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player clicked && clicked.getUniqueId().equals(uuid) && clicked.isOnline()) {
                mainThread.run(() -> guarded(clicked, () -> onSubmit.accept(clicked, view)));
            }
        };
        return ActionButton.builder(label).width(width).action(DialogAction.customClick(callback, options())).build();
    }

    /**
     * Button that closes the dialog. Dialogs use after_action NONE (no flicker between menus), so the
     * close is done explicitly by the callback instead of relying on the client's default close.
     */
    public ActionButton close(Component label) {
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player clicked && clicked.isOnline()) {
                mainThread.run(clicked::closeDialog);
            }
        };
        return ActionButton.builder(label).width(BUTTON_WIDTH).action(DialogAction.customClick(callback, options())).build();
    }

    public ActionButton command(Component label, String command) {
        return ActionButton.builder(label).width(BUTTON_WIDTH)
                .action(DialogAction.staticAction(ClickEvent.runCommand(command))).build();
    }

    private static ClickCallback.Options options() {
        return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build();
    }

    public void show(Player player, Dialog dialog) {
        mainThread.run(() -> {
            if (player.isOnline()) {
                player.showDialog(dialog);
            }
        });
    }
}
