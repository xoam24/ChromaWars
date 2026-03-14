package cz.xoam24.chromawars;

import cz.xoam24.chromawars.managers.ArenaManager;
import cz.xoam24.chromawars.managers.EloManager;
import cz.xoam24.chromawars.managers.GameManager;
import cz.xoam24.chromawars.managers.SessionManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI expanze pro ChromaWars.
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  DOSTUPNÉ PLACEHOLDERY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  Žebříček (POUZE z cache – nikdy nevolá DB):
 *    %chromawars_1_name%        jméno hráče na místě 1
 *    %chromawars_1_elo%         ELO hráče na místě 1
 *    %chromawars_1_position%    vrátí "1" (pro inline použití)
 *    %chromawars_<N>_name%      pro libovolné N 1–100
 *    %chromawars_<N>_elo%
 *    %chromawars_<N>_position%
 *
 *  Hráčské:
 *    %chromawars_player_elo%        ELO hráče (z leaderboard cache, nebo default)
 *    %chromawars_player_team%       název týmu hráče (nebo –)
 *    %chromawars_player_position%   pozice v žebříčku (nebo –)
 *
 *  Herní:
 *    %chromawars_arena%             název aktuální arény
 *    %chromawars_time_left%         zbývající čas  (m:ss)
 *    %chromawars_red_pct%           % pokrytí červeného týmu
 *    %chromawars_blue_pct%          % pokrytí modrého týmu
 *    %chromawars_green_pct%         % pokrytí zeleného týmu
 *    %chromawars_yellow_pct%        % pokrytí žlutého týmu
 *    %chromawars_players_in_game%   počet hráčů ve hře
 *    %chromawars_vote_leader%       vedoucí mapa hlasování
 * ─────────────────────────────────────────────────────────────────────────
 */
public class PlaceholderHook extends PlaceholderExpansion {

    // Regex pro %chromawars_<číslo>_<typ>%
    // Příklad: "3_name" → skupina 1 = "3", skupina 2 = "name"
    private static final Pattern LEADERBOARD_PATTERN =
            Pattern.compile("^(\\d+)_(name|elo|position)$");

    private final ChromaWars plugin;

    public PlaceholderHook(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── Metadata expanze ──────────────────────────────────────────────────

    @Override
    public @NotNull String getIdentifier() {
        return "chromawars";
    }

    @Override
    public @NotNull String getAuthor() {
        return "xoam24";
    }

    @Override
    public @NotNull String getVersion() {
        // Používáme plugin.getPluginMeta() místo deprecated getDescription()
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // Expanze přežije /papi reload – nemusí se znovu registrovat
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    // ── Hlavní handler ────────────────────────────────────────────────────

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        // params = vše za  %chromawars_  a před  %

        // ── 1. Žebříčkové placeholdery přes regex ────────────────────────
        // Příklady: "1_name", "5_elo", "10_position"
        Matcher matcher = LEADERBOARD_PATTERN.matcher(params);
        if (matcher.matches()) {
            return handleLeaderboard(matcher);
        }

        // ── 2. Hráčské placeholdery ───────────────────────────────────────
        if (params.startsWith("player_")) {
            return handlePlayer(player, params);
        }

        // ── 3. Herní placeholdery ─────────────────────────────────────────
        return handleGame(params);
    }

    // ── Žebříček ─────────────────────────────────────────────────────────

    private String handleLeaderboard(Matcher matcher) {
        int    position = Integer.parseInt(matcher.group(1));
        String type     = matcher.group(2);

        // Přímý getter – žádný generický cast
        EloManager elo = plugin.getEloManager();
        if (elo == null) return "–";

        return switch (type) {
            case "name"     -> elo.getNameAtPosition(position);
            case "elo"      -> String.valueOf(elo.getEloAtPosition(position));
            case "position" -> String.valueOf(position);
            default         -> null;
        };
    }

    // ── Hráč ─────────────────────────────────────────────────────────────

    private @Nullable String handlePlayer(@Nullable Player player, String params) {
        if (player == null) return "–";

        EloManager     elo = plugin.getEloManager();
        SessionManager sm  = plugin.getSessionManager();

        if (elo == null) return "–";

        return switch (params) {

            case "player_elo" -> {
                // Pokusíme se najít ELO v leaderboard cache (O(n), max 100 záznamů)
                int pos = elo.getPositionOfPlayer(player.getName());
                if (pos > 0) {
                    yield String.valueOf(elo.getEloAtPosition(pos));
                }
                // Hráč není v TOP 100 – vrátíme ELO z session cache
                if (sm != null) {
                    var session = sm.getSession(player);
                    if (session != null) {
                        yield String.valueOf(session.getCachedElo());
                    }
                }
                // Fallback: výchozí ELO z configu
                yield String.valueOf(plugin.getConfigManager().getDefaultElo());
            }

            case "player_team" -> {
                if (sm == null) yield "–";
                String teamId = sm.getPlayerTeam(player);
                if (teamId == null) yield "–";
                var teamCfg = plugin.getConfigManager().getTeam(teamId);
                yield teamCfg != null ? teamCfg.displayName() : teamId;
            }

            case "player_position" -> {
                int pos = elo.getPositionOfPlayer(player.getName());
                yield pos > 0 ? String.valueOf(pos) : "–";
            }

            default -> null;
        };
    }

    // ── Hra ──────────────────────────────────────────────────────────────

    private @Nullable String handleGame(String params) {
        GameManager  gm = plugin.getGameManager();
        ArenaManager am = plugin.getArenaManager();

        // GameManager ještě nebyl inicializován (startuje se po PAPI registraci)
        if (gm == null) {
            return switch (params) {
                case "arena", "time_left", "vote_leader" -> "–";
                case "red_pct", "blue_pct", "green_pct", "yellow_pct" -> "0.0";
                case "players_in_game" -> "0";
                default -> null;
            };
        }

        return switch (params) {
            case "arena"           -> gm.getCurrentArenaName();
            case "time_left"       -> gm.getFormattedTimeLeft();
            case "red_pct"         -> gm.getTeamPercentFormatted("red");
            case "blue_pct"        -> gm.getTeamPercentFormatted("blue");
            case "green_pct"       -> gm.getTeamPercentFormatted("green");
            case "yellow_pct"      -> gm.getTeamPercentFormatted("yellow");
            case "players_in_game" -> String.valueOf(gm.getInGamePlayerCount());
            case "vote_leader"     -> am != null ? am.getLeadingArenaName() : "–";
            default                -> null; // PAPI vrátí původní placeholder při null
        };
    }
}