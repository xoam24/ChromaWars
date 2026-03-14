package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.PlayerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Nativní Paper scoreboard pro lobby a in-game.
 *
 * Každý hráč má vlastní Scoreboard instanci (zabrání konfliktům).
 * Řádky se čtou z config.yml (scoreboard.lobby.lines / scoreboard.ingame.lines).
 * Proměnné se nahrazují v metodě replacePlaceholders().
 *
 * Lobby proměnné:
 *   {player} {elo} {position} {top1_name} {top1_elo} {top2_name} {top2_elo}
 *   {top3_name} {top3_elo} {players_online} {countdown} {map_vote}
 *
 * In-game proměnné:
 *   {player} {team} {arena} {time_left}
 *   {red_pct} {blue_pct} {green_pct} {yellow_pct}
 */
public class ScoreboardManager {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // Tasky pro pravidelnou aktualizaci
    private BukkitTask lobbyTask   = null;
    private BukkitTask ingameTask  = null;

    // UUID → Scoreboard (vlastní instance pro každého hráče)
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();

    public ScoreboardManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  START / STOP TASKŮ
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Spustí lobby scoreboard ticker.
     * Volat při startu pluginu (v ChromaWars.onEnable nebo při resetu hry).
     */
    public void startLobbyTask() {
        stopLobbyTask();
        int interval = plugin.getConfigManager().getRawConfig()
                .getInt("scoreboard.lobby.update-interval", 20);
        if (!plugin.getConfigManager().getRawConfig()
                .getBoolean("scoreboard.lobby.enabled", true)) return;

        lobbyTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    PlayerSession session = plugin.getSessionManager() != null
                            ? plugin.<SessionManager>getSessionManager().getSession(p) : null;
                    if (session == null || session.isInLobby()) {
                        updateLobbyScoreboard(p);
                    }
                }
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    public void stopLobbyTask() {
        if (lobbyTask != null && !lobbyTask.isCancelled()) {
            lobbyTask.cancel();
            lobbyTask = null;
        }
    }

    /**
     * Spustí in-game scoreboard ticker.
     * Volat v GameManager.startGame().
     */
    public void startIngameTask() {
        stopIngameTask();
        int interval = plugin.getConfigManager().getRawConfig()
                .getInt("scoreboard.ingame.update-interval", 10);
        if (!plugin.getConfigManager().getRawConfig()
                .getBoolean("scoreboard.ingame.enabled", true)) return;

        ingameTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    PlayerSession session = plugin.<SessionManager>getSessionManager() != null
                            ? plugin.<SessionManager>getSessionManager().getSession(p) : null;
                    if (session != null && (session.isPlaying() || session.isSpectator())) {
                        updateIngameScoreboard(p);
                    }
                }
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    public void stopIngameTask() {
        if (ingameTask != null && !ingameTask.isCancelled()) {
            ingameTask.cancel();
            ingameTask = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOBBY SCOREBOARD
    // ══════════════════════════════════════════════════════════════════════════

    public void updateLobbyScoreboard(Player player) {
        List<String> lines = plugin.getConfigManager().getRawConfig()
                .getStringList("scoreboard.lobby.lines");
        String titleRaw = plugin.getConfigManager().getRawConfig()
                .getString("scoreboard.lobby.title",
                        "<bold><gradient:&#FF4444:&#4477FF>ChromaWars</gradient></bold>");

        Map<String, String> vars = buildLobbyVars(player);
        renderScoreboard(player, titleRaw, lines, vars);
    }

    private Map<String, String> buildLobbyVars(Player player) {
        Map<String, String> v = new HashMap<>();
        EloManager   elo      = plugin.getEloManager();
        ArenaManager arenas   = plugin.getArenaManager();
        SessionManager sm     = plugin.<SessionManager>getSessionManager();

        v.put("{player}",         player.getName());
        v.put("{top1_name}",      elo.getNameAtPosition(1));
        v.put("{top1_elo}",       String.valueOf(elo.getEloAtPosition(1)));
        v.put("{top2_name}",      elo.getNameAtPosition(2));
        v.put("{top2_elo}",       String.valueOf(elo.getEloAtPosition(2)));
        v.put("{top3_name}",      elo.getNameAtPosition(3));
        v.put("{top3_elo}",       String.valueOf(elo.getEloAtPosition(3)));
        v.put("{players_online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
        v.put("{map_vote}",       arenas != null ? arenas.getLeadingArenaName() : "–");

        // ELO a pozice hráče z cache session
        if (sm != null) {
            PlayerSession session = sm.getSession(player);
            if (session != null) {
                v.put("{elo}",      String.valueOf(session.getCachedElo()));
            } else {
                v.put("{elo}",      String.valueOf(plugin.getConfigManager().getDefaultElo()));
            }
        } else {
            v.put("{elo}", String.valueOf(plugin.getConfigManager().getDefaultElo()));
        }

        int pos = elo.getPositionOfPlayer(player.getName());
        v.put("{position}", pos > 0 ? String.valueOf(pos) : "–");

        // Countdown
        GameManager gm = plugin.<GameManager>getGameManager();
        v.put("{countdown}", gm != null ? String.valueOf(gm.getLobbyCountdown()) : "–");

        return v;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  IN-GAME SCOREBOARD
    // ══════════════════════════════════════════════════════════════════════════

    public void updateIngameScoreboard(Player player) {
        List<String> lines = plugin.getConfigManager().getRawConfig()
                .getStringList("scoreboard.ingame.lines");
        String titleRaw = plugin.getConfigManager().getRawConfig()
                .getString("scoreboard.ingame.title",
                        "<bold><gradient:&#FF4444:&#4477FF>ChromaWars</gradient></bold>");

        Map<String, String> vars = buildIngameVars(player);
        renderScoreboard(player, titleRaw, lines, vars);
    }

    private Map<String, String> buildIngameVars(Player player) {
        Map<String, String> v = new HashMap<>();
        GameManager   gm  = plugin.<GameManager>getGameManager();
        SessionManager sm = plugin.<SessionManager>getSessionManager();

        v.put("{player}",     player.getName());
        v.put("{arena}",      gm != null ? gm.getCurrentArenaName() : "–");
        v.put("{time_left}",  gm != null ? gm.getFormattedTimeLeft() : "–");
        v.put("{red_pct}",    gm != null ? gm.getTeamPercentFormatted("red")    : "0");
        v.put("{blue_pct}",   gm != null ? gm.getTeamPercentFormatted("blue")   : "0");
        v.put("{green_pct}",  gm != null ? gm.getTeamPercentFormatted("green")  : "0");
        v.put("{yellow_pct}", gm != null ? gm.getTeamPercentFormatted("yellow") : "0");

        if (sm != null) {
            PlayerSession session = sm.getSession(player);
            if (session != null && session.getTeamId() != null) {
                var team = plugin.getConfigManager().getTeam(session.getTeamId());
                v.put("{team}", team != null ? team.displayName() : session.getTeamId());
            } else {
                v.put("{team}", "–");
            }
        } else {
            v.put("{team}", "–");
        }

        return v;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  JÁDRO – RENDER
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Vytvoří nebo aktualizuje scoreboard hráče.
     * Každý hráč má vlastní Scoreboard instanci, takže změny se navzájem neovlivňují.
     */
    private void renderScoreboard(Player player, String titleRaw,
                                  List<String> lines, Map<String, String> vars) {
        // Nahradíme proměnné v titulu
        String resolvedTitle = replacePlaceholders(titleRaw, vars);
        Component titleComponent = mm.deserialize(resolvedTitle);

        // Získáme nebo vytvoříme Scoreboard pro tohoto hráče
        Scoreboard board = playerBoards.computeIfAbsent(player.getUniqueId(), uuid -> {
            Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
            return b;
        });

        // Najdeme nebo vytvoříme Objective
        Objective obj = board.getObjective("chromawars");
        if (obj == null) {
            obj = board.registerNewObjective("chromawars", Criteria.DUMMY, titleComponent);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            obj.displayName(titleComponent);
        }

        // Odstraníme staré záznamy
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        // Zapíšeme řádky (skóre klesá od počtu řádků do 1)
        // Omezíme na max 15 řádků (Minecraft limit pro sidebar)
        int maxLines = Math.min(lines.size(), 15);
        int score    = maxLines;

        for (int i = 0; i < maxLines; i++) {
            String rawLine  = lines.get(i);
            String resolved = replacePlaceholders(rawLine, vars);
            Component line  = mm.deserialize(resolved);

            // Unikátní entry pro každý řádek (§0 - §f + §r padding)
            // Paper 1.21 supportuje přímý Component v Score
            Score s = obj.getScore(Bukkit.getOfflinePlayer(
                    generateUniqueEntry(i)));
            s.setCustomName(line);   // Paper API – nastaví Component přímo
            s.setScore(score--);
        }

        player.setScoreboard(board);
    }

    /**
     * Generuje unikátní neviditelný řetězec pro každý řádek scoreboard.
     * Bukkit vyžaduje unikátní entry strings.
     */
    private String generateUniqueEntry(int index) {
        // §0§0§0... (kombinace color kódů jako padding)
        String[] colors = {"§0","§1","§2","§3","§4","§5","§6","§7",
                "§8","§9","§a","§b","§c","§d","§e","§f"};
        return colors[index % colors.length] + "§r".repeat(index / colors.length);
    }

    /**
     * Nahradí všechny proměnné {key} hodnotami z mapy.
     */
    private String replacePlaceholders(String text, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    // ── Odebrání scoreboard hráči ─────────────────────────────────────────────

    /**
     * Vrátí hráči výchozí (prázdný) scoreboard.
     * Volat při odpojení nebo při přechodu mimo plugin.
     */
    public void removeScoreboard(Player player) {
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        playerBoards.remove(player.getUniqueId());
    }

    public void removeAllScoreboards() {
        for (Player p : Bukkit.getOnlinePlayers()) removeScoreboard(p);
        playerBoards.clear();
    }

    public void stopAll() {
        stopLobbyTask();
        stopIngameTask();
    }
}