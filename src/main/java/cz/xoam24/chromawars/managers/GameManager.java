package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.ArenaData;
import cz.xoam24.chromawars.model.PlayerSession;
import cz.xoam24.chromawars.model.TeamConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {

    public enum GameState { WAITING, STARTING, RUNNING, ENDING }

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private GameState gameState   = GameState.WAITING;
    private ArenaData currentArena = null;
    private int       lobbyCountdown;
    private int       gameTimeLeft;

    // teamId → počet bloků
    private final Map<String, Integer> teamBlockCounts = new ConcurrentHashMap<>();
    private int totalArenaBlocks = 0;

    private BukkitTask lobbyCountdownTask = null;
    private BukkitTask gameTickTask       = null;

    // UUID → teamId (hráči ve hře)
    private final Map<UUID, String> inGamePlayers = new ConcurrentHashMap<>();

    // ── Anti-spam: track zda jsme již odeslali "čekáme" zprávu ───────────
    // Posíláme ji pouze při změně stavu (připojení/odpojení hráče)
    private int lastOnlineCount = -1;

    public GameManager(ChromaWars plugin) {
        this.plugin         = plugin;
        this.lobbyCountdown = plugin.getConfigManager().getLobbyCountdown();
        this.gameTimeLeft   = plugin.getConfigManager().getGameDuration();

        plugin.getScoreboardManager().startLobbyTask();
        startLobbyCountdown();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOBBY ODPOČET – BEZ SPAMU
    // ══════════════════════════════════════════════════════════════════════

    private void startLobbyCountdown() {
        lobbyCountdown = plugin.getConfigManager().getLobbyCountdown();
        lastOnlineCount = -1; // reset anti-spam tracku
        cancelTask(lobbyCountdownTask);

        lobbyCountdownTask = new BukkitRunnable() {
            @Override public void run() {
                int online = Bukkit.getOnlinePlayers().size();
                int min    = plugin.getConfigManager().getMinPlayersToStart();

                if (online < min) {
                    // Reset odpočtu
                    lobbyCountdown = plugin.getConfigManager().getLobbyCountdown();

                    // ── Zprávu posíláme POUZE pokud se počet hráčů změnil ──
                    // Tím eliminujeme spam každou sekundu
                    if (online != lastOnlineCount) {
                        lastOnlineCount = online;
                        broadcastToLobby("lobby.waiting",
                                "current", String.valueOf(online),
                                "min",     String.valueOf(min));
                    }
                    return;
                }

                // Máme dost hráčů – reset anti-spam tracku
                lastOnlineCount = -1;

                // Odpočítávání – broadcast jen na klíčových hodnotách
                if (lobbyCountdown == 30 || lobbyCountdown == 10 || lobbyCountdown == 5) {
                    broadcastToLobby("lobby.countdown",
                            "time", String.valueOf(lobbyCountdown));
                }

                // Title na posledních 5 sekundách
                if (lobbyCountdown <= 5 && lobbyCountdown > 0) {
                    showCountdownTitle(lobbyCountdown);
                }

                if (lobbyCountdown <= 0) {
                    cancel();
                    lobbyCountdownTask = null;
                    startGame();
                    return;
                }
                lobbyCountdown--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  START HRY
    // ══════════════════════════════════════════════════════════════════════

    private void startGame() {
        gameState = GameState.STARTING;

        ArenaData winner = plugin.getArenaManager().getVotingWinner();
        if (winner == null || winner.getWorld() == null) {
            gameState = GameState.WAITING;
            startLobbyCountdown();
            return;
        }

        currentArena     = winner;
        totalArenaBlocks = winner.totalBlocks();

        teamBlockCounts.clear();
        plugin.getConfigManager().getTeams().keySet()
                .forEach(id -> teamBlockCounts.put(id, 0));

        List<Player> eligible = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (eligible.size() < plugin.getConfigManager().getMinPlayersToStart()) {
            broadcastToAll("game.not-enough-players",
                    "min", String.valueOf(plugin.getConfigManager().getMinPlayersToStart()));
            gameState = GameState.WAITING;
            startLobbyCountdown();
            return;
        }

        resetArena(winner);
        distributePlayersToTeams(eligible);
        prepareAndTeleportPlayers();
        showStartTitle();

        plugin.getScoreboardManager().stopLobbyTask();
        plugin.getScoreboardManager().startIngameTask();

        gameState    = GameState.RUNNING;
        gameTimeLeft = plugin.getConfigManager().getGameDuration();
        startGameTick();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HERNÍ TICK
    // ══════════════════════════════════════════════════════════════════════

    private void startGameTick() {
        cancelTask(gameTickTask);

        gameTickTask = new BukkitRunnable() {
            @Override public void run() {
                if (gameState != GameState.RUNNING) { cancel(); return; }

                checkWinCondition();

                if (gameTimeLeft == 60 || gameTimeLeft == 30 || gameTimeLeft == 10) {
                    broadcastToIngame("game.time-warning",
                            "time", String.valueOf(gameTimeLeft));
                }

                if (gameTimeLeft <= 0) {
                    cancel();
                    endGame(null);
                    return;
                }
                gameTimeLeft--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void checkWinCondition() {
        if (totalArenaBlocks <= 0) return;
        double threshold = plugin.getConfigManager().getWinPercentage();

        for (Map.Entry<String, Integer> entry : teamBlockCounts.entrySet()) {
            double pct = (entry.getValue() * 100.0) / totalArenaBlocks;
            if (pct >= threshold) {
                cancelTask(gameTickTask);
                endGame(entry.getKey());
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  KONEC HRY
    // ══════════════════════════════════════════════════════════════════════

    public void endGame(String winnerTeamId) {
        if (gameState == GameState.ENDING) return;
        gameState = GameState.ENDING;

        cancelTask(gameTickTask);
        plugin.getScoreboardManager().stopIngameTask();

        if (winnerTeamId != null) {
            TeamConfig team     = plugin.getConfigManager().getTeam(winnerTeamId);
            String teamName     = team != null ? team.displayName() : winnerTeamId;
            String teamColorHex = team != null
                    ? "<#" + String.format("%06X", team.color().value() & 0xFFFFFF) + ">"
                    : "";
            String pct          = getTeamPercentFormatted(winnerTeamId);

            showEndTitle(winnerTeamId, teamName, teamColorHex, pct);
            broadcastToIngame("game.win-broadcast",
                    "team",       teamName,
                    "team_color", teamColorHex,
                    "percent",    pct);

            distributeElo(winnerTeamId);
        } else {
            showDrawTitle();
        }

        plugin.getEloManager().updateLeaderboardCacheAsync();
        plugin.getArenaManager().resetVotes();

        new BukkitRunnable() {
            @Override public void run() {
                if (currentArena != null) resetArena(currentArena);
                teleportAllToLobby();
                cleanupAfterGame();
            }
        }.runTaskLater(plugin, 100L);
    }

    private void distributeElo(String winnerTeamId) {
        EloManager eloMgr  = plugin.getEloManager();
        int        eloWin  = plugin.getConfigManager().getEloWin();
        int        eloLoss = plugin.getConfigManager().getEloLoss();

        for (Map.Entry<UUID, String> entry : inGamePlayers.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            if (entry.getValue().equals(winnerTeamId)) {
                eloMgr.addElo(p, eloWin);
                plugin.getMessageManager().send(p, "game.elo-gain",
                        "amount", String.valueOf(eloWin), "total", "...");
            } else {
                eloMgr.removeElo(p, eloLoss);
                plugin.getMessageManager().send(p, "game.elo-loss",
                        "amount", String.valueOf(eloLoss), "total", "...");
            }
        }
    }

    private void teleportAllToLobby() {
        Location lobbyLoc = buildLobbyLocation();
        if (lobbyLoc == null) return;

        for (UUID uid : inGamePlayers.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) continue;

            PlayerSession session = plugin.getSessionManager().getSession(p);
            if (session == null) continue;

            p.getInventory().clear();
            if (session.getSavedInventory() != null) {
                p.getInventory().setContents(session.getSavedInventory());
            }
            p.setGameMode(session.getSavedGameMode() != null
                    ? session.getSavedGameMode() : GameMode.SURVIVAL);
            p.teleport(lobbyLoc);
            session.resetToLobby();
        }
    }

    private Location buildLobbyLocation() {
        ConfigManager cfg = plugin.getConfigManager();
        World world = Bukkit.getWorld(cfg.getLobbyWorldName());
        if (world == null) return null;
        return new Location(world,
                cfg.getLobbyX(), cfg.getLobbyY(), cfg.getLobbyZ(),
                cfg.getLobbyYaw(), cfg.getLobbyPitch());
    }

    private void cleanupAfterGame() {
        inGamePlayers.clear();
        teamBlockCounts.clear();
        if (plugin.getTurfListener() != null) plugin.getTurfListener().clearAllCaches();
        currentArena = null;
        gameState    = GameState.WAITING;
        plugin.getScoreboardManager().startLobbyTask();
        startLobbyCountdown();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PŘÍPRAVA HRÁČŮ + SPAWN SYSTÉM
    // ══════════════════════════════════════════════════════════════════════

    private void distributePlayersToTeams(List<Player> players) {
        SessionManager sm      = plugin.getSessionManager();
        List<String>   teamIds = new ArrayList<>(plugin.getConfigManager().getTeams().keySet());
        int            idx     = 0;

        for (Player p : players) {
            PlayerSession session = sm.getSession(p);
            if (session == null) continue;
            if (!session.isInTeam()) {
                session.setTeamId(teamIds.get(idx % teamIds.size()));
                idx++;
            }
            inGamePlayers.put(p.getUniqueId(), session.getTeamId());
            session.setState(PlayerSession.State.PLAYING);
        }
    }

    private void prepareAndTeleportPlayers() {
        for (Map.Entry<UUID, String> entry : inGamePlayers.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;

            PlayerSession session = plugin.getSessionManager().getSession(p);
            if (session == null) continue;

            // Záloha
            session.setSavedInventory(p.getInventory().getContents().clone());
            session.setSavedGameMode(p.getGameMode());
            session.setSavedLocation(p.getLocation());

            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.setSaturation(20);

            giveTeamArmor(p, entry.getValue());

            // Teleport: preferujeme nastavený team spawn, jinak náhodné místo
            Location spawnLoc = getTeamSpawnLocation(currentArena.name(), entry.getValue());
            if (spawnLoc != null) {
                p.teleport(spawnLoc);
            } else {
                teleportToRandomArenaSpot(p);
            }
        }
    }

    // ── Team spawn gettery ────────────────────────────────────────────────

    /**
     * Vrátí nastavený spawn pro tým v dané aréně.
     * Čte z config: arenas.<arenaName>.spawns.<teamId>.x/y/z/yaw/pitch
     * Pokud není nastaven, vrátí null.
     */
    public Location getTeamSpawnLocation(String arenaName, String teamId) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("arenas." + arenaName + ".spawns." + teamId);
        if (sec == null) return null;

        String worldName = plugin.getConfigManager().getRawConfig()
                .getString("arenas." + arenaName + ".world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x     = sec.getDouble("x");
        double y     = sec.getDouble("y");
        double z     = sec.getDouble("z");
        float  yaw   = (float) sec.getDouble("yaw", 0);
        float  pitch = (float) sec.getDouble("pitch", 0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Uloží team spawn do configu.
     * Volá CommandManager při /chroma arena setspawn <teamId>
     */
    public void saveTeamSpawn(String arenaName, String teamId, Location loc) {
        String path = "arenas." + arenaName + ".spawns." + teamId + ".";
        plugin.getConfigManager().getRawConfig().set(path + "x",     loc.getX());
        plugin.getConfigManager().getRawConfig().set(path + "y",     loc.getY());
        plugin.getConfigManager().getRawConfig().set(path + "z",     loc.getZ());
        plugin.getConfigManager().getRawConfig().set(path + "yaw",   (double) loc.getYaw());
        plugin.getConfigManager().getRawConfig().set(path + "pitch", (double) loc.getPitch());
        plugin.saveConfig();
    }

    // ── Teleport na náhodné místo ─────────────────────────────────────────

    public void teleportToRandomArenaSpot(Player player) {
        if (currentArena == null || currentArena.getWorld() == null) return;
        Random rng = new Random();
        int rangeX = currentArena.maxX() - currentArena.minX();
        int rangeZ = currentArena.maxZ() - currentArena.minZ();
        int x = currentArena.minX() + (rangeX > 0 ? rng.nextInt(rangeX + 1) : 0);
        int z = currentArena.minZ() + (rangeZ > 0 ? rng.nextInt(rangeZ + 1) : 0);
        int y = currentArena.minY() + 2;
        player.teleport(new Location(currentArena.getWorld(), x + 0.5, y, z + 0.5));
    }

    // ── Reset arény ───────────────────────────────────────────────────────

    private void resetArena(ArenaData arena) {
        World world = arena.getWorld();
        if (world == null) return;
        new BukkitRunnable() {
            @Override public void run() {
                for (int x = arena.minX(); x <= arena.maxX(); x++)
                    for (int y = arena.minY(); y <= arena.maxY(); y++)
                        for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                            Block b = world.getBlockAt(x, y, z);
                            if (b.getType().name().endsWith("_CONCRETE"))
                                b.setType(Material.WHITE_CONCRETE, false);
                        }
            }
        }.runTask(plugin);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BRNĚNÍ
    // ══════════════════════════════════════════════════════════════════════

    private void giveTeamArmor(Player player, String teamId) {
        TeamConfig team = plugin.getConfigManager().getTeam(teamId);
        if (team == null) return;

        String hexStr = plugin.getConfigManager().getRawConfig()
                .getString("teams." + teamId + ".armor-color",
                        String.format("%06X", team.color().value() & 0xFFFFFF));
        Color armorColor;
        try {
            armorColor = Color.fromRGB(Integer.parseInt(hexStr.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            armorColor = Color.fromRGB(team.color().value() & 0xFFFFFF);
        }

        player.getInventory().setHelmet    (coloredArmor(Material.LEATHER_HELMET,     armorColor));
        player.getInventory().setChestplate(coloredArmor(Material.LEATHER_CHESTPLATE, armorColor));
        player.getInventory().setLeggings  (coloredArmor(Material.LEATHER_LEGGINGS,   armorColor));
        player.getInventory().setBoots     (coloredArmor(Material.LEATHER_BOOTS,      armorColor));
    }

    public void giveTeamArmorPublic(Player player, String teamId) {
        giveTeamArmor(player, teamId);
    }

    private ItemStack coloredArmor(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        if (meta != null) {
            meta.setColor(color);
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BLOKOVÉ POČÍTÁNÍ
    // ══════════════════════════════════════════════════════════════════════

    public void incrementTeamBlock(String teamId) {
        teamBlockCounts.merge(teamId, 1, Integer::sum);
    }

    public void decrementTeamBlock(String teamId) {
        teamBlockCounts.merge(teamId, -1,
                (cur, delta) -> Math.max(0, cur + delta));
    }

    public int getTeamBlockCount(String teamId) {
        return teamBlockCounts.getOrDefault(teamId, 0);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GETTERY
    // ══════════════════════════════════════════════════════════════════════

    public String    getCurrentArenaName()      { return currentArena != null ? currentArena.name() : "\u2013"; }
    public ArenaData getCurrentArena()          { return currentArena; }
    public GameState getGameState()             { return gameState; }
    public boolean   isRunning()                { return gameState == GameState.RUNNING; }
    public int       getLobbyCountdown()        { return lobbyCountdown; }
    public int       getInGamePlayerCount()     { return inGamePlayers.size(); }
    public Map<UUID, String> getInGamePlayers() { return Collections.unmodifiableMap(inGamePlayers); }

    public String getFormattedTimeLeft() {
        int m = gameTimeLeft / 60;
        int s = gameTimeLeft % 60;
        return String.format("%d:%02d", m, s);
    }

    public String getTeamPercentFormatted(String teamId) {
        if (totalArenaBlocks <= 0) return "0.0";
        double pct = (teamBlockCounts.getOrDefault(teamId, 0) * 100.0) / totalArenaBlocks;
        return String.format("%.1f", pct);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TITULY
    // ══════════════════════════════════════════════════════════════════════

    private void showStartTitle() {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.game.start-title");
        String title    = sec != null ? sec.getString("title",    "<bold><gradient:#FF4444:#4477FF>CHROMAWARS!</gradient></bold>") : "<bold><gradient:#FF4444:#4477FF>CHROMAWARS!</gradient></bold>";
        String subtitle = sec != null ? sec.getString("subtitle", "<gray>Obarvi co nejvíce plochy!</gray>") : "<gray>Obarvi co nejvíce plochy!</gray>";
        int fi = sec != null ? sec.getInt("fade-in",  10) : 10;
        int st = sec != null ? sec.getInt("stay",     60) : 60;
        int fo = sec != null ? sec.getInt("fade-out", 20) : 20;
        Title t = buildTitle(title, subtitle, fi, st, fo);
        getIngamePlayers().forEach(p -> p.showTitle(t));
    }

    private void showEndTitle(String teamId, String teamName, String teamColor, String pct) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.game.end-title.win");
        String titleRaw    = (sec != null ? sec.getString("title",    "<bold>{team_color}{team} vítězí!</bold>") : "<bold>{team_color}{team} vítězí!</bold>")
                .replace("{team}", teamName).replace("{team_color}", teamColor);
        String subtitleRaw = (sec != null ? sec.getString("subtitle", "<gray>Obsazeno: {percent}%</gray>") : "<gray>Obsazeno: {percent}%</gray>")
                .replace("{percent}", pct);
        int fi = sec != null ? sec.getInt("fade-in",  10) : 10;
        int st = sec != null ? sec.getInt("stay",     80) : 80;
        int fo = sec != null ? sec.getInt("fade-out", 20) : 20;
        Title t = buildTitle(titleRaw, subtitleRaw, fi, st, fo);
        getIngamePlayers().forEach(p -> p.showTitle(t));
    }

    private void showDrawTitle() {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.game.end-title.draw");
        String title    = sec != null ? sec.getString("title",    "<bold><gray>Remíza!</gray></bold>")   : "<bold><gray>Remíza!</gray></bold>";
        String subtitle = sec != null ? sec.getString("subtitle", "<gray>Čas vypršel</gray>") : "<gray>Čas vypršel</gray>";
        int fi = sec != null ? sec.getInt("fade-in",  10) : 10;
        int st = sec != null ? sec.getInt("stay",     80) : 80;
        int fo = sec != null ? sec.getInt("fade-out", 20) : 20;
        Title t = buildTitle(title, subtitle, fi, st, fo);
        getIngamePlayers().forEach(p -> p.showTitle(t));
    }

    private void showCountdownTitle(int seconds) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.lobby.countdown-title");
        String titleRaw = (sec != null
                ? sec.getString("title", "<bold><yellow>{time}</yellow></bold>")
                : "<bold><yellow>{time}</yellow></bold>")
                .replace("{time}", String.valueOf(seconds));
        String sub = sec != null ? sec.getString("subtitle", "") : "";
        int fi = sec != null ? sec.getInt("fade-in",  0) : 0;
        int st = sec != null ? sec.getInt("stay",    25) : 25;
        int fo = sec != null ? sec.getInt("fade-out", 5) : 5;
        Title t = buildTitle(titleRaw, sub, fi, st, fo);
        Bukkit.getOnlinePlayers().forEach(p -> p.showTitle(t));
    }

    private Title buildTitle(String title, String subtitle, int fi, int st, int fo) {
        return Title.title(
                parseMM(title),
                parseMM(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fi * 50L),
                        Duration.ofMillis(st * 50L),
                        Duration.ofMillis(fo * 50L)));
    }

    /** MiniMessage parse s automatickou konverzí &#RRGGBB → <#RRGGBB> */
    private net.kyori.adventure.text.Component parseMM(String input) {
        if (input == null || input.isBlank()) return net.kyori.adventure.text.Component.empty();
        String converted = input.replaceAll("&#([A-Fa-f0-9]{6})", "<#$1>");
        return mm.deserialize(converted);
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────

    private void broadcastToLobby(String key, String... placeholders) {
        SessionManager sm = plugin.getSessionManager();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerSession s = sm != null ? sm.getSession(p) : null;
            if (s == null || s.isInLobby()) plugin.getMessageManager().send(p, key, placeholders);
        }
    }

    private void broadcastToIngame(String key, String... placeholders) {
        for (UUID uid : inGamePlayers.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) plugin.getMessageManager().send(p, key, placeholders);
        }
    }

    private void broadcastToAll(String key, String... placeholders) {
        Bukkit.getOnlinePlayers().forEach(p ->
                plugin.getMessageManager().send(p, key, placeholders));
    }

    private List<Player> getIngamePlayers() {
        List<Player> result = new ArrayList<>();
        for (UUID uid : inGamePlayers.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) result.add(p);
        }
        return result;
    }

    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) task.cancel();
    }
}