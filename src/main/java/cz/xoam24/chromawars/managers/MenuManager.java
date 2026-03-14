package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.ArenaData;
import cz.xoam24.chromawars.model.TeamConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Nativní GUI pro /join (výběr týmu) a /votemap (hlasování o mapě).
 *
 * Veškeré rozložení a texty jsou 100% konfigurovatelné v config.yml
 * pod sekcemi gui.join a gui.votemap.
 */
public class MenuManager implements Listener {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // Identifikátory inventářů (tituly jako klíče)
    private final Set<UUID> joinMenuOpen   = new HashSet<>();
    private final Set<UUID> voteMenuOpen   = new HashSet<>();

    // Mapování slot → arenaName pro votemap GUI
    private final Map<UUID, Map<Integer, String>> voteSlotMap = new HashMap<>();
    // Mapování slot → teamId pro join GUI
    private final Map<UUID, Map<Integer, String>> joinSlotMap = new HashMap<>();

    public MenuManager(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  JOIN MENU – /join
    // ══════════════════════════════════════════════════════════════════════════

    public void openJoinMenu(Player player) {
        FileConfiguration cfg = plugin.getConfigManager().getRawConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("gui.join");
        if (sec == null) { player.sendMessage("§cGUI join není nakonfigurováno."); return; }

        int rows  = Math.min(6, Math.max(1, sec.getInt("rows", 3)));
        String titleRaw = sec.getString("title", "Vyber tým");
        Inventory inv   = Bukkit.createInventory(null, rows * 9, mm.deserialize(titleRaw));

        // Výplň prázdných slotů
        fillEmpty(inv, sec.getConfigurationSection("fill-empty"));

        // Sloty týmů
        ConfigurationSection teamSlots = sec.getConfigurationSection("team-slots");
        ConfigurationSection teamItem  = sec.getConfigurationSection("team-item");
        Map<Integer, String> slotToTeam = new HashMap<>();

        Map<String, TeamConfig> teams = plugin.getConfigManager().getTeams();
        for (Map.Entry<String, TeamConfig> entry : teams.entrySet()) {
            String     teamId = entry.getKey();
            TeamConfig team   = entry.getValue();

            // Zjistíme počet hráčů v týmu
            int current = plugin.getSessionManager() != null
                    ? plugin.getSessionManager().countPlayersInTeam(teamId) : 0;
            int max     = team.maxPlayers();

            int slot = (teamSlots != null) ? teamSlots.getInt(teamId, -1) : -1;
            if (slot < 0 || slot >= rows * 9) continue;

            ItemStack item = buildTeamItem(teamItem, team, current, max);
            inv.setItem(slot, item);
            slotToTeam.put(slot, teamId);
        }

        // Extra tlačítka (náhodný tým apod.)
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
        joinMenuOpen.add(player.getUniqueId());
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VOTEMAP MENU – /votemap
    // ══════════════════════════════════════════════════════════════════════════

    public void openVoteMenu(Player player) {
        FileConfiguration cfg = plugin.getConfigManager().getRawConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("gui.votemap");
        if (sec == null) { player.sendMessage("§cGUI votemap není nakonfigurováno."); return; }

        ArenaManager arenas = plugin.getArenaManager();
        Collection<ArenaData> allArenas = arenas.getAllArenas();

        if (allArenas.isEmpty()) {
            plugin.getMessageManager().send(player, "vote.no-arenas");
            return;
        }

        int rows = Math.min(6, Math.max(1, sec.getInt("rows", 3)));
        String titleRaw = sec.getString("title", "Hlasování o mapě");
        Inventory inv   = Bukkit.createInventory(null, rows * 9, mm.deserialize(titleRaw));

        fillEmpty(inv, sec.getConfigurationSection("fill-empty"));

        ConfigurationSection layout   = sec.getConfigurationSection("layout");
        ConfigurationSection mapItem  = sec.getConfigurationSection("map-item");
        ConfigurationSection infoSec  = sec.getConfigurationSection("info-panel");

        String layoutMode = layout != null ? layout.getString("mode", "auto") : "auto";
        int startSlot     = layout != null ? layout.getInt("start-slot", 10)  : 10;
        int spacing       = layout != null ? layout.getInt("spacing", 2)       : 2;

        String playerVote = arenas.getPlayerVote(player.getUniqueId());
        int total = arenas.getTotalVotes();
        int currentSlot = startSlot;
        Map<Integer, String> slotToArena = new HashMap<>();

        for (ArenaData arena : allArenas) {
            if (currentSlot >= rows * 9) break;

            boolean voted  = arena.name().equals(playerVote);
            int voteCount  = arenas.getVoteCount(arena.name());
            ItemStack item = buildMapItem(mapItem, arena.name(), voteCount, total, voted);
            inv.setItem(currentSlot, item);
            slotToArena.put(currentSlot, arena.name());

            if (layoutMode.equals("auto")) {
                currentSlot += (1 + spacing);
            }
        }

        // Info panel
        if (infoSec != null && infoSec.getBoolean("enabled", true)) {
            int infoSlot = infoSec.getInt("slot", 26);
            if (infoSlot < rows * 9) {
                String countdown = String.valueOf(plugin.getConfigManager().getLobbyCountdown());
                inv.setItem(infoSlot, buildInfoPanel(infoSec,
                        arenas.getLeadingArenaName(), total, countdown));
            }
        }

        voteSlotMap.put(player.getUniqueId(), slotToArena);
        voteMenuOpen.add(player.getUniqueId());
        player.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CLICK HANDLERY
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);  // zabráníme přesouvání itemů

        UUID uid = player.getUniqueId();
        int slot = event.getRawSlot();

        // ── JOIN MENU ─────────────────────────────────────────────────────────
        if (joinMenuOpen.contains(uid)) {
            Map<Integer, String> slotMap = joinSlotMap.getOrDefault(uid, Map.of());
            String teamId = slotMap.get(slot);
            if (teamId == null) return;

            player.closeInventory();

            if (teamId.startsWith("extra:random")) {
                // Náhodný tým – přiřadit do nejméně obsazeného
                if (plugin.getSessionManager() != null) {
                    plugin.getSessionManager().assignRandomTeam(player);
                }
                return;
            }

            TeamConfig team = plugin.getConfigManager().getTeam(teamId);
            if (team == null) return;

            if (plugin.getSessionManager() != null) {
                plugin.getSessionManager().setPlayerTeam(player, teamId);
            }
            return;
        }

        // ── VOTEMAP MENU ──────────────────────────────────────────────────────
        if (voteMenuOpen.contains(uid)) {
            Map<Integer, String> slotMap = voteSlotMap.getOrDefault(uid, Map.of());
            String arenaName = slotMap.get(slot);
            if (arenaName == null) return;

            ArenaManager am = plugin.getArenaManager();
            boolean isNew   = am.vote(uid, arenaName);
            MessageManager msg = plugin.getMessageManager();

            if (isNew) {
                msg.send(player, "vote.registered", "map", arenaName);
            } else {
                msg.send(player, "vote.changed", "map", arenaName);
            }

            player.closeInventory();
            // Obnovíme menu (aktualizace hlasů)
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> openVoteMenu(player), 2L);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uid = player.getUniqueId();
        joinMenuOpen.remove(uid);
        voteMenuOpen.remove(uid);
        // Slotmapy ponecháme – jsou malé a přepíšou se při dalším otevření
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BUILDERY ITEMŮ
    // ══════════════════════════════════════════════════════════════════════════

    /** Sestaví ItemStack pro tlačítko týmu z config sekce team-item. */
    private ItemStack buildTeamItem(ConfigurationSection sec, TeamConfig team,
                                    int current, int max) {
        Material mat;
        try { mat = Material.valueOf(team.block().name()); }
        catch (Exception e) { mat = Material.STONE; }

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        // Jméno
        String nameRaw = (sec != null ? sec.getString("name", "{team_name}") : "{team_name}")
                .replace("{team_name}", plugin.getMessageManager().getRaw("teams." + team.id() + ".name") )
                .replace("{color}", "#" + Integer.toHexString(team.color().value()));
        meta.displayName(mm.deserialize(nameRaw));

        // Lore
        if (sec != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : sec.getStringList("lore")) {
                String parsed = line
                        .replace("{team_name}", team.displayName())
                        .replace("{current}", String.valueOf(current))
                        .replace("{max}",     String.valueOf(max))
                        .replace("{color}",   "#" + Integer.toHexString(team.color().value()));
                lore.add(mm.deserialize(parsed));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    /** Sestaví ItemStack pro položku mapy ve vote menu. */
    private ItemStack buildMapItem(ConfigurationSection sec, String mapName,
                                   int votes, int total, boolean playerVoted) {
        Material mat = Material.MAP;
        if (sec != null) {
            try { mat = Material.valueOf(sec.getString("material", "MAP")); }
            catch (Exception ignored) {}
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        String votedMark = playerVoted ? "<green>✔</green>" : "";
        String nameRaw = (sec != null ? sec.getString("name", "{map_name}") : "{map_name}")
                .replace("{map_name}", mapName)
                .replace("{voted}",    votedMark);
        meta.displayName(mm.deserialize(nameRaw));

        if (sec != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : sec.getStringList("lore")) {
                String parsed = line
                        .replace("{map_name}", mapName)
                        .replace("{votes}",    String.valueOf(votes))
                        .replace("{total_votes}", String.valueOf(total))
                        .replace("{voted}",    votedMark);
                lore.add(mm.deserialize(parsed));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    /** Sestaví ItemStack pro info panel. */
    private ItemStack buildInfoPanel(ConfigurationSection sec, String topMap,
                                     int total, String countdown) {
        Material mat = Material.PAPER;
        try { mat = Material.valueOf(sec.getString("material", "PAPER")); }
        catch (Exception ignored) {}

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(mm.deserialize(sec.getString("name", "Info")));

        List<Component> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            String parsed = line
                    .replace("{total_votes}", String.valueOf(total))
                    .replace("{top_map}",     topMap)
                    .replace("{countdown}",   countdown);
            lore.add(mm.deserialize(parsed));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Sestaví jednoduchý ItemStack z libovolné config sekce (material + name + lore). */
    private ItemStack buildSimpleItem(ConfigurationSection sec) {
        Material mat = Material.STONE;
        try { mat = Material.valueOf(sec.getString("material", "STONE")); }
        catch (Exception ignored) {}

        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;

        String name = sec.getString("name", " ");
        meta.displayName(mm.deserialize(name));

        List<Component> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            lore.add(mm.deserialize(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Vyplní prázdné sloty skleněnými panely (nebo jiným materiálem z configu). */
    private void fillEmpty(Inventory inv, ConfigurationSection sec) {
        if (sec == null || !sec.getBoolean("enabled", true)) return;
        Material mat = Material.GRAY_STAINED_GLASS_PANE;
        try { mat = Material.valueOf(sec.getString("material", "GRAY_STAINED_GLASS_PANE")); }
        catch (Exception ignored) {}

        String nameRaw = sec.getString("name", " ");
        ItemStack filler = new ItemStack(mat);
        ItemMeta  meta   = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize(nameRaw));
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }
}