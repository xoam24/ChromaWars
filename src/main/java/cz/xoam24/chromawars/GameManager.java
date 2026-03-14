package cz.xoam24.chromawars;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class GameManager {

    private final ChromaWars plugin;
    private GameState state = GameState.WAITING;
    private int timeLeft = 60; // 60s do startu nebo 300s délka hry
    private int redBlocks = 0;
    private int blueBlocks = 0;
    private int totalArenaBlocks = 1000; // V reálu se vypočítá z pos1 a pos2

    public enum GameState { WAITING, STARTING, IN_GAME, ENDING }

    public GameManager(ChromaWars plugin) {
        this.plugin = plugin;
        startTickTask();
    }

    private void startTickTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (state == GameState.STARTING) {
                    timeLeft--;
                    if (timeLeft <= 0) startGame();
                    else if (timeLeft % 10 == 0 || timeLeft <= 5) {
                        Bukkit.broadcastMessage(plugin.getMessageManager().colorize("&eHra začíná za " + timeLeft + " sekund!"));
                    }
                } else if (state == GameState.IN_GAME) {
                    timeLeft--;
                    checkWinCondition();
                    if (timeLeft <= 0) endGame(null);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void tryStartCountdown() {
        // Kontrola minima hráčů
        if (state == GameState.WAITING && Bukkit.getOnlinePlayers().size() >= 2) {
            state = GameState.STARTING;
            timeLeft = 30; // 30s do startu
            Bukkit.broadcastMessage(plugin.getMessageManager().colorize("&aDostatek hráčů! Odpočet spuštěn."));
        }
    }

    private void startGame() {
        state = GameState.IN_GAME;
        timeLeft = 300; // 5 minut hra
        redBlocks = 0;
        blueBlocks = 0;

        String arenaName = plugin.getArenaManager().getWinningArena();
        if (arenaName == null) arenaName = plugin.getArenaManager().getArenas().iterator().next();

        // Zjednodušený port hráčů - v praxi by se tahaly spawn pointy z configu
        Location spawnLoc = Bukkit.getWorlds().get(0).getSpawnLocation();

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerSession session = PlayerSession.getSession(p);
            if (session == null || session.getTeam() == PlayerSession.Team.NONE) continue;

            session.setState(PlayerSession.State.PLAYING);
            p.teleport(spawnLoc);
            p.getInventory().clear();
            session.giveTeamArmor(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
            p.sendTitle("§cBOJ!", "Obarvi co nejvíce bloků!", 10, 40, 10);
        }
    }

    private void checkWinCondition() {
        if (getRedPercentage() >= 90.0) endGame(PlayerSession.Team.RED);
        else if (getBluePercentage() >= 90.0) endGame(PlayerSession.Team.BLUE);
    }

    private void endGame(PlayerSession.Team winner) {
        state = GameState.ENDING;

        if (winner == null) {
            winner = (redBlocks > blueBlocks) ? PlayerSession.Team.RED : PlayerSession.Team.BLUE;
            if (redBlocks == blueBlocks) winner = PlayerSession.Team.NONE; // Remíza
        }

        String winnerName = (winner == PlayerSession.Team.RED) ? "§cČervený tým" : (winner == PlayerSession.Team.BLUE ? "§9Modrý tým" : "§7Nikdo (Remíza)");
        Bukkit.broadcastMessage(plugin.getMessageManager().colorize("&8[&eChromaWars&8] &fHra skončila! Vítěz: " + winnerName));

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerSession session = PlayerSession.getSession(p);
            if (session == null || session.getState() != PlayerSession.State.PLAYING) continue;

            if (session.getTeam() == winner) {
                session.setElo(session.getElo() + 25);
                p.sendMessage("§a+25 ELO (Výhra)");
            } else if (winner != PlayerSession.Team.NONE) {
                session.setElo(Math.max(0, session.getElo() - 15));
                p.sendMessage("§c-15 ELO (Prohra)");
            }
            // Uložíme nové ELO asynchronně do databáze
            plugin.getEloManager().saveEloAsync(p.getUniqueId(), p.getName(), session.getElo());

            // Reset hráče do Lobby
            session.setState(PlayerSession.State.LOBBY);
            session.setTeam(PlayerSession.Team.NONE);
            p.getInventory().clear();
            p.setGameMode(GameMode.ADVENTURE);
            // p.teleport(lobbyLocation);
        }

        // Asynchronní refresh žebříčku po zápase!
        plugin.getEloManager().updateLeaderboardCache();

        // Reset arény na WHITE_CONCRETE (v praxi by to byla asynchronní smyčka nebo FaWE)

        state = GameState.WAITING;
        plugin.getArenaManager().resetVotes();
    }

    public void addBlock(PlayerSession.Team team, Material oldBlock) {
        if (oldBlock == Material.RED_CONCRETE) redBlocks--;
        else if (oldBlock == Material.BLUE_CONCRETE) blueBlocks--;

        if (team == PlayerSession.Team.RED) redBlocks++;
        else if (team == PlayerSession.Team.BLUE) blueBlocks++;
    }

    public double getRedPercentage() { return Math.min(100.0, (double) redBlocks / totalArenaBlocks * 100); }
    public double getBluePercentage() { return Math.min(100.0, (double) blueBlocks / totalArenaBlocks * 100); }
    public boolean isStarting() { return state == GameState.STARTING; }
    public boolean isInGame() { return state == GameState.IN_GAME; }
    public int getTimeLeft() { return timeLeft; }
}