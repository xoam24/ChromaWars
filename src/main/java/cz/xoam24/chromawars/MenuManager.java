package cz.xoam24.chromawars;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class MenuManager implements Listener {

    private final ChromaWars plugin;
    private final String JOIN_MENU_TITLE = "Výběr týmu";
    private final String VOTE_MENU_TITLE = "Hlasování o mapu";

    public MenuManager(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openJoinMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, JOIN_MENU_TITLE);

        // Zjednodušená ukázka - v Kroku 3 propojíme s PlayerSession pro dynamický lore
        ItemStack redTeam = createItem(Material.RED_CONCRETE, "&cČervený tým", "&7Klikni pro připojení!");
        ItemStack blueTeam = createItem(Material.BLUE_CONCRETE, "&9Modrý tým", "&7Klikni pro připojení!");

        inv.setItem(11, redTeam);
        inv.setItem(15, blueTeam);
        player.openInventory(inv);
    }

    public void openVoteMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, VOTE_MENU_TITLE);
        int slot = 0;
        for (String arenaName : plugin.getArenaManager().getArenas()) {
            inv.setItem(slot++, createItem(Material.MAP, "&e" + arenaName, "&7Klikni pro hlasování"));
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(JOIN_MENU_TITLE)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            Player player = (Player) event.getWhoClicked();

            // Logika přiřazení do týmu se dopíše v Kroku 3 (PlayerSession)
            if (event.getCurrentItem().getType() == Material.RED_CONCRETE) {
                player.sendMessage(plugin.getMessageManager().colorize("&cPřipojil ses k červeným!"));
                player.closeInventory();
            } else if (event.getCurrentItem().getType() == Material.BLUE_CONCRETE) {
                player.sendMessage(plugin.getMessageManager().colorize("&9Připojil ses k modrým!"));
                player.closeInventory();
            }
        } else if (event.getView().getTitle().equals(VOTE_MENU_TITLE)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() != Material.MAP) return;
            Player player = (Player) event.getWhoClicked();
            String arenaName = org.bukkit.ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());

            plugin.getArenaManager().castVote(player.getUniqueId(), arenaName);
            player.sendMessage(plugin.getMessageManager().colorize("&aHlasoval jsi pro mapu: " + arenaName));
            player.closeInventory();
        }
    }

    private ItemStack createItem(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.getMessageManager().colorize(name));
        meta.setLore(java.util.Arrays.asList(plugin.getMessageManager().colorize(lore)));
        item.setItemMeta(meta);
        return item;
    }
}