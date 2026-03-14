package cz.xoam24.chromawars.listeners;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.PlayerSession;
import cz.xoam24.chromawars.model.TeamConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obarvování bloků při pohybu hráče – jádro Turf Wars mechaniky.
 *
 * Optimalizace:
 *  1. Kontrolujeme POUZE změnu bloku (blockX/Y/Z), ne každý pohyb myší.
 *  2. Setblock se volá jen pokud se blok pod hráčem SKUTEČNĚ liší od barvy týmu.
 *  3. Počítadla bloků se aktualizují přímo v GameManageru – žádný scan arény.
 *  4. Cache posledního bloku (UUID → [x,y,z]) zabrání duplicitním voláním
 *     při pohybu v rámci stejného bloku.
 */
public class TurfListener implements Listener {

    private final ChromaWars plugin;

    // Cache posledního bloku hráče: UUID → [blockX, blockY, blockZ]
    // Zabrání duplicitnímu obarvování na stejném bloku
    private final Map<UUID, long[]> lastBlockCache = new ConcurrentHashMap<>();

    public TurfListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // ── 1. Rychlá kontrola – hra musí běžet ──────────────────────────────
        GameManager gm = plugin.<GameManager>getGameManager();
        if (gm == null || !gm.isRunning()) return;

        Player player = event.getPlayer();
        UUID   uid    = player.getUniqueId();

        // ── 2. Hráč musí být ve hře (ne spectator) ───────────────────────────
        SessionManager sm      = plugin.<SessionManager>getSessionManager();
        PlayerSession  session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        String teamId = session.getTeamId();
        if (teamId == null) return;

        // ── 3. Kontrola ZMĚNY BLOKU (ne každý pohyb) ────────────────────────
        int newBX = event.getTo().getBlockX();
        int newBY = event.getTo().getBlockY();
        int newBZ = event.getTo().getBlockZ();

        long[] last = lastBlockCache.get(uid);
        if (last != null && last[0] == newBX && last[1] == newBY && last[2] == newBZ) {
            return; // Hráč je na stejném bloku – nic neděláme
        }
        lastBlockCache.put(uid, new long[]{newBX, newBY, newBZ});

        // ── 4. Musíme být uvnitř arény ───────────────────────────────────────
        var arena = gm.getCurrentArena();
        if (arena == null) return;
        if (newBX < arena.minX() || newBX > arena.maxX()
                || newBY < arena.minY() || newBY > arena.maxY()
                || newBZ < arena.minZ() || newBZ > arena.maxZ()) return;

        // ── 5. Blok POD hráčem (Y-1) ─────────────────────────────────────────
        Block below = player.getWorld().getBlockAt(newBX, newBY - 1, newBZ);

        // Blok musí být CONCRETE (jakékoliv barvy nebo WHITE) – ne jiný materiál
        if (!isConcrete(below.getType())) return;

        // ── 6. Zjistíme materiál týmu ─────────────────────────────────────────
        TeamConfig team = plugin.getConfigManager().getTeam(teamId);
        if (team == null) return;
        Material teamMaterial = team.block();

        // ── 7. Pokud blok již patří tomuto týmu – přeskočíme ─────────────────
        if (below.getType() == teamMaterial) return;

        // ── 8. Obarvení – detekujeme přebarvení cizího bloku ─────────────────
        Material previousMaterial = below.getType();
        String   previousTeamId   = getTeamIdByMaterial(previousMaterial);

        // Nastavíme nový blok (false = bez physics update pro výkon)
        below.setType(teamMaterial, false);

        // ── 9. Aktualizace počítadel v GameManageru (O(1)) ──────────────────
        gm.incrementTeamBlock(teamId);

        if (previousTeamId != null && !previousTeamId.equals("white")) {
            // Přebarvení cizího barevného bloku – odečteme od původního týmu
            gm.decrementTeamBlock(previousTeamId);
        }
        // WHITE_CONCRETE se nepočítá nikomu – žádný decrement
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Vrátí true pokud je materiál libovolný CONCRETE.
     */
    private boolean isConcrete(Material material) {
        String name = material.name();
        return name.endsWith("_CONCRETE") || name.equals("WHITE_CONCRETE");
    }

    /**
     * Vrátí teamId podle materiálu bloku, nebo "white" pro WHITE_CONCRETE,
     * nebo null pokud materiál neodpovídá žádnému týmu.
     */
    private String getTeamIdByMaterial(Material material) {
        if (material == Material.WHITE_CONCRETE) return "white";
        for (Map.Entry<String, TeamConfig> entry
                : plugin.getConfigManager().getTeams().entrySet()) {
            if (entry.getValue().block() == material) return entry.getKey();
        }
        return null;
    }

    /**
     * Vyčistí cache hráče při odpojení nebo konci hry.
     * Volá GameManager.cleanupAfterGame() nebo CombatListener.
     */
    public void clearPlayerCache(UUID uuid) {
        lastBlockCache.remove(uuid);
    }

    public void clearAllCaches() {
        lastBlockCache.clear();
    }
}