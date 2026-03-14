package cz.xoam24.chromawars.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Neměnný objekt arény načtený z configu.
 */
public record ArenaData(
        String name,
        String worldName,
        int x1, int y1, int z1,
        int x2, int y2, int z2
) {
    /** Minimální X/Y/Z rohový bod arény. */
    public int minX() { return Math.min(x1, x2); }
    public int minY() { return Math.min(y1, y2); }
    public int minZ() { return Math.min(z1, z2); }

    /** Maximální X/Y/Z rohový bod arény. */
    public int maxX() { return Math.max(x1, x2); }
    public int maxY() { return Math.max(y1, y2); }
    public int maxZ() { return Math.max(z1, z2); }

    /** Celkový počet bloků arény. */
    public int totalBlocks() {
        return (maxX() - minX() + 1)
                * (maxY() - minY() + 1)
                * (maxZ() - minZ() + 1);
    }

    /** Vrátí World objekt. Může být null pokud svět není načten. */
    public World getWorld() {
        return Bukkit.getWorld(worldName);
    }

    /** Vrátí střed arény jako Location (pro teleportaci). */
    public Location getCenter() {
        World w = getWorld();
        return new Location(w,
                (minX() + maxX()) / 2.0 + 0.5,
                minY(),
                (minZ() + maxZ()) / 2.0 + 0.5);
    }
}