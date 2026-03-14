package cz.xoam24.chromawars;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EloManager {

    private final ChromaWars plugin;
    private final Map<UUID, Integer> eloCache = new HashMap<>();

    // Varování z IntelliJ vyřešeno - přidáno final
    private final Map<UUID, Integer> cachedTopPlayers = new HashMap<>();

    public EloManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    public int getElo(Player player) {
        return eloCache.getOrDefault(player.getUniqueId(), plugin.getConfig().getInt("settings.default-elo", 1000));
    }

    // Tyto metody hlásily warning, že se nepoužívají. Teď mají jasnou funkci.
    public void addElo(Player player, int amount) {
        int currentElo = getElo(player);
        int newElo = currentElo + amount;
        eloCache.put(player.getUniqueId(), newElo);
        saveEloAsync(player.getUniqueId(), player.getName(), newElo);
    }

    public void resetElo(Player player) {
        int defaultElo = plugin.getConfig().getInt("settings.default-elo", 1000);
        eloCache.put(player.getUniqueId(), defaultElo);
        saveEloAsync(player.getUniqueId(), player.getName(), defaultElo);
    }

    // OPRAVA CHYBY v GameManageru: Metoda musí být PUBLIC, aby ji GameManager mohl zavolat.
    public void saveEloAsync(UUID uuid, String name, int elo) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Zde bude tvá logika pro uložení do databáze (DatabaseManager)
            // plugin.getDatabaseManager().updateElo(uuid, elo);
        });
    }
}