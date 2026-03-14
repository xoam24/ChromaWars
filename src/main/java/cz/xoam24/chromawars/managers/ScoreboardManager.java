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
 * Nativní Paper 1.21 Scoreboard.
 *
 * Implementace používá Team-prefix trik:
 *  - Každý řádek = anonymní entry (prázdný hráčský jméno s §-padding)
 *  - Team pro každý řádek nese prefix = barevný text (MiniMessage → Component)
 *  - Score hodnota určuje pořadí řádku
 *
 * Tento přístup je kompatibilní s Paper 1.21 a nevyžaduje setCustomName().
 */
public class ScoreboardManager {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private BukkitTask lobbyTask   = null;
    private BukkitTask ingameTask  = null;

    // UUID → vlastní Scoreboard instance
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();

    // Unikátní entry stringy pro každý řádek (§0, §1, ... §f, §0§0, ...)
    private static final String[] ENTRIES;
    static {
        String[] codes = {"0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"};
        ENTRIES = new String[32];
        for (int i = 0; i < 16; i++)  ENTRIES[i]      = "§" + codes[i];
        for (int i = 0; i < 16; i++)  ENTRIES[i + 16] = "§" + codes[i] + "§r";
    }

    public ScoreboardManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TASKY
    // ══════════════════════════════════════════════════════════════════════════

    public void startLobbyTask() {
        stopLobbyTask();
        if (!plugin.getConfigManager().getRawConfig()
                .getBoolean("scoreboard.lobby.enabled", true)) return;

        int interval = plugin.getConfigManager().getRawConfig()
                .getInt("scoreboard.lobby.update-interval", 20);

        lobbyTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    SessionManager sm = plugin.getSessionManager();
                    if (sm == null) { updateLobbyScoreboard(p); continue; }
                    PlayerSession session = sm.getSession(p);
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

    public void startIngameTask() {
        stopIngameTask();
        if (!plugin.getConfigManager().getRawConfig()
                .getBoolean("scoreboard.ingame.enabled", true)) return;

        int interval = plugin.getConfigManager().getRawConfig()
                .getInt("scoreboard.ingame.update-interval", 10);

        ingameTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    SessionManager sm = plugin.getSessionManager();
                    if (sm == null) continue;
                    PlayerSession session = sm.getSession(p);
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

    public void stopAll() {
        stopLobbyTask();
        stopIngameTask();
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
        renderScoreboard(player, titleRaw, lines, buildLobbyVars(player));
    }

    private Map<String, String> buildLobbyVars(Player player) {
        Map<String, String> v = new HashMap<>();
        EloManager     elo = plugin.getEloManager();
        ArenaManager   am  = plugin.getArenaManager();
        SessionManager sm  = plugin.getSessionManager();
        GameManager    gm  = plugin.getGameManager();

        v.put("{player}",         player.getName());
        v.put("{top1_name}",      elo.getNameAtPosition(1));
        v.put("{top1_elo}",       String.valueOf(elo.getEloAtPosition(1)));
        v.put("{top2_name}",      elo.getNameAtPosition(2));
        v.put("{top2_elo}",       String.valueOf(elo.getEloAtPosition(2)));
        v.put("{top3_name}",      elo.getNameAtPosition(3));
        v.put("{top3_elo}",       String.valueOf(elo.getEloAtPosition(3)));
        v.put("{players_online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
        v.put("{map_vote}",       am != null ? am.getLeadingArenaName() : "–");
        v.put("{countdown}",      gm != null ? String.valueOf(gm.getLobbyCountdown()) : "–");

        int pos = elo.getPositionOfPlayer(player.getName());
        v.put("{position}", pos > 0 ? String.valueOf(pos) : "–");

        if (sm != null) {
            PlayerSession session = sm.getSession(player);
            v.put("{elo}", session != null
                    ? String.valueOf(session.getCachedElo())
                    : String.valueOf(plugin.getConfigManager().getDefaultElo()));
        } else {
            v.put("{elo}", String.valueOf(plugin.getConfigManager().getDefaultElo()));
        }

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
        renderScoreboard(player, titleRaw, lines, buildIngameVars(player));
    }

    private Map<String, String> buildIngameVars(Player player) {
        Map<String, String> v = new HashMap<>();
        GameManager    gm = plugin.getGameManager();
        SessionManager sm = plugin.getSessionManager();

        v.put("{player}",     player.getName());
        v.put("{arena}",      gm != null ? gm.getCurrentArenaName() : "–");
        v.put("{time_left}",  gm != null ? gm.getFormattedTimeLeft() : "–:--");
        v.put("{red_pct}",    gm != null ? gm.getTeamPercentFormatted("red")    : "0.0");
        v.put("{blue_pct}",   gm != null ? gm.getTeamPercentFormatted("blue")   : "0.0");
        v.put("{green_pct}",  gm != null ? gm.getTeamPercentFormatted("green")  : "0.0");
        v.put("{yellow_pct}", gm != null ? gm.getTeamPercentFormatted("yellow") : "0.0");

        if (sm != null) {
            PlayerSession session = sm.getSession(player);
            if (session != null && session.getTeamId() != null) {
                var teamCfg = plugin.getConfigManager().getTeam(session.getTeamId());
                v.put("{team}", teamCfg != null ? teamCfg.displayName() : session.getTeamId());
            } else {
                v.put("{team}", "–");
            }
        } else {
            v.put("{team}", "–");
        }

        return v;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RENDER – Team-prefix trik (Paper 1.21 kompatibilní)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Renderuje scoreboard pomocí Team-prefix triku.
     *
     * Pro každý řádek:
     *  - entry string = unikátní §-kódovaný placeholder (neviditelný)
     *  - Team pro tento řádek má prefix = barevný Component text
     *  - Score tohoto entry = pozice řádku (klesající)
     *
     * Tím obejdeme setCustomName() které v Paper 1.21 neexistuje.
     */
    private void renderScoreboard(Player player, String titleRaw,
                                  List<String> lines, Map<String, String> vars) {
        // Titulek
        String resolvedTitle = replacePlaceholders(titleRaw, vars);
        Component titleComp  = mm.deserialize(resolvedTitle);

        // Vlastní Scoreboard pro hráče
        Scoreboard board = playerBoards.computeIfAbsent(
                player.getUniqueId(),
                uid -> Bukkit.getScoreboardManager().getNewScoreboard());

        // Objective – vytvoříme nebo aktualizujeme titulek
        Objective obj = board.getObjective("cw_sidebar");
        if (obj == null) {
            obj = board.registerNewObjective("cw_sidebar", Criteria.DUMMY, titleComp);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            obj.displayName(titleComp);
        }

        // Max 15 řádků (Minecraft sidebar limit), přeskočíme přebytečné
        int maxLines = Math.min(lines.size(), 15);

        // Odebereme stará entry a teamy
        for (int i = 0; i < ENTRIES.length; i++) {
            board.resetScores(ENTRIES[i]);
            Team old = board.getTeam("cw_line_" + i);
            if (old != null) old.unregister();
        }

        // Zapíšeme nové řádky
        final Objective finalObj = obj;
        for (int i = 0; i < maxLines; i++) {
            String rawLine  = lines.get(i);
            String resolved = replacePlaceholders(rawLine, vars);
            Component lineComp = mm.deserialize(resolved);

            // Entry pro tento řádek
            String entry = ENTRIES[i % ENTRIES.length];

            // Team nese prefix = barevný text
            String teamName = "cw_line_" + i;
            Team team = board.registerNewTeam(teamName);
            team.prefix(lineComp);
            team.addEntry(entry);

            // Skóre = maxLines - i (vyšší = výše na scoreboard)
            Score score = finalObj.getScore(entry);
            score.setScore(maxLines - i);
        }

        player.setScoreboard(board);
    }

    private String replacePlaceholders(String text, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    // ── Odebrání scoreboard ───────────────────────────────────────────────────

    public void removeScoreboard(Player player) {
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        Scoreboard old = playerBoards.remove(player.getUniqueId());
        if (old != null) {
            // Uklid teams aby nezůstávaly v paměti
            for (Team t : old.getTeams()) t.unregister();
        }
    }

    public void removeAllScoreboards() {
        for (Player p : Bukkit.getOnlinePlayers()) removeScoreboard(p);
        playerBoards.clear();
    }
}