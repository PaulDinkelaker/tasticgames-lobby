package de.tasticgames.lobby.pass;

import de.tasticgames.lobby.locale.LobbyMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * /pass [quests|rewards|leaderboard|premium] (aliases /battlepass, /bp) – opens the TasticPass UI.
 */
public final class PassCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("rewards", "quests", "premium", "leaderboard");

    private final PassDialogService dialogs;
    private final LobbyMessages messages;

    public PassCommand(PassDialogService dialogs, LobbyMessages messages) {
        this.dialogs = Objects.requireNonNull(dialogs);
        this.messages = Objects.requireNonNull(messages);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "common.players-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "rewards", "tiers", "track" -> dialogs.openRewards(player);
            case "quests", "quest", "missions" -> dialogs.openQuests(player);
            case "premium", "shop", "store" -> dialogs.openPremium(player);
            case "leaderboard", "top" -> dialogs.openLeaderboard(player);
            default -> dialogs.openOverview(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(name -> name.startsWith(prefix)).toList();
    }
}
