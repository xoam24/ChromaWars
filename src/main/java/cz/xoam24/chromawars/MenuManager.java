package cz.xoam24.chromawars;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class MenuManager implements Listener {

    private final ChromaWars plugin;

    public MenuManager(ChromaWars plugin) {
        this.plugin = plugin;
    }

    public void openJoinMenu(Player player) {
        // Načtení z configu
        String title = color(plugin.getConfig().getString("gui.join.title", "&aViber si Arenu"));
        int size = plugin.getConfig().getInt("gui.join.size", 27);

        Inventory inv = Bukkit.createInventory(null, size, title);

        // Pro ukázku: načteme jeden item z configu
        Material mat = Material.matchMaterial(plugin.getConfig().getString("gui.join.items.random.material", "COMPASS"));
        if (mat == null) mat = Material.COMPASS;

        ItemStack randomJoin = new ItemStack(mat);
        ItemMeta meta = randomJoin.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(plugin.getConfig().getString("gui.join.items.random.name", "&eNahodna Arena")));
            List<String> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList("gui.join.items.random.lore")) {
                lore.add(color(line));
            }
            meta.setLore(lore);
            randomJoin.setItemMeta(meta);
        }

        int slot = plugin.getConfig().getInt("gui.join.items.random.slot", 13);
        inv.setItem(slot, randomJoin);

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        String title = color(plugin.getConfig().getString("gui.join.title", "&aViber si Arenu"));

        // Ochrana pro deprecated getTitle() - pro novější verze použij event.getView().getTitle()
        if (event.getView().getTitle().equals(title)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;

            // Logika kliknutí na kompas atd. Zde získáš přístup k ArenaManageru díky hlavní třídě:
            // plugin.getArenaManager().joinRandomArena(player);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}