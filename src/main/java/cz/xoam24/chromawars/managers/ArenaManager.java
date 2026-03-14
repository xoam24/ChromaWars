package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.ArenaData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Načítá arény z configu a spravuje hlasovací systém (/votemap).
 *
 * Voting systém:
 *   - Každý hráč v lobby může hlasovat jen jednou (ale hlas může změnit).
 *   - Po každé hře se hlasy resetují.
 *   - getVotingWinner() vrátí mapu s nejvíce hlasy; při shodě náhodně vybere.
 */
public class ArenaManager {

    private final ChromaWars plugin;

    // Načtené arény z configu
    private final Map<String, ArenaData> arenas = new LinkedHashMap<>();

    // Hlasování: UUID hráče → název arény
    private final Map<UUID, String> votes = new HashMap<>();

    public ArenaManager(ChromaWars plugin) {
        this.plugin = plugin;
        loadArenas();
    }

    // ── Načtení arén z configu ────────────────────────────────────────────────

    public void loadArenas() {
        arenas.clear();
        FileConfiguration cfg = plugin.getConfigManager().getRawConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("arenas");
        if (sec == null) return;

        for (String name : sec.getKeys(false)) {
            ConfigurationSection a = sec.getConfigurationSection(name);
            if (a == null) continue;

            String worldName = a.getString("world", "world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Aréna '" + name + "' odkazuje na neznámý svět '" + worldName + "'!");
            }

            int x1 = a.getInt("pos1.x"), y1 = a.getInt("pos1.y"), z1 = a.getInt("pos1.z");
            int x2 = a.getInt("pos2.x"), y2 = a.getInt("pos2.y"), z2 = a.getInt("pos2.z");

            arenas.put(name, new ArenaData(name, worldName, x1, y1, z1, x2, y2, z2));
        }

        plugin.getLogger().info("Načteno " + arenas.size() + " arén.");
    }

    // ── Gettery arén ─────────────────────────────────────────────────────────

    public boolean arenaExists(String name)       { return arenas.containsKey(name); }
    public ArenaData getArena(String name)         { return arenas.get(name); }
    public Collection<ArenaData> getAllArenas()    { return Collections.unmodifiableCollection(arenas.values()); }
    public Set<String> getArenaNames()            { return Collections.unmodifiableSet(arenas.keySet()); }

    // ── Hlasování ─────────────────────────────────────────────────────────────

    /**
     * Zaregistruje nebo změní hlas hráče.
     *
     * @return true  = nový hlas
     *         false = hlas změněn (hráč již hlasoval)
     */
    public boolean vote(UUID playerUuid, String arenaName) {
        boolean alreadyVoted = votes.containsKey(playerUuid);
        votes.put(playerUuid, arenaName);
        return !alreadyVoted;
    }

    /**
     * Vrátí název arény, pro kterou hráč hlasoval, nebo null.
     */
    public String getPlayerVote(UUID playerUuid) {
        return votes.get(playerUuid);
    }

    /**
     * Vrátí počet hlasů pro danou arénu.
     */
    public int getVoteCount(String arenaName) {
        int count = 0;
        for (String voted : votes.values()) {
            if (voted.equals(arenaName)) count++;
        }
        return count;
    }

    /**
     * Vrátí celkový počet hlasů.
     */
    public int getTotalVotes() {
        return votes.size();
    }

    /**
     * Vrátí vítěznou arénu podle hlasování.
     * Při shodě náhodně vybere ze shodujících se arén.
     * Pokud nikdo nehlasoval, vrátí náhodnou arénu.
     */
    public ArenaData getVotingWinner() {
        if (arenas.isEmpty()) return null;
        if (votes.isEmpty()) {
            // Nikdo nehlasoval – náhodná aréna
            List<ArenaData> all = new ArrayList<>(arenas.values());
            return all.get(new Random().nextInt(all.size()));
        }

        // Spočítáme hlasy pro každou arénu
        Map<String, Integer> counts = new HashMap<>();
        for (String v : votes.values()) {
            counts.merge(v, 1, Integer::sum);
        }

        int max = Collections.max(counts.values());
        List<String> winners = counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();

        String winner = winners.get(new Random().nextInt(winners.size()));
        return arenas.getOrDefault(winner,
                new ArrayList<>(arenas.values()).get(0));
    }

    /**
     * Vrátí arénu s nejvíce hlasy pro zobrazení v GUI (může se lišit od getVotingWinner).
     */
    public String getLeadingArenaName() {
        if (votes.isEmpty()) return "–";
        Map<String, Integer> counts = new HashMap<>();
        for (String v : votes.values()) counts.merge(v, 1, Integer::sum);
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("–");
    }

    /**
     * Resetuje všechny hlasy (volat na konci každé hry).
     */
    public void resetVotes() {
        votes.clear();
    }
}