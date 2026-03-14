package cz.xoam24.chromawars;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {

    private final ChromaWars plugin;
    private Connection connection;

    public DatabaseManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        try {
            File dataFolder = new File(plugin.getDataFolder(), "database.db");
            if (!dataFolder.exists()) {
                plugin.getDataFolder().mkdirs();
            }
            String url = "jdbc:sqlite:" + dataFolder;
            connection = DriverManager.getConnection(url);
        } catch (SQLException e) {
            plugin.getLogger().severe("Nepodarilo se pripojit k databazi: " + e.getMessage());
        }
    }

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void createTables() {
        String sql = "CREATE TABLE IF NOT EXISTS players (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(16) NOT NULL, " +
                "elo INTEGER NOT NULL" +
                ");";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public CompletableFuture<Void> savePlayerElo(UUID uuid, String name, int elo) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO players (uuid, name, elo) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name);
                pstmt.setInt(3, elo);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public CompletableFuture<List<EloManager.LeaderboardEntry>> getTopPlayers(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<EloManager.LeaderboardEntry> topPlayers = new ArrayList<>();
            String sql = "SELECT name, elo FROM players ORDER BY elo DESC LIMIT ?";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, limit);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    topPlayers.add(new EloManager.LeaderboardEntry(
                            rs.getString("name"),
                            rs.getInt("elo")
                    ));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return topPlayers;
        });
    }
}