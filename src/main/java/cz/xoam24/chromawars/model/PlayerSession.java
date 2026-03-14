package cz.xoam24.chromawars.model;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Dočasný datový objekt pro každého připojeného hráče.
 * Drží stav hráče v rámci ChromaWars (lobby / hra / spectator).
 * Nevytváří se pro hráče, kteří ChromaWars nevyužívají.
 */
public class PlayerSession {

    // ── Stavy hráče ───────────────────────────────────────────────────────────
    public enum State {
        LOBBY,      // hráč je v lobby, vybírá tým / hlasuje
        PLAYING,    // hráč aktivně hraje
        SPECTATOR   // hráč zemřel, čeká na respawn
    }

    private final UUID   uuid;
    private final String name;

    private State    state       = State.LOBBY;
    private String   teamId      = null;   // null = bez týmu

    // Záloha inventáře z lobby (obnoví se po konci hry)
    private ItemStack[] savedInventory = null;
    private GameMode    savedGameMode  = GameMode.SURVIVAL;
    private Location    savedLocation  = null;

    // Respawn odpočet (sekundy zbývající do respawnu)
    private int respawnCountdown = 0;

    // ELO cache pro tento session (aby se nemuselo tahat z DB při každém tick)
    private int cachedElo = 1000;

    public PlayerSession(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ── Gettery / Settery ─────────────────────────────────────────────────────

    public UUID   getUuid()    { return uuid; }
    public String getName()    { return name; }

    public State  getState()               { return state; }
    public void   setState(State state)    { this.state = state; }

    public String getTeamId()              { return teamId; }
    public void   setTeamId(String teamId) { this.teamId = teamId; }

    public boolean isInTeam()              { return teamId != null; }
    public boolean isPlaying()             { return state == State.PLAYING; }
    public boolean isSpectator()           { return state == State.SPECTATOR; }
    public boolean isInLobby()             { return state == State.LOBBY; }

    public ItemStack[] getSavedInventory()                       { return savedInventory; }
    public void        setSavedInventory(ItemStack[] inv)        { this.savedInventory = inv; }

    public GameMode getSavedGameMode()                           { return savedGameMode; }
    public void     setSavedGameMode(GameMode gm)                { this.savedGameMode = gm; }

    public Location getSavedLocation()                           { return savedLocation; }
    public void     setSavedLocation(Location loc)               { this.savedLocation = loc; }

    public int  getRespawnCountdown()                            { return respawnCountdown; }
    public void setRespawnCountdown(int seconds)                 { this.respawnCountdown = seconds; }
    public void decrementRespawnCountdown()                      { if (respawnCountdown > 0) respawnCountdown--; }

    public int  getCachedElo()                                   { return cachedElo; }
    public void setCachedElo(int elo)                            { this.cachedElo = elo; }

    // ── Reset pro lobby ───────────────────────────────────────────────────────

    /**
     * Resetuje herní stav hráče do lobby (zachová cachedElo).
     */
    public void resetToLobby() {
        state            = State.LOBBY;
        teamId           = null;
        respawnCountdown = 0;
    }
}