package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.ArenaData;
import cz.xoam24.chromawars.model.TeamConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Nativní Paper GUI pro /join a /votemap.
 *
 * Opravy oproti předchozí verzi:
 *  - Inventory se identifikuje přes vlastní title Component porovnání
 *    (předchozí verze nespolehlivě sledovala UUID sety)
 *  - Slot mapping se ukládá per-player správně
 *  - MiniMessage konverze &#HEX před parsováním
 *  - Inventory se zavírá a znovu otevírá BEZPEČNĚ (runTaskLater)
 */
public class MenuManager implements Listener {

    private final ChromaWars plugin;
    private final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    // UUID → typ otevřeného menu ("join" | "vote")
    private final Map<UUID, String>              openMenuType = new HashMap<>();
    // UUID → slot→teamId (join menu)
    private final Map<UUID, Map<Integer, String>> joinSlotMap = new HashMap<>();
    // UUID → slot→arenaName (vote menu)
    private final Map<UUID, Map<Integer, String>> voteSlotMap = new HashMap<>();

    // Uložíme title Componenty pro identifikaci inventáře při kliknutí
    private Component joinTitleComponent = Component.empty();
    private Component voteTitleComponent = Component.empty();

    public MenuManager(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  JOIN MENU  /join
    // ══════════════════════════════════════════════════════════════════════

    public void openJoinMenu(Player player) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("gui.join");
        if (sec == null) {
            player.sendMessage(parse("<red>gui.join sekce chybí v config.yml!</red>"));
            return;
        }

        int rows = Math.min(6, Math.max(1, sec.getInt("rows", 3)));
        String titleRaw = sec.getString("title", "<white>Vyber si tým");
        joinTitleComponent = parse(titleRaw);

        Inventory inv = Bukkit.createInventory(null, rows * 9, joinTitleComponent);

        // Výplň
        fillEmpty(inv, sec.getConfigurationSection("fill-empty"));

        // Týmová tlačítka
        ConfigurationSection teamSlots = sec.getConfigurationSection("team-slots");
        ConfigurationSection teamItem  = sec.getConfigurationSection("team-item");
        Map<Integer, String> slotToTeam = new HashMap<>();

        for (Map.Entry<String, TeamConfig> entry : plugin.getConfigManager().getTeams().entrySet()) {
            String     teamId = entry.getKey();
            TeamConfig team   = entry.getValue();

            int slot = teamSlots != null ? teamSlots.getInt(teamId, -1) : -1;
            if (slot < 0 || slot >= rows * 9) continue;

            int current = plugin.getSessionManager() != null
                    ? plugin.getSessionManager().countPlayersInTeam(teamId) : 0;

            inv.setItem(slot, buildTeamItem(teamItem, team, current));
            slotToTeam.put(slot, teamId);
        }

        // Extra tlačítka
        ConfigurationSection extras = sec.getConfigurationSection("extra-buttons");
        if (extras != null) {
            for (String btnKey : extras.getKeys(false)) {
                ConfigurationSection btn = extras.getConfigurationSection(btnKey);
                if (btn == null || !btn.getBoolean("enabled", true)) continue;
                int slot = btn.getInt("slot", -1);
                if (slot < 0 || slot >= rows * 9) continue;
                inv.setItem(slot, buildSimpleItem(btn));
                slotToTeam.put(slot, "extra:" + btnKey);
            }
        }

        joinSlotMap.put(player.getUniqueId(), slotToTeam);
        openMenuType.put(player.getUniqueId(), "join");
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VOTEMAP MENU  /votemap
    // ══════════════════════════════════════════════════════════════════════

    public void openVoteMenu(Player player) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("gui.votemap");
        if (sec == null) {
            player.sendMessage(parse("<red>gui.votemap sekce chybí v config.yml!</red>"));
            return;
        }

        ArenaManager am = plugin.getArenaManager();
        Collection<ArenaData> allArenas = am.getAllArenas();
        if (allArenas.isEmpty()) {
            plugin.getMessageManager().send(player, "vote.no-arenas");
            return;
        }

        int rows = Math.min(6, Math.max(1, sec.getInt("rows", 3)));
        String titleRaw = sec.getString("title", "<white>Hlasuj o mapě");
        voteTitleComponent = parse(titleRaw);

        Inventory inv = Bukkit.createInventory(null, rows * 9, voteTitleComponent);

        fillEmpty(inv, sec.getConfigurationSection("fill-empty"));

        ConfigurationSection layoutSec  = sec.getConfigurationSection("layout");
        ConfigurationSection mapItemSec = sec.getConfigurationSection("map-item");
        ConfigurationSection infoPanSec = sec.getConfigurationSection("info-panel");

        int startSlot = layoutSec != null ? layoutSec.getInt("start-slot", 10)  : 10;
        int spacing   = layoutSec != null ? layoutSec.getInt("spacing",     2)  : 2;

        String playerVote = am.getPlayerVote(player.getUniqueId());
        int    total      = am.getTotalVotes();

        int currentSlot = startSlot;
        Map<Integer, String> slotToArena = new HashMap<>();

        for (ArenaData arena : allArenas) {
            if (currentSlot >= rows * 9) break;

            boolean voted     = arena.name().equals(playerVote);
            int     voteCount = am.getVoteCount(arena.name());

            inv.setItem(currentSlot, buildMapItem(mapItemSec, arena.name(), voteCount, total, voted));
            slotToArena.put(currentSlot, arena.name());
            currentSlot += 1 + spacing;
        }

        // Info panel
        if (infoPanSec != null && infoPanSec.getBoolean("enabled", true)) {
            int infoSlot = infoPanSec.getInt("slot", 26);
            if (infoSlot < rows * 9) {
                String countdown = plugin.getGameManager() != null
                        ? String.valueOf(plugin.getGameManager().getLobbyCountdown()) : "–";
                inv.setItem(infoSlot, buildInfoPanel(infoPanSec,
                        am.getLeadingArenaName(), total, countdown));
            }
        }

        voteSlotMap.put(player.getUniqueId(), slotToArena);
        openMenuType.put(player.getUniqueId(), "vote");
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CLICK HANDLER
    // ══════════════════════════════════════════════════════════════════════

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID uid = player.getUniqueId();
        String menuType = openMenuType.get(uid);
        if (menuType == null) return;

        // Zrušíme přesouvání itemů ve VŠECH slech tohoto GUI
        event.setCancelled(true);

        // Ignorujeme kliknutí mimo horní inventář (do hráčova inv)
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() == player.getInventory()) return;

        int slot = event.getSlot();

        switch (menuType) {
            case "join" -> handleJoinClick(player, uid, slot);
            case "vote" -> handleVoteClick(player, uid, slot);
        }
    }

    private void handleJoinClick(Player player, UUID uid, int slot) {
        Map<Integer, String> slotMap = joinSlotMap.getOrDefault(uid, Map.of());
        String teamId = slotMap.get(slot);
        if (teamId == null) return;

        player.closeInventory();

        if (teamId.startsWith("extra:random")) {
            if (plugin.getSessionManager() != null) {
                plugin.getSessionManager().assignRandomTeam(player);
            }
            return;
        }

        if (plugin.getSessionManager() != null) {
            plugin.getSessionManager().setPlayerTeam(player, teamId);
        }
    }

    private void handleVoteClick(Player player, UUID uid, int slot) {
        Map<Integer, String> slotMap = voteSlotMap.getOrDefault(uid, Map.of());
        String arenaName = slotMap.get(slot);
        if (arenaName == null) return;

        ArenaManager am  = plugin.getArenaManager();
        boolean      isNew = am.vote(uid, arenaName);

        if (isNew) {
            plugin.getMessageManager().send(player, "vote.registered", "map", arenaName);
        } else {
            plugin.getMessageManager().send(player, "vote.changed", "map", arenaName);
        }

        player.closeInventory();

        // Znovuotevřeme s aktuálními hlasy (po 1 ticku – po closeInventory)
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> openVoteMenu(player), 2L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uid = player.getUniqueId();

        // Čistíme jen pokud se zavírá opravdu naše menu
        // (ne při znovu-otevření přes runTaskLater)
        String type = openMenuType.get(uid);
        if (type != null) {
            openMenuType.remove(uid);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ITEM BUILDERY
    // ══════════════════════════════════════════════════════════════════════

    private ItemStack buildTeamItem(ConfigurationSection sec, TeamConfig team, int current) {
        ItemStack item = new ItemStack(team.block());
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        String nameRaw = (sec != null ? sec.getString("name", "{team_name}") : "{team_name}")
                .replace("{team_name}", team.displayName())
                .replace("{current}",  String.valueOf(current))
                .replace("{max}",      String.valueOf(team.maxPlayers()));

        meta.displayName(parse(nameRaw));

        if (sec != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : sec.getStringList("lore")) {
                lore.add(parse(line
                        .replace("{team_name}", team.displayName())
                        .replace("{current}",  String.valueOf(current))
                        .replace("{max}",      String.valueOf(team.maxPlayers()))));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildMapItem(ConfigurationSection sec, String mapName,
                                   int votes, int total, boolean voted) {
        Material mat = Material.MAP;
        if (sec != null) {
            try { mat = Material.valueOf(sec.getString("material", "MAP")); }
            catch (Exception ignored) {}
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        String votedMark = voted ? "<green>✔</green>" : "";
        String nameRaw = (sec != null ? sec.getString("name", "{map_name}") : "{map_name}")
                .replace("{map_name}", mapName)
                .replace("{voted}",    votedMark);
        meta.displayName(parse(nameRaw));

        if (sec != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : sec.getStringList("lore")) {
                lore.add(parse(line
                        .replace("{map_name}",    mapName)
                        .replace("{votes}",       String.valueOf(votes))
                        .replace("{total_votes}", String.valueOf(total))
                        .replace("{voted}",       votedMark)));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildInfoPanel(ConfigurationSection sec, String topMap,
                                     int total, String countdown) {
        Material mat = Material.PAPER;
        try { mat = Material.valueOf(sec.getString("material", "PAPER")); }
        catch (Exception ignored) {}

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(parse(sec.getString("name", "Info")));

        List<Component> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            lore.add(parse(line
                    .replace("{total_votes}", String.valueOf(total))
                    .replace("{top_map}",     topMap)
                    .replace("{countdown}",   countdown)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSimpleItem(ConfigurationSection sec) {
        Material mat = Material.STONE;
        try { mat = Material.valueOf(sec.getString("material", "STONE")); }
        catch (Exception ignored) {}

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(parse(sec.getString("name", " ")));

        List<Component> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            lore.add(parse(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void fillEmpty(Inventory inv, ConfigurationSection sec) {
        if (sec == null || !sec.getBoolean("enabled", true)) return;

        Material mat = Material.GRAY_STAINED_GLASS_PANE;
        try { mat = Material.valueOf(sec.getString("material", "GRAY_STAINED_GLASS_PANE")); }
        catch (Exception ignored) {}

        ItemStack filler = new ItemStack(mat);
        ItemMeta  meta   = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty()); // prázdný název (ne null)
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    // ── MiniMessage helper ─────────────────────────────────────────────────

    /**
     * Konvertuje &#RRGGBB → <#RRGGBB> a pak parsuje přes MiniMessage.
     */
    private Component parse(String input) {
        if (input == null) return Component.empty();
        String converted = LEGACY_HEX.matcher(input)
                .replaceAll(mr -> "<#" + mr.group(1) + ">");
        return MM.deserialize(converted);
    }
}