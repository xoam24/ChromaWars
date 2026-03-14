package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.LeaderboardEntry;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Veškerá SQLite logika.
 *
 * Zásady:
 *  - VŠECHNY zápisy (INSERT, UPDATE) probíhají ASYNCHRONNĚ přes Bukkit scheduler.
 *  - ČTENÍ pro Leaderboard (getTopPlayers) probíhá ASYNCHRONNĚ, výsledek se
 *    doručí callback funkcí (Consumer) zpět do libovolného vlákna.
 *  - Synchronní čtení (getPlayerElo) je záměrně ponecháno pro interní použití
 *    v EloManageru (volá se jen při async úloze).
 */
public class DatabaseManager {

    private static final String DB_FILE    = "database.db";
    private static final String TABLE_PLAYERS = "players";

    // SQL příkazy
    private static final String SQL_CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_PLAYERS + " (" +
                    "  uuid TEXT PRIMARY KEY NOT NULL," +
                    "  name TEXT NOT NULL," +
                    "  elo  INTEGER NOT NULL DEFAULT 1000" +
                    ");";

    private static final String SQL_UPSERT =
            "INSERT INTO " + TABLE_PLAYERS + " (uuid, name, elo) VALUES (?, ?, ?)" +
                    " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, elo = excluded.elo;";

    private static final String SQL_GET_ELO =
            "SELECT elo FROM " + TABLE_PLAYERS + " WHERE uuid = ?;";

    private static final String SQL_TOP_PLAYERS =
            "SELECT name, elo FROM " + TABLE_PLAYERS + " ORDER BY elo DESC LIMIT ?;";

    private final ChromaWars plugin;
    private Connection connection;

    public DatabaseManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── Inicializace ──────────────────────────────────────────────────────────

    /**
     * Připojí se k SQLite databázi a vytvoří tabulky.
     * Musí být voláno synchronně při startu pluginu.
     *
     * @return true pokud se inicializace podařila
     */
    public boolean initialize() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().severe("Nepodařilo se vytvořit složku pluginu!");
            return false;
        }

        File dbFile = new File(dataFolder, DB_FILE);
        try {
            // SQLite JDBC driver je součástí Paper serveru (bundled)
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Optimalizace výkonu SQLite
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA cache_size=2000;");
                stmt.execute("PRAGMA foreign_keys=ON;");
            }

            // Vytvoření tabulek
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(SQL_CREATE_TABLE);
            }

            plugin.getLogger().info("SQLite databáze inicializována: " + dbFile.getName());
            return true;

        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite JDBC driver nenalezen!", e);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Chyba při inicializaci databáze!", e);
        }
        return false;
    }

    /**
     * Bezpečně uzavře připojení k databázi.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("Databázové připojení uzavřeno.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Chyba při zavírání databáze.", e);
            }
        }
    }

    // ── Zápis (asynchronní) ───────────────────────────────────────────────────

    /**
     * Asynchronně uloží nebo aktualizuje hráčův záznam (ELO).
     * Bezpečné volat z hlavního vlákna.
     */
    public void savePlayerAsync(UUID uuid, String name, int elo) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = connection.prepareStatement(SQL_UPSERT)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setInt(3, elo);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Chyba při ukládání hráče " + name + " do DB.", e);
            }
        });
    }

    // ── Čtení (synchronní – volat POUZE z async vlákna!) ─────────────────────

    /**
     * Synchronně načte ELO hráče z DB.
     * ⚠ Volat POUZE z asynchronního vlákna (Bukkit async task).
     *
     * @return ELO hráče, nebo defaultElo pokud hráč v DB není
     */
    public int getPlayerEloSync(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(SQL_GET_ELO)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("elo");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Chyba při čtení ELO pro UUID " + uuid, e);
        }
        return plugin.getConfigManager().getDefaultElo();
    }

    // ── Leaderboard dotaz ─────────────────────────────────────────────────────

    /**
     * Asynchronně načte TOP N hráčů z DB a předá výsledek callbacku.
     * Callback je vyvolán ve stejném ASYNC vlákně – pokud potřebuješ
     * pracovat s Bukkit API, wrappuj v runTask().
     *
     * @param limit    počet hráčů (např. 100)
     * @param callback funkce přijímající List<LeaderboardEntry>
     */
    public void getTopPlayersAsync(int limit, Consumer<List<LeaderboardEntry>> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LeaderboardEntry> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SQL_TOP_PLAYERS)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    int position = 1;
                    while (rs.next()) {
                        result.add(new LeaderboardEntry(
                                position++,
                                rs.getString("name"),
                                rs.getInt("elo")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Chyba při načítání top hráčů z DB.", e);
            }
            callback.accept(result);
        });
    }
}