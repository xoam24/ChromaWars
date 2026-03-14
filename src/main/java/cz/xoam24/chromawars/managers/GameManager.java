package cz.xoam24.chromawars.managers;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.model.ArenaData;
import cz.xoam24.chromawars.model.PlayerSession;
import cz.xoam24.chromawars.model.TeamConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.Component;
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

/**
 * Hlavní herní smyčka ChromaWars.
 *
 * Stavy:
 *   WAITING  → lobby, čekáme na hráče
 *   STARTING → odpočet, nelze vstoupit
 *   RUNNING  → hra běží
 *   ENDING   → ukončování, rozdávání ELO
 *
 * Blokové počítání:
 *   TurfListener při každém obarvení volá incrementTeamBlock() / decrementTeamBlock().
 *   GameManager si drží Map<String, Integer> teamBlockCounts – žádné scanování arény
 *   při každém ticku. Procenta se vypočtou z těchto počítadel.
 */
public class GameManager {

    public enum GameState { WAITING, STARTING, RUNNING, ENDING }

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // ── Stav hry ──────────────────────────────────────────────────────────────
    private GameState gameState    = GameState.WAITING;
    private ArenaData currentArena = null;
    private int       lobbyCountdown;
    private int       gameTimeLeft;

    // ── Blokové počítání (teamId → počet bloků) ────────────────────────────
    private final Map<String, Integer> teamBlockCounts = new ConcurrentHashMap<>();
    private int totalArenaBlocks = 0;

    // ── Tasky ─────────────────────────────────────────────────────────────────
    private BukkitTask lobbyCountdownTask = null;
    private BukkitTask gameTickTask       = null;

    // ── Hráči ve hře (UUID → teamId) pro rychlý lookup ───────────────────────
    private final Map<UUID, String> inGamePlayers = new ConcurrentHashMap<>();

    public GameManager(ChromaWars plugin) {
        this.plugin = plugin;
        this.lobbyCountdown = plugin.getConfigManager().getLobbyCountdown();
        this.gameTimeLeft   = plugin.getConfigManager().getGameDuration();

        // Registrace do ChromaWars
        plugin.setGameManager(this);

        // Spustíme lobby scoreboard a odpočet
        plugin.<ScoreboardManager>getScoreboardManager().startLobbyTask();
        startLobbyCountdown();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOBBY ODPOČET
    // ══════════════════════════════════════════════════════════════════════════

    private void startLobbyCountdown() {
        lobbyCountdown = plugin.getConfigManager().getLobbyCountdown();
        cancelTask(lobbyCountdownTask);

        lobbyCountdownTask = new BukkitRunnable() {
            @Override public void run() {
                int online = Bukkit.getOnlinePlayers().size();
                int min    = plugin.getConfigManager().getMinPlayersToStart();

                if (online < min) {
                    // Reset odpočtu
                    lobbyCountdown = plugin.getConfigManager().getLobbyCountdown();
                    broadcastToLobby("lobby.waiting",
                            "current", String.valueOf(online),
                            "min",     String.valueOf(min));
                    return;
                }

                // Odpočítávání
                if (lobbyCountdown == 10 || lobbyCountdown == 5
                        || lobbyCountdown == 3 || lobbyCountdown == 2 || lobbyCountdown == 1) {
                    showCountdownTitle(lobbyCountdown);
                }
                if (lobbyCountdown == 30 || lobbyCountdown == 10) {
                    broadcastToLobby("lobby.countdown",
                            "time", String.valueOf(lobbyCountdown));
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

    // ══════════════════════════════════════════════════════════════════════════
    //  START HRY
    // ══════════════════════════════════════════════════════════════════════════

    private void startGame() {
        gameState = GameState.STARTING;

        // Vybereme arénu z hlasování
        ArenaData winner = plugin.getArenaManager().getVotingWinner();
        if (winner == null || winner.getWorld() == null) {
            broadcastToAll("game.not-enough-players", "min", "1");
            gameState = GameState.WAITING;
            startLobbyCountdown();
            return;
        }
        currentArena    = winner;
        totalArenaBlocks = winner.totalBlocks();

        // Resetujeme počítadla bloků
        teamBlockCounts.clear();
        plugin.getConfigManager().getTeams().keySet()
                .forEach(id -> teamBlockCounts.put(id, 0));

        // Sebereme hráče do hry
        List<Player> eligible = getEligiblePlayers();
        if (eligible.size() < plugin.getConfigManager().getMinPlayersToStart()) {
            broadcastToAll("game.not-enough-players",
                    "min", String.valueOf(plugin.getConfigManager().getMinPlayersToStart()));
            gameState = GameState.WAITING;
            startLobbyCountdown();
            return;
        }

        // Resetujeme arénu na WHITE_CONCRETE (asynchronně per-chunk)
        resetArena(winner);

        // Teleportujeme hráče a připravíme je
        distributePlayersToTeams(eligible);
        prepareAndTeleportPlayers();

        // Uložíme hlasování (výsledky se broadcastnou)
        plugin.getMessageManager().send(
                (org.bukkit.command.CommandSender) Bukkit.getConsoleSender(),
                "vote.result",
                "map",   currentArena.name(),
                "votes", String.valueOf(plugin.getArenaManager().getVoteCount(currentArena.name())));

        // Start title pro všechny
        showStartTitle();

        // Přepneme scoreboard
        plugin.<ScoreboardManager>getScoreboardManager().stopLobbyTask();
        plugin.<ScoreboardManager>getScoreboardManager().startIngameTask();

        gameState    = GameState.RUNNING;
        gameTimeLeft = plugin.getConfigManager().getGameDuration();

        startGameTick();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HERNÍ TICK
    // ══════════════════════════════════════════════════════════════════════════

    private void startGameTick() {
        cancelTask(gameTickTask);

        gameTickTask = new BukkitRunnable() {
            @Override public void run() {
                if (gameState != GameState.RUNNING) { cancel(); return; }

                // Kontrola win condition každý tick
                checkWinCondition();

                // Časové varování
                if (gameTimeLeft == 60 || gameTimeLeft == 30 || gameTimeLeft == 10) {
                    broadcastToIngame("game.time-warning",
                            "time", String.valueOf(gameTimeLeft));
                }

                if (gameTimeLeft <= 0) {
                    cancel();
                    endGame(null);  // null = draw / konec časem
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

    // ══════════════════════════════════════════════════════════════════════════
    //  KONEC HRY
    // ══════════════════════════════════════════════════════════════════════════

    public void endGame(String winnerTeamId) {
        if (gameState == GameState.ENDING) return;
        gameState = GameState.ENDING;

        cancelTask(gameTickTask);
        plugin.<ScoreboardManager>getScoreboardManager().stopIngameTask();

        // Titulek výsledku
        if (winnerTeamId != null) {
            TeamConfig team = plugin.getConfigManager().getTeam(winnerTeamId);
            String teamName = team != null ? team.displayName() : winnerTeamId;
            String teamColor = team != null ? "<#" + Integer.toHexString(team.color().value()) + ">" : "";
            String pct = getTeamPercentFormatted(winnerTeamId);

            showEndTitle(winnerTeamId, teamName, teamColor, pct);
            broadcastToIngame("game.win-broadcast",
                    "team",       teamName,
                    "team_color", teamColor,
                    "percent",    pct);

            // ELO distribuce
            distributeElo(winnerTeamId);
        } else {
            // Remíza
            showDrawTitle();
        }

        // Aktualizace leaderboard cache
        plugin.getEloManager().updateLeaderboardCacheAsync();
        plugin.getArenaManager().resetVotes();

        // Reset arény a teleport do lobby za 5 sekund
        new BukkitRunnable() {
            @Override public void run() {
                if (currentArena != null) resetArena(currentArena);
                teleportAllToLobby();
                cleanupAfterGame();
            }
        }.runTaskLater(plugin, 100L); // 5 sekund
    }

    // ── ELO distribuce ────────────────────────────────────────────────────────

    private void distributeElo(String winnerTeamId) {
        int eloWin  = plugin.getConfigManager().getEloWin();
        int eloLoss = plugin.getConfigManager().getEloLoss();
        EloManager eloMgr = plugin.getEloManager();

        for (Map.Entry<UUID, String> entry : inGamePlayers.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;

            boolean won = entry.getValue().equals(winnerTeamId);
            if (won) {
                eloMgr.addElo(p, eloWin);
                plugin.getMessageManager().send(p, "game.elo-gain",
                        "amount", String.valueOf(eloWin),
                        "total",  "...");
            } else {
                eloMgr.removeElo(p, eloLoss);
                plugin.getMessageManager().send(p, "game.elo-loss",
                        "amount", String.valueOf(eloLoss),
                        "total",  "...");
            }
        }
    }

    // ── Teleport do lobby ─────────────────────────────────────────────────────

    private void teleportAllToLobby() {
        Location lobbyLoc = buildLobbyLocation();
        if (lobbyLoc == null) return;

        for (UUID uid : inGamePlayers.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) continue;

            PlayerSession session = plugin.<SessionManager>getSessionManager().getSession(p);
            if (session == null) continue;

            // Obnovíme inventář a gamemode
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

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void cleanupAfterGame() {
        inGamePlayers.clear();
        teamBlockCounts.clear();
        currentArena = null;
        gameState    = GameState.WAITING;
        plugin.<ScoreboardManager>getScoreboardManager().startLobbyTask();
        startLobbyCountdown();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DISTRIBUCE HRÁČŮ DO TÝMŮ
    // ══════════════════════════════════════════════════════════════════════════

    private void distributePlayersToTeams(List<Player> players) {
        SessionManager sm = plugin.<SessionManager>getSessionManager();
        List<String> teamIds = new ArrayList<>(plugin.getConfigManager().getTeams().keySet());
        int teamIndex = 0;

        for (Player p : players) {
            PlayerSession session = sm.getSession(p);
            if (session == null) continue;

            // Pokud nemá tým (nehlasoval), přiřadíme automaticky
            if (!session.isInTeam()) {
                session.setTeamId(teamIds.get(teamIndex % teamIds.size()));
                teamIndex++;
            }

            inGamePlayers.put(p.getUniqueId(), session.getTeamId());
            session.setState(PlayerSession.State.PLAYING);
        }
    }

    private void prepareAndTeleportPlayers() {
        for (Map.Entry<UUID, String> entry : inGamePlayers.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;

            PlayerSession session = plugin.<SessionManager>getSessionManager().getSession(p);
            if (session == null) continue;

            // Záloha inventáře a gamemode
            session.setSavedInventory(p.getInventory().getContents().clone());
            session.setSavedGameMode(p.getGameMode());
            session.setSavedLocation(p.getLocation());

            // Připravíme hráče
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setSaturation(20);

            // Dáme obarvené brnění
            giveTeamArmor(p, entry.getValue());

            // Teleport na náhodné místo v aréně
            teleportToRandomArenaSpot(p);
        }
    }

    // ── Brnění ────────────────────────────────────────────────────────────────

    private void giveTeamArmor(Player player, String teamId) {
        TeamConfig team = plugin.getConfigManager().getTeam(teamId);
        if (team == null) return;

        // Barva z configu (armor-color sekce)
        String hexStr = plugin.getConfigManager().getRawConfig()
                .getString("teams." + teamId + ".armor-color",
                        Integer.toHexString(team.color().value()));
        Color armorColor;
        try {
            armorColor = Color.fromRGB(Integer.parseInt(hexStr, 16));
        } catch (Exception e) {
            armorColor = Color.fromRGB(team.color().value());
        }

        ItemStack helmet     = coloredArmor(Material.LEATHER_HELMET,     armorColor);
        ItemStack chestplate = coloredArmor(Material.LEATHER_CHESTPLATE,  armorColor);
        ItemStack leggings   = coloredArmor(Material.LEATHER_LEGGINGS,    armorColor);
        ItemStack boots      = coloredArmor(Material.LEATHER_BOOTS,       armorColor);

        player.getInventory().setHelmet(helmet);
        player.getInventory().setChestplate(chestplate);
        player.getInventory().setLeggings(leggings);
        player.getInventory().setBoots(boots);
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

    // ── Teleport na náhodné místo v aréně ────────────────────────────────────

    private void teleportToRandomArenaSpot(Player player) {
        if (currentArena == null || currentArena.getWorld() == null) return;
        Random rng = new Random();
        World world = currentArena.getWorld();

        // Náhodná XZ souřadnice uvnitř arény, Y = povrch
        int x = currentArena.minX() + rng.nextInt(currentArena.maxX() - currentArena.minX() + 1);
        int z = currentArena.minZ() + rng.nextInt(currentArena.maxZ() - currentArena.minZ() + 1);
        int y = currentArena.minY() + 2; // 2 bloky nad podlahou arény

        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        player.teleport(loc);
    }

    // ── Reset arény ───────────────────────────────────────────────────────────

    /**
     * Resetuje všechny bloky arény na WHITE_CONCRETE.
     * Probíhá po chunkcích asynchronně, setBlock se volá synchronně.
     */
    private void resetArena(ArenaData arena) {
        World world = arena.getWorld();
        if (world == null) return;

        new BukkitRunnable() {
            final int minX = arena.minX(), maxX = arena.maxX();
            final int minY = arena.minY(), maxY = arena.maxY();
            final int minZ = arena.minZ(), maxZ = arena.maxZ();

            @Override public void run() {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block b = world.getBlockAt(x, y, z);
                            if (b.getType().name().endsWith("_CONCRETE")) {
                                b.setType(Material.WHITE_CONCRETE, false);
                            }
                        }
                    }
                }
            }
        }.runTask(plugin); // synchronní (arény by měly být malé ~32x32)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  BLOKOVÉ POČÍTÁNÍ (volá TurfListener)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Přičte blok týmu (volá TurfListener při novém obarvení).
     */
    public void incrementTeamBlock(String teamId) {
        teamBlockCounts.merge(teamId, 1, Integer::sum);
    }

    /**
     * Odečte blok od týmu (volá TurfListener při přebarvení cizího bloku).
     */
    public void decrementTeamBlock(String teamId) {
        teamBlockCounts.merge(teamId, -1, (a, b) -> Math.max(0, a + b));
    }

    /**
     * Vrátí počet bloků daného týmu.
     */
    public int getTeamBlockCount(String teamId) {
        return teamBlockCounts.getOrDefault(teamId, 0);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GETTERY PRO SCOREBOARD / PAPI
    // ══════════════════════════════════════════════════════════════════════════

    public String getCurrentArenaName() {
        return currentArena != null ? currentArena.name() : "–";
    }

    public ArenaData getCurrentArena() {
        return currentArena;
    }

    public String getFormattedTimeLeft() {
        int minutes = gameTimeLeft / 60;
        int seconds = gameTimeLeft % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    public String getTeamPercentFormatted(String teamId) {
        if (totalArenaBlocks <= 0) return "0.0";
        double pct = (teamBlockCounts.getOrDefault(teamId, 0) * 100.0) / totalArenaBlocks;
        return String.format("%.1f", pct);
    }

    public int getLobbyCountdown()    { return lobbyCountdown; }
    public GameState getGameState()   { return gameState; }
    public int getInGamePlayerCount() { return inGamePlayers.size(); }
    public boolean isRunning()        { return gameState == GameState.RUNNING; }

    public Map<UUID, String> getInGamePlayers() {
        return Collections.unmodifiableMap(inGamePlayers);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TITULEK HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void showStartTitle() {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.game.start-title");
        String title    = sec != null ? sec.getString("title",    "") : "<bold><gradient:&#FF4444:&#4477FF>CHROMAWARS!</gradient></bold>";
        String subtitle = sec != null ? sec.getString("subtitle", "") : "<gray>Obarvi co nejvíce plochy!</gray>";
        int fi = sec != null ? sec.getInt("fade-in",  10) : 10;
        int st = sec != null ? sec.getInt("stay",     60) : 60;
        int fo = sec != null ? sec.getInt("fade-out", 20) : 20;
        Title t = buildTitle(title, subtitle, fi, st, fo);
        for (Player p : getIngamePlayers()) p.showTitle(t);
    }

    private void showEndTitle(String teamId, String teamName, String teamColor, String pct) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.game.end-title.win");
        String titleRaw    = (sec != null ? sec.getString("title",    "") : "<bold>{team_color}{team} vítězí!</bold>")
                .replace("{team}", teamName).replace("{team_color}", teamColor);
        String subtitleRaw = (sec != null ? sec.getString("subtitle", "") : "<gray>Obsazeno: <percent>%</gray>")
                .replace("{percent}", pct);
        int fi = sec != null ? sec.getInt("fade-in",  10) : 10;
        int st = sec != null ? sec.getInt("stay",     80) : 80;
        int fo = sec != null ? sec.getInt("fade-out", 20) : 20;
        Title t = buildTitle(titleRaw, subtitleRaw, fi, st, fo);
        for (Player p : getIngamePlayers()) p.showTitle(t);
    }

    private void showDrawTitle() {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.game.end-title.draw");
        String title    = sec != null ? sec.getString("title",    "<bold><gray>Remíza!</gray></bold>") : "<bold><gray>Remíza!</gray></bold>";
        String subtitle = sec != null ? sec.getString("subtitle", "<gray>Čas vypršel</gray>") : "<gray>Čas vypršel</gray>";
        int fi = sec != null ? sec.getInt("fade-in",  10) : 10;
        int st = sec != null ? sec.getInt("stay",     80) : 80;
        int fo = sec != null ? sec.getInt("fade-out", 20) : 20;
        Title t = buildTitle(title, subtitle, fi, st, fo);
        for (Player p : getIngamePlayers()) p.showTitle(t);
    }

    private void showCountdownTitle(int seconds) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.lobby.countdown-title");
        String titleRaw = (sec != null ? sec.getString("title", "<bold><yellow>{time}</yellow></bold>") : "<bold><yellow>{time}</yellow></bold>")
                .replace("{time}", String.valueOf(seconds));
        String sub = sec != null ? sec.getString("subtitle", "") : "";
        int fi = sec != null ? sec.getInt("fade-in",  0) : 0;
        int st = sec != null ? sec.getInt("stay",    25) : 25;
        int fo = sec != null ? sec.getInt("fade-out", 5) : 5;
        Title t = buildTitle(titleRaw, sub, fi, st, fo);
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
    }

    private Title buildTitle(String title, String subtitle, int fi, int st, int fo) {
        return Title.title(
                mm.deserialize(title),
                mm.deserialize(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fi * 50L),
                        Duration.ofMillis(st * 50L),
                        Duration.ofMillis(fo * 50L)));
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private void broadcastToLobby(String key, String... placeholders) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerSession s = plugin.<SessionManager>getSessionManager().getSession(p);
            if (s == null || s.isInLobby()) {
                plugin.getMessageManager().send(p, key, placeholders);
            }
        }
    }

    private void broadcastToIngame(String key, String... placeholders) {
        for (UUID uid : inGamePlayers.keySet()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) plugin.getMessageManager().send(p, key, placeholders);
        }
    }

    private void broadcastToAll(String key, String... placeholders) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.getMessageManager().send(p, key, placeholders);
        }
    }

    private List<Player> getEligiblePlayers() {
        List<Player> result = new ArrayList<>(Bukkit.getOnlinePlayers());
        return result;
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