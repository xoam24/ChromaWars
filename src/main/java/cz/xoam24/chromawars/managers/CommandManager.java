package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.TeamConfig;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Obsluha příkazů /elo a /chroma.
 * Registruje se v ChromaWars.onEnable() přes:
 *   getCommand("elo").setExecutor(commandManager);
 *   getCommand("chroma").setExecutor(commandManager);
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private final ChromaWars plugin;
    private final MessageManager msg;

    public CommandManager(ChromaWars plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageManager();

        // Registrace executorů
        PluginCommand elo    = plugin.getCommand("elo");
        PluginCommand chroma = plugin.getCommand("chroma");
        PluginCommand join   = plugin.getCommand("join");
        PluginCommand vote   = plugin.getCommand("votemap");

        if (elo    != null) { elo.setExecutor(this);    elo.setTabCompleter(this); }
        if (chroma != null) { chroma.setExecutor(this); chroma.setTabCompleter(this); }
        if (join   != null) { join.setExecutor(this); }
        if (vote   != null) { vote.setExecutor(this); }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "elo"      -> handleElo(sender, args);
            case "chroma"   -> handleChroma(sender, args);
            case "join"     -> handleJoin(sender);
            case "votemap"  -> handleVotemap(sender);
            default         -> false;
        };
    }

    // ── /elo <add|set|remove|reset> <player> [value] ─────────────────────────

    private boolean handleElo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chromawars.admin")) {
            msg.send(sender, "admin.no-permission");
            return true;
        }
        if (args.length < 2) {
            msg.send(sender, "admin.usage-elo");
            return true;
        }

        String sub        = args[0].toLowerCase();
        String targetName = args[1];

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            msg.send(sender, "admin.player-not-found", "player", targetName);
            return true;
        }

        EloManager elo = plugin.getEloManager();

        switch (sub) {
            case "add" -> {
                int amount = parseAmount(sender, args, 2);
                if (amount < 0) return true;
                elo.addElo(target, amount);
                msg.send(sender, "admin.elo-add",
                        "player", targetName,
                        "amount", String.valueOf(amount),
                        "value",  "...");  // přesná hodnota není synchronně dostupná
            }
            case "set" -> {
                int value = parseAmount(sender, args, 2);
                if (value < 0) return true;
                elo.setElo(target, value);
                msg.send(sender, "admin.elo-set",
                        "player", targetName,
                        "value",  String.valueOf(value));
            }
            case "remove" -> {
                int amount = parseAmount(sender, args, 2);
                if (amount < 0) return true;
                elo.removeElo(target, amount);
                msg.send(sender, "admin.elo-remove",
                        "player", targetName,
                        "amount", String.valueOf(amount),
                        "value",  "...");
            }
            case "reset" -> {
                elo.resetElo(target);
                msg.send(sender, "admin.elo-reset",
                        "player", targetName,
                        "value",  String.valueOf(plugin.getConfigManager().getDefaultElo()));
            }
            default -> msg.send(sender, "admin.usage-elo");
        }
        return true;
    }

    // ── /chroma <setlobby|wand|arena|reload> ─────────────────────────────────

    private boolean handleChroma(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chromawars.admin")) {
            msg.send(sender, "admin.no-permission");
            return true;
        }
        if (args.length == 0) {
            msg.send(sender, "admin.usage-chroma");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {

            // /chroma setlobby
            case "setlobby" -> {
                if (!(sender instanceof Player p)) {
                    msg.send(sender, "misc.only-players");
                    return true;
                }
                plugin.getConfigManager().saveLobbyLocation(
                        p.getWorld().getName(),
                        p.getLocation().getX(),
                        p.getLocation().getY(),
                        p.getLocation().getZ(),
                        p.getLocation().getYaw(),
                        p.getLocation().getPitch()
                );
                msg.send(sender, "admin.lobby-set");
            }

            // /chroma wand
            case "wand" -> {
                if (!(sender instanceof Player p)) {
                    msg.send(sender, "misc.only-players");
                    return true;
                }
                plugin.getWandManager().giveWand(p);
                msg.send(sender, "admin.wand-given");
            }

            // /chroma arena <create|edit|save|delete|list>
            case "arena" -> handleArenaSubCommand(sender, args);

            // /chroma reload
            case "reload" -> {
                plugin.getConfigManager().loadAll();
                plugin.getMessageManager().load();
                msg.send(sender, "admin.reload-done");
            }

            default -> msg.send(sender, "admin.usage-chroma");
        }
        return true;
    }

    // ── /chroma arena ... ─────────────────────────────────────────────────────

    private void handleArenaSubCommand(CommandSender sender, String[] args) {
        // args[0] = "arena", args[1] = subpříkaz, args[2] = název arény
        if (args.length < 2) {
            sender.sendMessage("Použití: /chroma arena <create|edit|save|delete|list> [název]");
            return;
        }

        String arenaCmd = args[1].toLowerCase();
        ArenaManager arenas = plugin.getArenaManager();

        switch (arenaCmd) {

            // /chroma arena create <name> <world>
            case "create" -> {
                if (args.length < 3) {
                    sender.sendMessage("Použití: /chroma arena create <název>");
                    return;
                }
                String name = args[2];
                if (plugin.getConfigManager().getArenaNames().contains(name)) {
                    msg.send(sender, "admin.arena-exists", "arena", name);
                    return;
                }
                String world = (sender instanceof Player p)
                        ? p.getWorld().getName()
                        : (args.length > 3 ? args[3] : "world");
                plugin.getConfigManager().saveArena(name, world, 0,0,0,0,0,0);
                msg.send(sender, "admin.arena-created", "arena", name);
            }

            // /chroma arena edit <name>
            case "edit" -> {
                if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return; }
                if (args.length < 3) { sender.sendMessage("Použití: /chroma arena edit <název>"); return; }
                String name = args[2];
                if (!plugin.getConfigManager().getArenaNames().contains(name)) {
                    msg.send(sender, "admin.arena-not-found", "arena", name);
                    return;
                }
                plugin.getWandManager().startEditSession(p, name);
                plugin.getWandManager().giveWand(p);
                msg.send(sender, "admin.edit-session-start", "arena", name);
            }

            // /chroma arena save
            case "save" -> {
                if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return; }
                WandManager wand = plugin.getWandManager();
                if (!wand.isInEditSession(p)) {
                    msg.send(sender, "admin.not-in-edit-session");
                    return;
                }
                if (!wand.hasPos1(p) || !wand.hasPos2(p)) {
                    sender.sendMessage("§cNastav nejprve Pos1 i Pos2 pomocí Wandu.");
                    return;
                }
                String arenaName = wand.getEditArena(p);
                int[] p1 = wand.getPos1(p);
                int[] p2 = wand.getPos2(p);
                String world = p.getWorld().getName();

                plugin.getConfigManager().saveArena(arenaName, world,
                        p1[0], p1[1], p1[2],
                        p2[0], p2[1], p2[2]);

                int dx = Math.abs(p2[0] - p1[0]) + 1;
                int dy = Math.abs(p2[1] - p1[1]) + 1;
                int dz = Math.abs(p2[2] - p1[2]) + 1;
                int blocks = dx * dy * dz;

                wand.endEditSession(p);
                msg.send(sender, "admin.arena-saved",
                        "arena",  arenaName,
                        "blocks", String.valueOf(blocks));
            }

            // /chroma arena delete <name>
            case "delete" -> {
                if (args.length < 3) { sender.sendMessage("Použití: /chroma arena delete <název>"); return; }
                String name = args[2];
                if (!plugin.getConfigManager().getArenaNames().contains(name)) {
                    msg.send(sender, "admin.arena-not-found", "arena", name);
                    return;
                }
                plugin.getConfigManager().deleteArena(name);
                msg.send(sender, "admin.arena-deleted", "arena", name);
            }

            // /chroma arena list
            case "list" -> {
                Set<String> names = plugin.getConfigManager().getArenaNames();
                if (names.isEmpty()) {
                    sender.sendMessage("§eŽádné arény nejsou definovány.");
                } else {
                    sender.sendMessage("§aArény (" + names.size() + "): §f" + String.join(", ", names));
                }
            }

            default -> sender.sendMessage("Neznámý podpříkaz arény.");
        }
    }

    // ── /join ─────────────────────────────────────────────────────────────────

    private boolean handleJoin(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            msg.send(sender, "misc.only-players");
            return true;
        }
        plugin.getMenuManager().openJoinMenu(p);
        return true;
    }

    // ── /votemap ──────────────────────────────────────────────────────────────

    private boolean handleVotemap(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            msg.send(sender, "misc.only-players");
            return true;
        }
        plugin.getMenuManager().openVoteMenu(p);
        return true;
    }

    // ── Tab doplňování ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("chromawars.admin")) return Collections.emptyList();

        return switch (command.getName().toLowerCase()) {
            case "elo"    -> tabElo(args);
            case "chroma" -> tabChroma(args);
            default       -> Collections.emptyList();
        };
    }

    private List<String> tabElo(String[] args) {
        if (args.length == 1) return filter(List.of("add", "set", "remove", "reset"), args[0]);
        if (args.length == 2) return null; // null = Bukkit doplní online hráče
        if (args.length == 3) return filter(List.of("10", "25", "50", "100", "1000"), args[2]);
        return Collections.emptyList();
    }

    private List<String> tabChroma(String[] args) {
        if (args.length == 1) return filter(List.of("setlobby", "wand", "arena", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("arena"))
            return filter(List.of("create", "edit", "save", "delete", "list"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("arena")
                && (args[1].equalsIgnoreCase("edit")
                || args[1].equalsIgnoreCase("delete")))
            return filter(new ArrayList<>(plugin.getConfigManager().getArenaNames()), args[2]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .toList();
    }

    // ── Pomocné metody ────────────────────────────────────────────────────────

    private int parseAmount(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            msg.send(sender, "admin.invalid-value");
            return -1;
        }
        try {
            int v = Integer.parseInt(args[index]);
            if (v < 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            msg.send(sender, "admin.invalid-value");
            return -1;
        }
    }
}