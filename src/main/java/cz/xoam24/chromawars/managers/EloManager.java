package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.LeaderboardEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * EloManager – výpočet ELO a správa Leaderboard Cache.
 *
 * Leaderboard Cache:
 *  - cachedTopPlayers je CopyOnWriteArrayList, bezpečné pro čtení z hlavního vlákna.
 *  - updateLeaderboardCacheAsync() je VŽDY asynchronní (DB dotaz) a atomicky
 *    nahradí celý list v hlavním vlákně po dokončení.
 *  - Cache se NIKDY nedotazuje DB při čtení – gettery jsou O(1) / O(n) z paměti.
 */
public class EloManager {

    // ── Cache – thread-safe read, atomic replace ─────────────────────────────
    // CopyOnWriteArrayList: čtení z libovolného vlákna bez synchronizace
    private volatile List<LeaderboardEntry> cachedTopPlayers = new CopyOnWriteArrayList<>();

    private final ChromaWars plugin;

    public EloManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    // ── ELO operace ───────────────────────────────────────────────────────────

    /**
     * Přidá hodnotu k ELU online nebo offline hráče.
     * Asynchronní – bezpečné volat z hlavního vlákna.
     */
    public void addElo(OfflinePlayer player, int amount) {
        modifyElo(player, current -> Math.max(0, current + amount));
    }

    /**
     * Odebere hodnotu z ELA (minimum 0).
     */
    public void removeElo(OfflinePlayer player, int amount) {
        modifyElo(player, current -> Math.max(0, current - amount));
    }

    /**
     * Nastaví ELO hráče na přesnou hodnotu.
     */
    public void setElo(OfflinePlayer player, int value) {
        modifyElo(player, current -> Math.max(0, value));
    }

    /**
     * Resetuje ELO hráče na výchozí hodnotu z configu.
     */
    public void resetElo(OfflinePlayer player) {
        int defaultElo = plugin.getConfigManager().getDefaultElo();
        modifyElo(player, current -> defaultElo);
    }

    /**
     * Interní metoda pro modifikaci ELO.
     * 1. Asynchronně načte aktuální ELO z DB
     * 2. Aplikuje transformaci
     * 3. Asynchronně uloží zpět
     *
     * @param player      cílový hráč
     * @param transformer funkce (int -> int) pro výpočet nového ELO
     */
    private void modifyElo(OfflinePlayer player, java.util.function.IntUnaryOperator transformer) {
        if (player == null || player.getUniqueId() == null) return;

        final UUID uuid = player.getUniqueId();
        final String name = player.getName() != null ? player.getName() : "Unknown";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager db = plugin.getDatabaseManager();
            int current = db.getPlayerEloSync(uuid);
            int newElo = transformer.applyAsInt(current);
            db.savePlayerAsync(uuid, name, newElo);
        });
    }

    // ── Leaderboard Cache ─────────────────────────────────────────────────────

    /**
     * Asynchronně aktualizuje Leaderboard Cache z DB.
     * VOLAT:
     *   1) Při spuštění pluginu (ChromaWars.onEnable)
     *   2) Na konci každého zápasu (GameManager.endGame)
     *
     * Po dokončení DB dotazu se cache atomicky nahradí v HLAVNÍM vlákně.
     */
    public void updateLeaderboardCacheAsync() {
        plugin.getDatabaseManager().getTopPlayersAsync(100, newEntries -> {
            // Přepneme zpět do hlavního vlákna pro atomickou výměnu reference
            Bukkit.getScheduler().runTask(plugin, () -> {
                cachedTopPlayers = new CopyOnWriteArrayList<>(newEntries);
                plugin.getLogger().info("Leaderboard cache aktualizována: " + newEntries.size() + " hráčů.");
            });
        });
    }

    // ── Gettery z cache (O(1) / O(n) – BEZ DB dotazu) ────────────────────────

    /**
     * Vrátí jméno hráče na dané pozici v žebříčku.
     *
     * @param position 1-based pozice (1 = první místo)
     * @return jméno hráče, nebo "Nikdo" pokud pozice neexistuje
     */
    public String getNameAtPosition(int position) {
        if (position < 1 || position > cachedTopPlayers.size()) return "Nikdo";
        return cachedTopPlayers.get(position - 1).name();
    }

    /**
     * Vrátí ELO hráče na dané pozici v žebříčku.
     *
     * @param position 1-based pozice
     * @return ELO, nebo 0 pokud pozice neexistuje
     */
    public int getEloAtPosition(int position) {
        if (position < 1 || position > cachedTopPlayers.size()) return 0;
        return cachedTopPlayers.get(position - 1).elo();
    }

    /**
     * Zjistí pořadí hráče v žebříčku podle jeho jména.
     * Prochází cache lineárně – O(n). Cache má max 100 záznamů.
     *
     * @param playerName přesné herní jméno (case-insensitive)
     * @return 1-based pozice, nebo -1 pokud hráč v TOP 100 není
     */
    public int getPositionOfPlayer(String playerName) {
        if (playerName == null) return -1;
        for (LeaderboardEntry entry : cachedTopPlayers) {
            if (entry.name().equalsIgnoreCase(playerName)) {
                return entry.position();
            }
        }
        return -1;
    }

    /**
     * Zjistí ELO online hráče (asynchronně z DB, výsledek přes callback).
     * Používej pro zobrazení ELO konkrétního hráče (ne pro leaderboard).
     */
    public void getPlayerEloAsync(UUID uuid, java.util.function.IntConsumer callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int elo = plugin.getDatabaseManager().getPlayerEloSync(uuid);
            // Vrátíme výsledek do hlavního vlákna
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(elo));
        });
    }

    /**
     * Vrátí kopii celé cache pro iteraci (např. pro generování scoreboard tabulky).
     * Bezpečné volat z hlavního vlákna.
     */
    public List<LeaderboardEntry> getCachedTopPlayers() {
        return Collections.unmodifiableList(cachedTopPlayers);
    }

    /**
     * Vrátí počet hráčů v cache.
     */
    public int getCacheSize() {
        return cachedTopPlayers.size();
    }
}