package cz.xoam24.chromawars;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI expanze pro ChromaWars.
 *
 * ─────────────────────────────────────────────────────────────
 *  DOSTUPNÉ PLACEHOLDERY
 * ─────────────────────────────────────────────────────────────
 *  Hráčské (dynamické):
 *    %chromawars_player_elo%        ELO daného hráče
 *    %chromawars_player_team%       Aktuální tým hráče (nebo "–")
 *    %chromawars_player_position%   Pozice v žebříčku (nebo "–")
 *
 *  Žebříček (z CACHE – nikdy nevolá DB):
 *    %chromawars_<N>_name%          Jméno hráče na místě N (1-100)
 *    %chromawars_<N>_elo%           ELO hráče na místě N
 *    %chromawars_<N>_position%      Číslo N (pro inline použití v scoreboard)
 *
 *  Hra:
 *    %chromawars_arena%             Název aktuální arény (nebo "–")
 *    %chromawars_red_pct%           % pokrytí červeného týmu
 *    %chromawars_blue_pct%          % pokrytí modrého týmu
 *    %chromawars_green_pct%         % pokrytí zeleného týmu
 *    %chromawars_yellow_pct%        % pokrytí žlutého týmu
 *    %chromawars_time_left%         Zbývající čas (mm:ss)
 *    %chromawars_players_in_game%   Počet hráčů ve hře
 *    %chromawars_vote_leader%       Vedoucí mapa hlasování
 *
 * ─────────────────────────────────────────────────────────────
 *  DŮLEŽITÉ: VŠECHNA ČTENÍ PROBÍHAJÍ Z CACHE (EloManager).
 *  Žádný databázový dotaz při vyhodnocení placeholderu!
 * ─────────────────────────────────────────────────────────────
 */
public class PlaceholderHook extends PlaceholderExpansion {

    // Regex pro dynamické žebříčkové placeholdery: chromawars_<číslo>_<typ>
    private static final Pattern LEADERBOARD_PATTERN =
            Pattern.compile("^(\\d+)_(name|elo|position)$");

    private final ChromaWars plugin;

    public PlaceholderHook(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── Metadata expanze ──────────────────────────────────────────────────────

    @Override
    public @NotNull String getIdentifier() { return "chromawars"; }

    @Override
    public @NotNull String getAuthor()     { return "xoam24"; }

    @Override
    public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist()               { return true; }   // přežije /papi reload

    @Override
    public boolean canRegister()           { return true; }

    // ── Hlavní metoda ─────────────────────────────────────────────────────────

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        // params = vše za %chromawars_
        EloManager elo = plugin.getEloManager();

        // ── 1. Žebříčkové placeholdery (regex) ───────────────────────────────
        Matcher m = LEADERBOARD_PATTERN.matcher(params);
        if (m.matches()) {
            int position = Integer.parseInt(m.group(1));
            String type  = m.group(2);
            return switch (type) {
                case "name"     -> elo.getNameAtPosition(position);
                case "elo"      -> String.valueOf(elo.getEloAtPosition(position));
                case "position" -> String.valueOf(position);
                default         -> null;
            };
        }

        // ── 2. Hráčské placeholdery ───────────────────────────────────────────
        if (params.startsWith("player_")) {
            if (player == null) return "–";
            return switch (params) {

                case "player_elo" -> {
                    // ELO hráče: pokus najít v cache, jinak vrátíme "..."
                    // (Async načtení by v PAPI callbacku bylo nebezpečné, proto cache)
                    int pos = elo.getPositionOfPlayer(player.getName());
                    if (pos > 0) {
                        yield String.valueOf(elo.getEloAtPosition(pos));
                    }
                    // Hráč není v TOP 100 – vrátíme výchozí ELO z configu
                    // (přesné ELO by vyžadovalo async DB dotaz, což v PAPI nelze bezpečně)
                    yield String.valueOf(plugin.getConfigManager().getDefaultElo());
                }

                case "player_team" -> {
                    if (plugin.getSessionManager() == null) yield "–";
                    String teamId = plugin.getSessionManager().getPlayerTeam(player);
                    if (teamId == null) yield "–";
                    var teamCfg = plugin.getConfigManager().getTeam(teamId);
                    yield teamCfg != null ? teamCfg.displayName() : teamId;
                }

                case "player_position" -> {
                    int position = elo.getPositionOfPlayer(player.getName());
                    yield position > 0 ? String.valueOf(position) : "–";
                }

                default -> null;
            };
        }

        // ── 3. Herní placeholdery ─────────────────────────────────────────────
        if (plugin.getGameManager() == null) {
            // GameManager není ještě inicializován (nastane jen při startu)
            return switch (params) {
                case "arena", "time_left", "vote_leader" -> "–";
                case "red_pct", "blue_pct", "green_pct", "yellow_pct" -> "0";
                case "players_in_game" -> "0";
                default -> null;
            };
        }

        return switch (params) {
            case "arena"           -> plugin.getGameManager().getCurrentArenaName();
            case "time_left"       -> plugin.getGameManager().getFormattedTimeLeft();
            case "red_pct"         -> plugin.getGameManager().getTeamPercentFormatted("red");
            case "blue_pct"        -> plugin.getGameManager().getTeamPercentFormatted("blue");
            case "green_pct"       -> plugin.getGameManager().getTeamPercentFormatted("green");
            case "yellow_pct"      -> plugin.getGameManager().getTeamPercentFormatted("yellow");
            case "players_in_game" -> String.valueOf(plugin.getGameManager().getInGamePlayerCount());
            case "vote_leader"     -> {
                ArenaManager am = plugin.getArenaManager();
                yield am != null ? am.getLeadingArenaName() : "–";
            }
            default -> null;  // PAPI vrátí původní placeholder pokud null
        };
    }
}