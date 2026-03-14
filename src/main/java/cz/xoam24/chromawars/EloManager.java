package cz.xoam24.chromawars;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class EloManager {

    private final ChromaWars plugin;
    private List<LeaderboardEntry> cachedTopPlayers;

    // Record objekt pro ukládání do cache (Java 16+)
    public record LeaderboardEntry(String name, int elo) {}

    public EloManager(ChromaWars plugin) {
        this.plugin = plugin;
        this.cachedTopPlayers = Collections.synchronizedList(new ArrayList<>());
    }

    // --- LEADERBOARD CACHE ---

    public void updateLeaderboardCache() {
        plugin.getLogger().info("Aktualizuji Leaderboard Cache...");
        // Asynchronně vytáhne TOP 100 hráčů
        plugin.getDatabaseManager().getTopPlayers(100).thenAccept(topPlayers -> {
            cachedTopPlayers.clear();
            cachedTopPlayers.addAll(topPlayers);
        });
    }

    public String getNameAtPosition(int position) {
        int index = position - 1;
        if (index >= 0 && index < cachedTopPlayers.size()) {
            return cachedTopPlayers.get(index).name();
        }
        return "Nikdo";
    }

    public String getEloAtPosition(int position) {
        int index = position - 1;
        if (index >= 0 && index < cachedTopPlayers.size()) {
            return String.valueOf(cachedTopPlayers.get(index).elo());
        }
        return "0";
    }

    public int getPlayerPosition(String playerName) {
        for (int i = 0; i < cachedTopPlayers.size(); i++) {
            if (cachedTopPlayers.get(i).name().equalsIgnoreCase(playerName)) {
                return i + 1;
            }
        }
        return -1; // Nenalezen
    }

    // --- ELO ÚPRAVY ---

    public void setElo(Player player, int newElo) {
        saveEloAsync(player.getUniqueId(), player.getName(), newElo);
    }

    public void addElo(Player player, int amount) {
        // V praxi zde nejdříve vytáhneme současné ELO hráče (pro zjednodušení v Kroku 1 rovnou ukládáme)
        // V pozdějším kroku (PlayerSession) budeme držet ELO v RAM a do DB ukládat jen upravené číslo.
        // Simulujeme nyní set.
    }

    public void resetElo(Player player) {
        int defaultElo = plugin.getConfigManager().getDefaultElo();
        setElo(player, defaultElo);
    }

    private void saveEloAsync(UUID uuid, String name, int elo) {
        plugin.getDatabaseManager().savePlayerElo(uuid, name, elo);
    }
}