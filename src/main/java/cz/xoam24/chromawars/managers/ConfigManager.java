package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.TeamConfig;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.*;

/**
 * Načítá a cachuje veškerou konfiguraci z config.yml.
 * Podporuje HEX barvy ve formátu &#RRGGBB (Adventure API).
 */
public class ConfigManager {

    private final ChromaWars plugin;
    private FileConfiguration config;

    // ── Team konfigurace ──────────────────────────────────────────────────────
    // Klíč = interní název týmu (malými písmeny), Hodnota = TeamConfig objekt
    private final Map<String, TeamConfig> teams = new LinkedHashMap<>();

    // ── Herní nastavení ───────────────────────────────────────────────────────
    private int defaultElo;
    private int lobbyCountdown;       // sekundy do spuštění hry v lobby
    private int gameDuration;         // délka hry v sekundách
    private int minPlayersToStart;
    private double winPercentage;     // % arény které musí tým obsadit pro výhru
    private int eloWin;               // ELO za výhru
    private int eloLoss;              // ELO ztráta za prohru

    // ── Lokace lobby ─────────────────────────────────────────────────────────
    private String lobbyWorldName;
    private double lobbyX, lobbyY, lobbyZ;
    private float lobbyYaw, lobbyPitch;

    // ── Arény ─────────────────────────────────────────────────────────────────
    // Klíč = název arény
    private final Set<String> arenaNames = new HashSet<>();

    public ConfigManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── Načtení veškeré konfigurace ───────────────────────────────────────────

    public void loadAll() {
        saveDefaultConfig();
        this.config = plugin.getConfig();
        config.options().copyDefaults(true);
        plugin.saveConfig();

        loadGameSettings();
        loadTeams();
        loadLobbyLocation();
        loadArenas();
    }

    private void saveDefaultConfig() {
        plugin.saveDefaultConfig();
        // Pokud config.yml neexistuje, vytvoříme ho s výchozími hodnotami
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
    }

    private void loadGameSettings() {
        defaultElo         = config.getInt("game.default-elo", 1000);
        lobbyCountdown     = config.getInt("game.lobby-countdown", 30);
        gameDuration       = config.getInt("game.game-duration", 180);
        minPlayersToStart  = config.getInt("game.min-players", 2);
        winPercentage      = config.getDouble("game.win-percentage", 90.0);
        eloWin             = config.getInt("game.elo-win", 25);
        eloLoss            = config.getInt("game.elo-loss", 15);
    }

    private void loadTeams() {
        teams.clear();
        ConfigurationSection teamsSection = config.getConfigurationSection("teams");
        if (teamsSection == null) {
            plugin.getLogger().warning("Sekce 'teams' v config.yml nenalezena – používám výchozí týmy.");
            loadDefaultTeams();
            return;
        }

        for (String teamKey : teamsSection.getKeys(false)) {
            ConfigurationSection ts = teamsSection.getConfigurationSection(teamKey);
            if (ts == null) continue;

            String displayName = ts.getString("name", teamKey);
            String hexColor    = ts.getString("color", "#FFFFFF");
            String materialStr = ts.getString("block", "WHITE_CONCRETE");
            int maxPlayers     = ts.getInt("max-players", 4);

            // Validace Material
            Material blockMaterial;
            try {
                blockMaterial = Material.valueOf(materialStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Tým '" + teamKey + "' má neplatný materiál '" + materialStr + "'. Používám WHITE_CONCRETE.");
                blockMaterial = Material.WHITE_CONCRETE;
            }

            // Validace HEX barvy
            TextColor color;
            try {
                color = TextColor.fromHexString(hexColor.startsWith("#") ? hexColor : "#" + hexColor.replace("&#", ""));
                if (color == null) throw new IllegalArgumentException("null color");
            } catch (Exception e) {
                plugin.getLogger().warning("Tým '" + teamKey + "' má neplatnou barvu '" + hexColor + "'. Používám bílou.");
                color = TextColor.color(0xFFFFFF);
            }

            teams.put(teamKey.toLowerCase(), new TeamConfig(teamKey.toLowerCase(), displayName, color, blockMaterial, maxPlayers));
        }

        if (teams.isEmpty()) {
            plugin.getLogger().warning("Žádné týmy nenačteny! Inicializuji výchozí týmy.");
            loadDefaultTeams();
        }

        plugin.getLogger().info("Načteno " + teams.size() + " týmů: " + String.join(", ", teams.keySet()));
    }

    private void loadDefaultTeams() {
        teams.put("red",    new TeamConfig("red",    "Červený tým",  TextColor.color(0xFF4444), Material.RED_CONCRETE,    4));
        teams.put("blue",   new TeamConfig("blue",   "Modrý tým",    TextColor.color(0x4477FF), Material.BLUE_CONCRETE,   4));
        teams.put("green",  new TeamConfig("green",  "Zelený tým",   TextColor.color(0x44DD44), Material.GREEN_CONCRETE,  4));
        teams.put("yellow", new TeamConfig("yellow", "Žlutý tým",    TextColor.color(0xFFDD00), Material.YELLOW_CONCRETE, 4));
    }

    private void loadLobbyLocation() {
        ConfigurationSection ls = config.getConfigurationSection("lobby");
        if (ls == null) return;
        lobbyWorldName = ls.getString("world", "world");
        lobbyX    = ls.getDouble("x", 0);
        lobbyY    = ls.getDouble("y", 64);
        lobbyZ    = ls.getDouble("z", 0);
        lobbyYaw  = (float) ls.getDouble("yaw", 0);
        lobbyPitch= (float) ls.getDouble("pitch", 0);
    }

    private void loadArenas() {
        arenaNames.clear();
        ConfigurationSection arenaSection = config.getConfigurationSection("arenas");
        if (arenaSection == null) return;
        arenaNames.addAll(arenaSection.getKeys(false));
    }

    // ── Uložení lobby pozice (volá CommandManager) ────────────────────────────

    public void saveLobbyLocation(String world, double x, double y, double z, float yaw, float pitch) {
        config.set("lobby.world",  world);
        config.set("lobby.x",      x);
        config.set("lobby.y",      y);
        config.set("lobby.z",      z);
        config.set("lobby.yaw",    yaw);
        config.set("lobby.pitch",  pitch);
        plugin.saveConfig();
        // Znovu načteme
        lobbyWorldName = world;
        lobbyX = x; lobbyY = y; lobbyZ = z;
        lobbyYaw = yaw; lobbyPitch = pitch;
    }

    // ── Správa arén v configu ─────────────────────────────────────────────────

    public void saveArena(String name, String world,
                          int x1, int y1, int z1,
                          int x2, int y2, int z2) {
        String path = "arenas." + name + ".";
        config.set(path + "world", world);
        config.set(path + "pos1.x", x1);
        config.set(path + "pos1.y", y1);
        config.set(path + "pos1.z", z1);
        config.set(path + "pos2.x", x2);
        config.set(path + "pos2.y", y2);
        config.set(path + "pos2.z", z2);
        plugin.saveConfig();
        arenaNames.add(name);
    }

    public void deleteArena(String name) {
        config.set("arenas." + name, null);
        plugin.saveConfig();
        arenaNames.remove(name);
    }

    // ── HEX barva helper ──────────────────────────────────────────────────────

    /**
     * Převede řetězec ve formátu "&#RRGGBB" nebo "#RRGGBB" na TextColor.
     * Vrátí bílou barvu při chybě.
     */
    public static TextColor parseHexColor(String raw) {
        if (raw == null) return TextColor.color(0xFFFFFF);
        String clean = raw.replace("&#", "#");
        if (!clean.startsWith("#")) clean = "#" + clean;
        try {
            TextColor c = TextColor.fromHexString(clean);
            return c != null ? c : TextColor.color(0xFFFFFF);
        } catch (Exception e) {
            return TextColor.color(0xFFFFFF);
        }
    }

    // ── Gettery ───────────────────────────────────────────────────────────────

    public FileConfiguration getRawConfig()    { return config; }
    public Map<String, TeamConfig> getTeams()  { return Collections.unmodifiableMap(teams); }
    public TeamConfig getTeam(String key)      { return teams.get(key.toLowerCase()); }
    public Set<String> getArenaNames()         { return Collections.unmodifiableSet(arenaNames); }
    public int getDefaultElo()                 { return defaultElo; }
    public int getLobbyCountdown()             { return lobbyCountdown; }
    public int getGameDuration()               { return gameDuration; }
    public int getMinPlayersToStart()          { return minPlayersToStart; }
    public double getWinPercentage()           { return winPercentage; }
    public int getEloWin()                     { return eloWin; }
    public int getEloLoss()                    { return eloLoss; }
    public String getLobbyWorldName()          { return lobbyWorldName; }
    public double getLobbyX()                  { return lobbyX; }
    public double getLobbyY()                  { return lobbyY; }
    public double getLobbyZ()                  { return lobbyZ; }
    public float getLobbyYaw()                 { return lobbyYaw; }
    public float getLobbyPitch()               { return lobbyPitch; }
}