package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.PlayerSession;
import cz.xoam24.chromawars.model.TeamConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Správa PlayerSession objektů.
 * Každý připojený hráč dostane session při joinu a ztratí ji při odpojení.
 * Poskytuje metody pro výběr týmu a týmové počítání.
 */
public class SessionManager implements Listener {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // Thread-safe mapa UUID → PlayerSession
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PlayerSession session = new PlayerSession(p.getUniqueId(), p.getName());
        sessions.put(p.getUniqueId(), session);

        // Asynchronně načteme ELO do cache session
        plugin.getEloManager().getPlayerEloAsync(p.getUniqueId(), elo -> {
            PlayerSession s = sessions.get(p.getUniqueId());
            if (s != null) s.setCachedElo(elo);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    // ── Gettery ───────────────────────────────────────────────────────────────

    public PlayerSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public PlayerSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * Vrátí všechny aktivní sessions.
     */
    public Collection<PlayerSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Vrátí seznam hráčů ve stavu PLAYING.
     */
    public List<Player> getPlayingPlayers() {
        List<Player> result = new ArrayList<>();
        for (PlayerSession s : sessions.values()) {
            if (s.isPlaying()) {
                Player p = plugin.getServer().getPlayer(s.getUuid());
                if (p != null) result.add(p);
            }
        }
        return result;
    }

    /**
     * Vrátí seznam hráčů daného týmu ve stavu PLAYING.
     */
    public List<Player> getPlayersInTeam(String teamId) {
        List<Player> result = new ArrayList<>();
        for (PlayerSession s : sessions.values()) {
            if (teamId.equals(s.getTeamId()) && s.isPlaying()) {
                Player p = plugin.getServer().getPlayer(s.getUuid());
                if (p != null) result.add(p);
            }
        }
        return result;
    }

    /**
     * Vrátí ID týmu hráče, nebo null.
     */
    public String getPlayerTeam(Player player) {
        PlayerSession s = getSession(player);
        return s != null ? s.getTeamId() : null;
    }

    /**
     * Počet hráčů v daném týmu (ve stavu LOBBY nebo PLAYING).
     */
    public int countPlayersInTeam(String teamId) {
        int count = 0;
        for (PlayerSession s : sessions.values()) {
            if (teamId.equals(s.getTeamId())) count++;
        }
        return count;
    }

    // ── Přiřazení týmu ────────────────────────────────────────────────────────

    /**
     * Nastaví hráči tým. Kontroluje kapacitu a informuje hráče.
     */
    public boolean setPlayerTeam(Player player, String teamId) {
        TeamConfig team = plugin.getConfigManager().getTeam(teamId);
        if (team == null) return false;

        PlayerSession session = getSession(player);
        if (session == null) return false;

        // Kontrola kapacity
        if (countPlayersInTeam(teamId) >= team.maxPlayers()) {
            plugin.getMessageManager().send(player, "lobby.team-full",
                    "team", team.displayName(),
                    "max",  String.valueOf(team.maxPlayers()));
            return false;
        }

        session.setTeamId(teamId);
        plugin.getMessageManager().send(player, "lobby.team-join",
                "team",       team.displayName(),
                "team_color", "<#" + Integer.toHexString(team.color().value()) + ">");
        return true;
    }

    /**
     * Přiřadí hráče do nejméně obsazeného týmu.
     */
    public void assignRandomTeam(Player player) {
        Map<String, TeamConfig> teams = plugin.getConfigManager().getTeams();
        String bestTeam = null;
        int    minCount = Integer.MAX_VALUE;

        for (Map.Entry<String, TeamConfig> e : teams.entrySet()) {
            int count = countPlayersInTeam(e.getKey());
            if (count < e.getValue().maxPlayers() && count < minCount) {
                minCount = count;
                bestTeam = e.getKey();
            }
        }

        if (bestTeam == null) {
            plugin.getMessageManager().send(player, "lobby.team-full",
                    "team", "všechny", "max", "");
            return;
        }
        setPlayerTeam(player, bestTeam);
    }
}