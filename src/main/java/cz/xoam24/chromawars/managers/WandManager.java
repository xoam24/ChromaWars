package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Spravuje "Chroma Wand" pro výběr pos1/pos2 arény.
 *
 * Edit session:
 *   Admin zavolá /chroma arena edit <name> → hráč dostane Wand a je registrován
 *   v editSessions. L-klik = pos1, P-klik = pos2 (na blok).
 *   /chroma arena save uloží data a ukončí session.
 */
public class WandManager implements Listener {

    private static final Material WAND_MATERIAL = Material.BLAZE_ROD;
    private final NamespacedKey WAND_KEY;

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // UUID hráče → název arény (Edit session)
    private final Map<UUID, String>  editSessions = new HashMap<>();
    // UUID hráče → pos1 [x,y,z]
    private final Map<UUID, int[]>   pos1Map      = new HashMap<>();
    // UUID hráče → pos2 [x,y,z]
    private final Map<UUID, int[]>   pos2Map      = new HashMap<>();

    public WandManager(ChromaWars plugin) {
        this.plugin  = plugin;
        this.WAND_KEY = new NamespacedKey(plugin, "chroma_wand");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ── Dání Wandu hráči ──────────────────────────────────────────────────────

    public void giveWand(Player player) {
        ItemStack wand = new ItemStack(WAND_MATERIAL);
        ItemMeta  meta = wand.getItemMeta();

        // Název z messages.yml
        String rawName = plugin.getMessageManager().getRaw("wand.item-name");
        meta.displayName(mm.deserialize(rawName));

        // Lore z messages.yml
        List<String> rawLore = plugin.getConfigManager().getRawConfig()
                .getStringList("wand.item-lore");  // přečteme ze správného souboru
        // Lore je v messages.yml, načteme přes MessageManager
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getMessageManager().getRaw("wand.item-lore").lines().toList()) {
            lore.add(mm.deserialize(line));
        }
        // Pokud messages vrátí prázdný řetězec (nová syntaxe), použijeme fallback
        if (lore.isEmpty() || (lore.size() == 1 && lore.get(0).equals(Component.empty()))) {
            lore = List.of(
                    mm.deserialize("&#AAAAAAL-klik = <white>Pos1</white>"),
                    mm.deserialize("&#AAAAAAR-klik = <white>Pos2</white>")
            );
        }
        meta.lore(lore);

        // PDC tag – identifikace Wandu
        meta.getPersistentDataContainer().set(WAND_KEY, PersistentDataType.BYTE, (byte) 1);
        meta.setUnbreakable(true);
        wand.setItemMeta(meta);

        player.getInventory().addItem(wand);
    }

    // ── Edit session správa ───────────────────────────────────────────────────

    public void startEditSession(Player player, String arenaName) {
        editSessions.put(player.getUniqueId(), arenaName);
        pos1Map.remove(player.getUniqueId());
        pos2Map.remove(player.getUniqueId());
    }

    public void endEditSession(Player player) {
        UUID uid = player.getUniqueId();
        editSessions.remove(uid);
        pos1Map.remove(uid);
        pos2Map.remove(uid);
        // Odebereme Wand z inventáře
        removeWandFromInventory(player);
    }

    private void removeWandFromInventory(Player player) {
        player.getInventory().forEach(item -> {
            if (isWand(item)) player.getInventory().remove(item);
        });
    }

    // ── Gettery ───────────────────────────────────────────────────────────────

    public boolean isInEditSession(Player player) {
        return editSessions.containsKey(player.getUniqueId());
    }

    public String getEditArena(Player player) {
        return editSessions.get(player.getUniqueId());
    }

    public boolean hasPos1(Player player) { return pos1Map.containsKey(player.getUniqueId()); }
    public boolean hasPos2(Player player) { return pos2Map.containsKey(player.getUniqueId()); }
    public int[]   getPos1(Player player) { return pos1Map.get(player.getUniqueId()); }
    public int[]   getPos2(Player player) { return pos2Map.get(player.getUniqueId()); }

    // ── Detekce Wandu ─────────────────────────────────────────────────────────

    public boolean isWand(ItemStack item) {
        if (item == null || item.getType() != WAND_MATERIAL) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(WAND_KEY, PersistentDataType.BYTE);
    }

    // ── Události ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Ignorujeme vedlejší ruku
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (!isWand(item)) return;

        // Kliknutí musí být na blok
        if (event.getClickedBlock() == null) return;
        event.setCancelled(true);

        Block clicked = event.getClickedBlock();
        int x = clicked.getX();
        int y = clicked.getY();
        int z = clicked.getZ();
        UUID uid = player.getUniqueId();

        // Hráč musí být v edit session pro ukládání pozic
        if (!isInEditSession(player)) {
            player.sendActionBar(mm.deserialize("<red>Nejsi v edit session! Použij /chroma arena edit <name>."));
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            pos1Map.put(uid, new int[]{x, y, z});
            String msg = plugin.getMessageManager().getRaw("wand.pos1-action-bar")
                    .replace("<x>", String.valueOf(x))
                    .replace("<y>", String.valueOf(y))
                    .replace("<z>", String.valueOf(z));
            player.sendActionBar(mm.deserialize(msg));

        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            pos2Map.put(uid, new int[]{x, y, z});
            String msg = plugin.getMessageManager().getRaw("wand.pos2-action-bar")
                    .replace("<x>", String.valueOf(x))
                    .replace("<y>", String.valueOf(y))
                    .replace("<z>", String.valueOf(z));
            player.sendActionBar(mm.deserialize(msg));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Uklidíme data při odpojení (session se ztratí, ale to je OK)
        UUID uid = event.getPlayer().getUniqueId();
        editSessions.remove(uid);
        pos1Map.remove(uid);
        pos2Map.remove(uid);
    }
}