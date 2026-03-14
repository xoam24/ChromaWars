package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.PlayerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nativní Paper 1.21 Scoreboard – Team-prefix trik.
 *
 * Opravy oproti předchozí verzi:
 *  - MiniMessage se parsuje VŽDY přes convertToMiniMessage() který
 *    převede legacy &#RRGGBB na správný <#RRGGBB> formát před parsováním.
 *  - Scoreboard title se renderuje jako Section-sign legacy string
 *    (Objective.displayName() v Paper 1.21 přijímá Component).
 *  - Team prefix přijímá plný Component – fungují gradienty i barvy.
 *  - Teamy se recyklují (neregistrují se znovu každý tick → žádné exceptions).
 */
public class ScoreboardManager {

    private final ChromaWars plugin;
    private final MiniMessage MM = MiniMessage.miniMessage();

    // Regex pro konverzi &#RRGGBB → <#RRGGBB>
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private BukkitTask lobbyTask  = null;
    private BukkitTask ingameTask = null;

    // UUID → vlastní Scoreboard instance pro každého hráče
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();

    // Unikátní neviditelné entry stringy pro řádky (§0 .. §f, §0§0 ..)
    private static final String[] ENTRIES = buildEntries();

    private static String[] buildEntries() {
        String[] codes = {"0","1","2","3","4","5","6","7",
                "8","9","a","b","c","d","e","f"};
        String[] arr = new String[32];
        for (int i = 0; i < 16; i++) arr[i]      = "\u00A7" + codes[i];
        for (int i = 0; i < 16; i++) arr[i + 16] = "\u00A7" + codes[i] + "\u00A7r";
        return arr;
    }

    public ScoreboardManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TASKY
    // ══════════════════════════════════════════════════════════════════════

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
                    PlayerSession session = sm != null ? sm.getSession(p) : null;
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

    // ══════════════════════════════════════════════════════════════════════
    //  LOBBY
    // ══════════════════════════════════════════════════════════════════════

    public void updateLobbyScoreboard(Player player) {
        List<String> lines = plugin.getConfigManager().getRawConfig()
                .getStringList("scoreboard.lobby.lines");
        String titleRaw = plugin.getConfigManager().getRawConfig()
                .getString("scoreboard.lobby.title",
                        "<bold><gradient:#FF4444:#4477FF>ChromaWars</gradient></bold>");

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
        v.put("{map_vote}",       am != null ? am.getLeadingArenaName() : "\u2013");
        v.put("{countdown}",      gm != null ? String.valueOf(gm.getLobbyCountdown()) : "\u2013");

        int pos = elo.getPositionOfPlayer(player.getName());
        v.put("{position}", pos > 0 ? String.valueOf(pos) : "\u2013");

        PlayerSession session = sm != null ? sm.getSession(player) : null;
        v.put("{elo}", session != null
                ? String.valueOf(session.getCachedElo())
                : String.valueOf(plugin.getConfigManager().getDefaultElo()));

        return v;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  IN-GAME
    // ══════════════════════════════════════════════════════════════════════

    public void updateIngameScoreboard(Player player) {
        List<String> lines = plugin.getConfigManager().getRawConfig()
                .getStringList("scoreboard.ingame.lines");
        String titleRaw = plugin.getConfigManager().getRawConfig()
                .getString("scoreboard.ingame.title",
                        "<bold><gradient:#FF4444:#4477FF>ChromaWars</gradient></bold>");

        renderScoreboard(player, titleRaw, lines, buildIngameVars(player));
    }

    private Map<String, String> buildIngameVars(Player player) {
        Map<String, String> v = new HashMap<>();
        GameManager    gm = plugin.getGameManager();
        SessionManager sm = plugin.getSessionManager();

        v.put("{player}",     player.getName());
        v.put("{arena}",      gm != null ? gm.getCurrentArenaName() : "\u2013");
        v.put("{time_left}",  gm != null ? gm.getFormattedTimeLeft() : "\u2013");
        v.put("{red_pct}",    gm != null ? gm.getTeamPercentFormatted("red")    : "0.0");
        v.put("{blue_pct}",   gm != null ? gm.getTeamPercentFormatted("blue")   : "0.0");
        v.put("{green_pct}",  gm != null ? gm.getTeamPercentFormatted("green")  : "0.0");
        v.put("{yellow_pct}", gm != null ? gm.getTeamPercentFormatted("yellow") : "0.0");

        PlayerSession session = sm != null ? sm.getSession(player) : null;
        if (session != null && session.getTeamId() != null) {
            var teamCfg = plugin.getConfigManager().getTeam(session.getTeamId());
            v.put("{team}", teamCfg != null ? teamCfg.displayName() : session.getTeamId());
        } else {
            v.put("{team}", "\u2013");
        }

        return v;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RENDER – Team-prefix trik (Paper 1.21)
    // ══════════════════════════════════════════════════════════════════════

    private void renderScoreboard(Player player, String titleRaw,
                                  List<String> lines, Map<String, String> vars) {
        // Získáme nebo vytvoříme board
        Scoreboard board = playerBoards.computeIfAbsent(
                player.getUniqueId(),
                uid -> Bukkit.getScoreboardManager().getNewScoreboard());

        // ── Titulek Objective ──────────────────────────────────────────────
        String resolvedTitle = replacePlaceholders(titleRaw, vars);
        Component titleComp  = parse(resolvedTitle);

        Objective obj = board.getObjective("cw_sb");
        if (obj == null) {
            obj = board.registerNewObjective("cw_sb", Criteria.DUMMY, titleComp);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            obj.displayName(titleComp);
        }

        int maxLines = Math.min(lines.size(), 15);

        // ── Smažeme staré záznamy (skóre) ──────────────────────────────────
        // Nestačí resetovat score – musíme odstranit entries ze scoreboard
        // jinak zůstanou viditelné prázdné řádky
        for (String entry : new HashSet<>(board.getEntries())) {
            board.resetScores(entry);
        }

        // ── Smažeme staré teamy (recyklace zabrání memory leaku) ───────────
        for (int i = 0; i < ENTRIES.length; i++) {
            Team old = board.getTeam("cw_l" + i);
            if (old != null) old.unregister();
        }

        // ── Zapíšeme nové řádky ─────────────────────────────────────────────
        for (int i = 0; i < maxLines; i++) {
            String raw      = lines.get(i);
            String resolved = replacePlaceholders(raw, vars);
            Component lineComp = parse(resolved);

            String entry    = ENTRIES[i % ENTRIES.length];
            Team   team     = board.registerNewTeam("cw_l" + i);

            // prefix = celý barevný text řádku
            team.prefix(lineComp);
            // suffix je prázdný Component (ne null)
            team.suffix(Component.empty());
            team.addEntry(entry);

            // Skóre: nejvyšší nahoře
            obj.getScore(entry).setScore(maxLines - i);
        }

        player.setScoreboard(board);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MINIMESSAGE HELPER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Konvertuje legacy &#RRGGBB na <#RRGGBB> a pak parsuje přes MiniMessage.
     * Toto je klíčová oprava – bez konverze zůstávají tagy viditelné jako text.
     */
    private Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        // 1. &#RRGGBB → <#RRGGBB>
        String converted = LEGACY_HEX.matcher(input)
                .replaceAll(mr -> "<#" + mr.group(1) + ">");
        // 2. Parse přes MiniMessage
        return MM.deserialize(converted);
    }

    private String replacePlaceholders(String text, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    // ── Odebrání scoreboard ───────────────────────────────────────────────

    public void removeScoreboard(Player player) {
        Scoreboard old = playerBoards.remove(player.getUniqueId());
        if (old != null) {
            for (Team t : old.getTeams()) {
                try { t.unregister(); } catch (Exception ignored) {}
            }
        }
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void removeAllScoreboards() {
        for (Player p : Bukkit.getOnlinePlayers()) removeScoreboard(p);
        playerBoards.clear();
    }
}