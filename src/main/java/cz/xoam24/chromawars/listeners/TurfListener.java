package cz.xoam24.chromawars.listeners;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.managers.GameManager;
import cz.xoam24.chromawars.managers.SessionManager;
import cz.xoam24.chromawars.model.ArenaData;
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
 * Optimalizované barvení bloků při pohybu hráče.
 *
 * Optimalizace:
 *  1. Kontrola změny bloku (blockX/Y/Z) – ignorujeme pohyby myší.
 *  2. setType() jen pokud blok SKUTEČNĚ potřebuje změnu.
 *  3. Počítadla aktualizujeme přímo v GameManageru (O(1), žádný scan).
 *  4. lastBlockCache eliminuje duplicitní zpracování na stejném bloku.
 */
public class TurfListener implements Listener {

    private final ChromaWars plugin;

    // UUID → packed long (blockX << 40 | blockY << 20 | blockZ) pro cache
    private final Map<UUID, long[]> lastBlockCache = new ConcurrentHashMap<>();

    public TurfListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // ── 1. Rychlá kontrola blokové změny ─────────────────────────────────
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return; // pohyb myší bez změny bloku
        }

        // ── 2. Hra musí běžet ─────────────────────────────────────────────────
        GameManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;

        Player player = event.getPlayer();
        UUID   uid    = player.getUniqueId();

        // ── 3. Hráč musí být ve stavu PLAYING ────────────────────────────────
        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return;

        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        String teamId = session.getTeamId();
        if (teamId == null) return;

        // ── 4. Souřadnice nového bloku ────────────────────────────────────────
        int newBX = event.getTo().getBlockX();
        int newBY = event.getTo().getBlockY();
        int newBZ = event.getTo().getBlockZ();

        // ── 5. Cache – jsme pořád na stejném bloku? ───────────────────────────
        long[] last = lastBlockCache.get(uid);
        if (last != null && last[0] == newBX && last[1] == newBY && last[2] == newBZ) {
            return;
        }
        lastBlockCache.put(uid, new long[]{newBX, newBY, newBZ});

        // ── 6. Hráč musí být uvnitř hranic arény ─────────────────────────────
        ArenaData arena = gm.getCurrentArena();
        if (arena == null) return;

        if (newBX < arena.minX() || newBX > arena.maxX()
                || newBY < arena.minY() || newBY > arena.maxY()
                || newBZ < arena.minZ() || newBZ > arena.maxZ()) {
            return;
        }

        // ── 7. Blok POD hráčem (Y-1) ─────────────────────────────────────────
        Block below = player.getWorld().getBlockAt(newBX, newBY - 1, newBZ);
        if (!isConcrete(below.getType())) return;

        // ── 8. Materiál týmu ──────────────────────────────────────────────────
        TeamConfig team = plugin.getConfigManager().getTeam(teamId);
        if (team == null) return;
        Material teamMaterial = team.block();

        // ── 9. Blok už patří tomuto týmu – nic nedělat ───────────────────────
        if (below.getType() == teamMaterial) return;

        // ── 10. Detekce přebarvení – zjistíme původní tým ────────────────────
        String previousTeamId = getTeamIdByMaterial(below.getType());

        // ── 11. Nastavíme blok (false = bez physics update) ───────────────────
        below.setType(teamMaterial, false);

        // ── 12. Aktualizace počítadel v GameManageru ─────────────────────────
        gm.incrementTeamBlock(teamId);

        // Pokud byl blok barevný (cizí tým), odečteme původnímu
        if (previousTeamId != null) {
            gm.decrementTeamBlock(previousTeamId);
        }
        // WHITE_CONCRETE vrátí null → žádný decrement
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isConcrete(Material material) {
        String name = material.name();
        return name.endsWith("_CONCRETE");
    }

    /**
     * Vrátí teamId podle materiálu, nebo null pro WHITE_CONCRETE / neznámý.
     */
    private String getTeamIdByMaterial(Material material) {
        if (material == Material.WHITE_CONCRETE) return null;
        for (Map.Entry<String, TeamConfig> entry
                : plugin.getConfigManager().getTeams().entrySet()) {
            if (entry.getValue().block() == material) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void clearPlayerCache(UUID uuid) {
        lastBlockCache.remove(uuid);
    }

    public void clearAllCaches() {
        lastBlockCache.clear();
    }
}