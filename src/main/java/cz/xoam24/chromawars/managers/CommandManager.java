package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.TeamConfig;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Obsluha příkazů.
 *
 * Příkazy:
 *  /elo add|set|remove|reset <hráč> [hodnota]
 *  /chroma setlobby
 *  /chroma arena create <název>
 *  /chroma arena edit <název>          ← automaticky dá Wand
 *  /chroma arena save
 *  /chroma arena delete <název>
 *  /chroma arena list
 *  /chroma arena setspawn <teamId>     ← NOVÝ příkaz
 *  /chroma reload
 *  /join
 *  /votemap
 *
 * ODEBRÁNO: /chroma wand (Wand se dává automaticky při /chroma arena edit)
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private final ChromaWars plugin;
    private final MessageManager msg;

    public CommandManager(ChromaWars plugin) {
        this.plugin = plugin;
        this.msg    = plugin.getMessageManager();

        registerCmd("elo",     this);
        registerCmd("chroma",  this);
        registerCmd("join",    this);
        registerCmd("votemap", this);
    }

    private void registerCmd(String name, CommandExecutor executor) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter tc) cmd.setTabCompleter(tc);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DISPATCH
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "elo"     -> handleElo(sender, args);
            case "chroma"  -> handleChroma(sender, args);
            case "join"    -> handleJoin(sender);
            case "votemap" -> handleVotemap(sender);
            default        -> false;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /elo
    // ══════════════════════════════════════════════════════════════════════

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
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            msg.send(sender, "admin.player-not-found", "player", targetName);
            return true;
        }

        EloManager elo = plugin.getEloManager();

        switch (sub) {
            case "add" -> {
                int amount = parsePositiveInt(sender, args, 2);
                if (amount < 0) return true;
                elo.addElo(target, amount);
                msg.send(sender, "admin.elo-add",
                        "player", targetName, "amount", String.valueOf(amount));
            }
            case "set" -> {
                int value = parsePositiveInt(sender, args, 2);
                if (value < 0) return true;
                elo.setElo(target, value);
                msg.send(sender, "admin.elo-set",
                        "player", targetName, "value", String.valueOf(value));
            }
            case "remove" -> {
                int amount = parsePositiveInt(sender, args, 2);
                if (amount < 0) return true;
                elo.removeElo(target, amount);
                msg.send(sender, "admin.elo-remove",
                        "player", targetName, "amount", String.valueOf(amount));
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

    // ══════════════════════════════════════════════════════════════════════
    //  /chroma
    // ══════════════════════════════════════════════════════════════════════

    private boolean handleChroma(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chromawars.admin")) {
            msg.send(sender, "admin.no-permission");
            return true;
        }
        if (args.length == 0) {
            msg.send(sender, "admin.usage-chroma");
            return true;
        }

        switch (args[0].toLowerCase()) {

            // /chroma setlobby
            case "setlobby" -> {
                if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return true; }
                plugin.getConfigManager().saveLobbyLocation(
                        p.getWorld().getName(),
                        p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ(),
                        p.getLocation().getYaw(), p.getLocation().getPitch());
                msg.send(sender, "admin.lobby-set");
            }

            // /chroma arena ...
            case "arena" -> handleArena(sender, args);

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

    // ══════════════════════════════════════════════════════════════════════
    //  /chroma arena ...
    // ══════════════════════════════════════════════════════════════════════

    private void handleArena(CommandSender sender, String[] args) {
        // args[0]="arena"  args[1]=sub-příkaz  args[2+]=parametry
        if (args.length < 2) {
            sender.sendMessage("§cPoužití: /chroma arena <create|edit|save|delete|list|setspawn>");
            return;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {

            // ── create ────────────────────────────────────────────────────
            case "create" -> {
                if (args.length < 3) { sender.sendMessage("§cPoužití: /chroma arena create <název>"); return; }
                String name = args[2];
                if (plugin.getConfigManager().getArenaNames().contains(name)) {
                    msg.send(sender, "admin.arena-exists", "arena", name);
                    return;
                }
                String world = (sender instanceof Player p)
                        ? p.getWorld().getName() : "world";
                plugin.getConfigManager().saveArena(name, world, 0,0,0, 0,0,0);
                // Načteme arény znovu
                plugin.getArenaManager().loadArenas();
                msg.send(sender, "admin.arena-created", "arena", name);
            }

            // ── edit ──────────────────────────────────────────────────────
            // Automaticky dává Wand – není třeba /chroma wand
            case "edit" -> {
                if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return; }
                if (args.length < 3) { sender.sendMessage("§cPoužití: /chroma arena edit <název>"); return; }
                String name = args[2];
                if (!plugin.getConfigManager().getArenaNames().contains(name)) {
                    msg.send(sender, "admin.arena-not-found", "arena", name);
                    return;
                }
                plugin.getWandManager().startEditSession(p, name);
                plugin.getWandManager().giveWand(p);
                msg.send(sender, "admin.edit-session-start", "arena", name);
            }

            // ── save ──────────────────────────────────────────────────────
            case "save" -> {
                if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return; }
                WandManager wand = plugin.getWandManager();
                if (!wand.isInEditSession(p)) {
                    msg.send(sender, "admin.not-in-edit-session");
                    return;
                }
                if (!wand.hasPos1(p) || !wand.hasPos2(p)) {
                    sender.sendMessage("§cNastav nejprve Pos1 a Pos2 pomocí Wandu.");
                    return;
                }
                String arenaName = wand.getEditArena(p);
                int[]  p1        = wand.getPos1(p);
                int[]  p2        = wand.getPos2(p);

                plugin.getConfigManager().saveArena(arenaName, p.getWorld().getName(),
                        p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
                plugin.getArenaManager().loadArenas();

                int dx = Math.abs(p2[0] - p1[0]) + 1;
                int dy = Math.abs(p2[1] - p1[1]) + 1;
                int dz = Math.abs(p2[2] - p1[2]) + 1;

                wand.endEditSession(p);
                msg.send(sender, "admin.arena-saved",
                        "arena",  arenaName,
                        "blocks", String.valueOf(dx * dy * dz));
            }

            // ── delete ────────────────────────────────────────────────────
            case "delete" -> {
                if (args.length < 3) { sender.sendMessage("§cPoužití: /chroma arena delete <název>"); return; }
                String name = args[2];
                if (!plugin.getConfigManager().getArenaNames().contains(name)) {
                    msg.send(sender, "admin.arena-not-found", "arena", name);
                    return;
                }
                plugin.getConfigManager().deleteArena(name);
                plugin.getArenaManager().loadArenas();
                msg.send(sender, "admin.arena-deleted", "arena", name);
            }

            // ── list ──────────────────────────────────────────────────────
            case "list" -> {
                Set<String> names = plugin.getConfigManager().getArenaNames();
                if (names.isEmpty()) {
                    sender.sendMessage("§eŽádné arény nejsou definovány.");
                } else {
                    sender.sendMessage("§aArény (" + names.size() + "): §f" + String.join(", ", names));
                }
            }

            // ── setspawn <teamId> ──────────────────────────────────────────
            // Nastaví spawn pro daný tým v aktuálně editované aréně
            // nebo v zadané aréně: /chroma arena setspawn <teamId> [arenaName]
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return; }
                if (args.length < 3) {
                    sender.sendMessage("§cPoužití: /chroma arena setspawn <teamId> [název arény]");
                    sender.sendMessage("§7Dostupné týmy: " + String.join(", ",
                            plugin.getConfigManager().getTeams().keySet()));
                    return;
                }

                String teamId = args[2].toLowerCase();

                // Validace teamId
                TeamConfig team = plugin.getConfigManager().getTeam(teamId);
                if (team == null) {
                    sender.sendMessage("§cNeznámý tým '" + teamId + "'. Dostupné: "
                            + String.join(", ", plugin.getConfigManager().getTeams().keySet()));
                    return;
                }

                // Zjistíme název arény: buď z edit session nebo z args[3]
                String arenaName;
                if (args.length >= 4) {
                    arenaName = args[3];
                } else if (plugin.getWandManager().isInEditSession(p)) {
                    arenaName = plugin.getWandManager().getEditArena(p);
                } else {
                    sender.sendMessage("§cZadej název arény: /chroma arena setspawn <teamId> <arenaName>");
                    sender.sendMessage("§7Nebo nejprve spusť /chroma arena edit <arenaName>");
                    return;
                }

                if (!plugin.getConfigManager().getArenaNames().contains(arenaName)) {
                    msg.send(sender, "admin.arena-not-found", "arena", arenaName);
                    return;
                }

                // Uložíme spawn
                plugin.getGameManager().saveTeamSpawn(arenaName, teamId, p.getLocation());

                sender.sendMessage("§a✔ Spawn pro tým §f" + team.displayName()
                        + " §av aréně §f" + arenaName + " §auložen na "
                        + "§f" + String.format("%.1f, %.1f, %.1f", p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ()));
            }

            default -> sender.sendMessage("§cNeznámý arena příkaz. Dostupné: create, edit, save, delete, list, setspawn");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /join  /votemap
    // ══════════════════════════════════════════════════════════════════════

    private boolean handleJoin(CommandSender sender) {
        if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return true; }
        plugin.getMenuManager().openJoinMenu(p);
        return true;
    }

    private boolean handleVotemap(CommandSender sender) {
        if (!(sender instanceof Player p)) { msg.send(sender, "misc.only-players"); return true; }
        plugin.getMenuManager().openVoteMenu(p);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TAB DOPLŇOVÁNÍ
    // ══════════════════════════════════════════════════════════════════════

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
        if (args.length == 2) return null; // Bukkit doplní online hráče
        if (args.length == 3) return filter(List.of("10", "25", "50", "100", "500"), args[2]);
        return Collections.emptyList();
    }

    private List<String> tabChroma(String[] args) {
        if (args.length == 1)
            return filter(List.of("setlobby", "arena", "reload"), args[0]);

        if (args.length == 2 && args[0].equalsIgnoreCase("arena"))
            return filter(List.of("create", "edit", "save", "delete", "list", "setspawn"), args[1]);

        if (args.length == 3 && args[0].equalsIgnoreCase("arena")) {
            String sub = args[1].toLowerCase();
            if (sub.equals("edit") || sub.equals("delete"))
                return filter(new ArrayList<>(plugin.getConfigManager().getArenaNames()), args[2]);
            if (sub.equals("setspawn"))
                return filter(new ArrayList<>(plugin.getConfigManager().getTeams().keySet()), args[2]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("arena")
                && args[1].equalsIgnoreCase("setspawn")) {
            return filter(new ArrayList<>(plugin.getConfigManager().getArenaNames()), args[3]);
        }

        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .toList();
    }

    private int parsePositiveInt(CommandSender sender, String[] args, int index) {
        if (args.length <= index) { msg.send(sender, "admin.invalid-value"); return -1; }
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