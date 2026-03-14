package cz.xoam24.chromawars;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scheduler.BukkitRunnable;

public class ScoreboardManager {

    private final ChromaWars plugin;

    public ScoreboardManager(ChromaWars plugin) {
        this.plugin = plugin;
        startUpdateTask();
    }

    private void startUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    PlayerSession session = PlayerSession.getSession(p);
                    if (session == null) continue;

                    if (session.getState() == PlayerSession.State.LOBBY) {
                        updateLobbyScoreboard(p, session);
                    } else {
                        updateInGameScoreboard(p, session);
                    }
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 20L); // Každou vteřinu
    }

    private void updateLobbyScoreboard(Player player, PlayerSession session) {
        Scoreboard board = getOrCreateScoreboard(player);
        Objective obj = getOrCreateObjective(board, "Lobby", "§x§F§F§0§0§0§0§lChromaWars");

        // Hack pro přepsání řádků - resetujeme skóre a nastavíme znova (pro nativní API nutnost, jinak se řádky duplikují)
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        GameManager gm = plugin.getGameManager();
        obj.getScore("§1").setScore(6);
        obj.getScore("§fTvé ELO: §a" + session.getElo()).setScore(5);
        obj.getScore("§fTvůj Tým: " + (session.getTeam() == PlayerSession.Team.NONE ? "§7Žádný" : (session.getTeam() == PlayerSession.Team.RED ? "§cČervený" : "§9Modrý"))).setScore(4);
        obj.getScore("§2").setScore(3);
        obj.getScore("§fTop 1: §e" + plugin.getEloManager().getNameAtPosition(1)).setScore(2);
        obj.getScore("§fStatus: " + (gm.isStarting() ? "§eStart za " + gm.getTimeLeft() + "s" : "§7Čekání...")).setScore(1);
        obj.getScore("§3").setScore(0);

        player.setScoreboard(board);
    }

    private void updateInGameScoreboard(Player player, PlayerSession session) {
        Scoreboard board = getOrCreateScoreboard(player);
        Objective obj = getOrCreateObjective(board, "InGame", "§x§F§F§0§0§0§0§lChromaWars");

        for (String entry : board.getEntries()) { board.resetScores(entry); }

        GameManager gm = plugin.getGameManager();
        obj.getScore("§1").setScore(4);
        obj.getScore("§fČas: §e" + gm.getTimeLeft() + "s").setScore(3);
        obj.getScore("§cČervení: §f" + String.format("%.1f", gm.getRedPercentage()) + "%").setScore(2);
        obj.getScore("§9Modří: §f" + String.format("%.1f", gm.getBluePercentage()) + "%").setScore(1);
        obj.getScore("§2").setScore(0);

        player.setScoreboard(board);
    }

    private Scoreboard getOrCreateScoreboard(Player player) {
        Scoreboard board = player.getScoreboard();
        if (board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
        }
        return board;
    }

    private Objective getOrCreateObjective(Scoreboard board, String name, String title) {
        Objective obj = board.getObjective(name);
        if (obj == null) {
            obj = board.registerNewObjective(name, "dummy", title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            obj.setDisplayName(title); // Update title
        }
        return obj;
    }
}