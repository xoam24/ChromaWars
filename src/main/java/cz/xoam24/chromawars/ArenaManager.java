package cz.xoam24.chromawars;

import org.bukkit.Location;
import java.util.*;

public class ArenaManager {

    private final ChromaWars plugin;
    private final Set<String> loadedArenas = new HashSet<>();
    private final Map<String, Integer> arenaVotes = new HashMap<>();
    private final Map<UUID, String> playerVotes = new HashMap<>();

    public ArenaManager(ChromaWars plugin) {
        this.plugin = plugin;
        loadArenas();
    }

    public void loadArenas() {
        loadedArenas.clear();
        arenaVotes.clear();
        if (plugin.getConfigManager().getConfig().contains("arenas")) {
            loadedArenas.addAll(plugin.getConfigManager().getConfig().getConfigurationSection("arenas").getKeys(false));
            for (String arena : loadedArenas) {
                arenaVotes.put(arena, 0);
            }
        }
        plugin.getLogger().info("Nacteno aren: " + loadedArenas.size());
    }

    public boolean createArena(String name, String world) {
        if (loadedArenas.contains(name)) return false;
        plugin.getConfigManager().getConfig().set("arenas." + name + ".world", world);
        plugin.getConfigManager().getConfig().save(plugin.getDataFolder() + "/config.yml");
        loadedArenas.add(name);
        arenaVotes.put(name, 0);
        return true;
    }

    public boolean saveArenaPositions(String name, Location pos1, Location pos2) {
        if (!loadedArenas.contains(name)) return false;
        String path = "arenas." + name;
        plugin.getConfigManager().getConfig().set(path + ".pos1", serializeLocation(pos1));
        plugin.getConfigManager().getConfig().set(path + ".pos2", serializeLocation(pos2));
        plugin.saveConfig();
        return true;
    }

    public boolean deleteArena(String name) {
        if (!loadedArenas.contains(name)) return false;
        plugin.getConfigManager().getConfig().set("arenas." + name, null);
        plugin.saveConfig();
        loadedArenas.remove(name);
        arenaVotes.remove(name);
        return true;
    }

    public void castVote(UUID playerUuid, String arenaName) {
        if (playerVotes.containsKey(playerUuid)) return; // Hráč už hlasoval
        if (!loadedArenas.contains(arenaName)) return;

        playerVotes.put(playerUuid, arenaName);
        arenaVotes.put(arenaName, arenaVotes.getOrDefault(arenaName, 0) + 1);
    }

    public String getWinningArena() {
        if (arenaVotes.isEmpty()) return null;
        return Collections.max(arenaVotes.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    public void resetVotes() {
        playerVotes.clear();
        for (String arena : loadedArenas) arenaVotes.put(arena, 0);
    }

    public Set<String> getArenas() { return loadedArenas; }

    private String serializeLocation(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}