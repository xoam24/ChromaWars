package cz.xoam24.chromawars;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandManager implements CommandExecutor {

    private final ChromaWars plugin;
    private final WandManager wandManager;

    public CommandManager(ChromaWars plugin, WandManager wandManager) {
        this.plugin = plugin;
        this.wandManager = wandManager;
        plugin.getCommand("chroma").setExecutor(this);
        plugin.getCommand("elo").setExecutor(this);
        // Příkazy jako /join a /votemap by se daly přidat sem nebo odchytávat přes PlayerCommandPreprocessEvent
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Prikazy lze pouzivat jen ve hre.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("chroma")) {
            if (!player.hasPermission("chromawars.admin")) {
                player.sendMessage(plugin.getMessageManager().getMessage("error.no-permission"));
                return true;
            }

            if (args.length == 0) {
                player.sendMessage("§cPoužití: /chroma <setlobby|wand|arena>");
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "setlobby":
                    plugin.getConfigManager().getConfig().set("lobby.location", player.getLocation().getWorld().getName() + "," + player.getLocation().getX() + "," + player.getLocation().getY() + "," + player.getLocation().getZ());
                    plugin.saveConfig();
                    player.sendMessage("§aLobby nastaveno.");
                    break;
                case "wand":
                    wandManager.giveWand(player);
                    break;
                case "arena":
                    handleArenaCommand(player, args);
                    break;
            }
        } else if (command.getName().equalsIgnoreCase("elo")) {
            // Placeholder pro ELO příkazy (/elo add/set <player> <amount>)
            // Doplní se provázání s EloManagerem
            player.sendMessage("§eELO systém - příkazy v přípravě (krok 3 propojení).");
        }

        return true;
    }

    private void handleArenaCommand(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cPoužití: /chroma arena <create|edit|save|delete>");
            return;
        }
        String action = args[1].toLowerCase();

        if (action.equals("create") && args.length == 3) {
            plugin.getArenaManager().createArena(args[2], player.getWorld().getName());
            player.sendMessage("§aAréna " + args[2] + " vytvořena.");
        } else if (action.equals("edit") && args.length == 4 && args[3].equalsIgnoreCase("position")) {
            wandManager.startEditSession(player, args[2]);
        } else if (action.equals("save")) {
            WandManager.EditSession session = wandManager.getSession(player);
            if (session != null && session.getPos1() != null && session.getPos2() != null) {
                plugin.getArenaManager().saveArenaPositions(session.getArenaName(), session.getPos1(), session.getPos2());
                wandManager.clearSession(player);
                player.sendMessage("§aPozice arény " + session.getArenaName() + " uloženy.");
            } else {
                player.sendMessage("§cChyba: Musíš vybrat obě pozice Wandem.");
            }
        } else if (action.equals("delete") && args.length == 3) {
            plugin.getArenaManager().deleteArena(args[2]);
            player.sendMessage("§aAréna smazána.");
        }
    }
}