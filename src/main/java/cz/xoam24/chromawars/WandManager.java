package cz.xoam24.chromawars;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WandManager implements Listener {

    private final ChromaWars plugin;
    private final Map<UUID, EditSession> activeSessions = new HashMap<>();

    public WandManager(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getMessageManager().colorize("&#FF00FFChromaWars Wand"));
            wand.setItemMeta(meta);
        }
        player.getInventory().addItem(wand);
        player.sendMessage(plugin.getMessageManager().getMessage("prefix") + "Dostal jsi ChromaWars Wand.");
    }

    public void startEditSession(Player player, String arenaName) {
        activeSessions.put(player.getUniqueId(), new EditSession(arenaName));
        player.sendMessage(plugin.getMessageManager().colorize("&aNyní upravuješ arénu: " + arenaName));
    }

    public EditSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    public void clearSession(Player player) {
        activeSessions.remove(player.getUniqueId());
    }

    @EventHandler
    public void onWandInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getDisplayName().contains("ChromaWars Wand")) return;

        EditSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        event.setCancelled(true);
        Location loc = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : player.getLocation();

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            session.setPos1(loc);
            player.sendMessage(plugin.getMessageManager().colorize("&d[ChromaWars] &fPozice 1 nastavena."));
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            session.setPos2(loc);
            player.sendMessage(plugin.getMessageManager().colorize("&d[ChromaWars] &fPozice 2 nastavena."));
        }
    }

    public static class EditSession {
        private final String arenaName;
        private Location pos1;
        private Location pos2;

        public EditSession(String arenaName) { this.arenaName = arenaName; }
        public String getArenaName() { return arenaName; }
        public Location getPos1() { return pos1; }
        public void setPos1(Location pos1) { this.pos1 = pos1; }
        public Location getPos2() { return pos2; }
        public void setPos2(Location pos2) { this.pos2 = pos2; }
    }
}