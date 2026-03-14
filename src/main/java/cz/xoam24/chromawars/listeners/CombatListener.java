package cz.xoam24.chromawars.listeners;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.managers.GameManager;
import cz.xoam24.chromawars.managers.ScoreboardManager;
import cz.xoam24.chromawars.managers.SessionManager;
import cz.xoam24.chromawars.model.ArenaData;
import cz.xoam24.chromawars.model.PlayerSession;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatListener implements Listener {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private static final int DEFAULT_RESPAWN_SECONDS = 5;

    // UUID → respawn odpočet task
    private final Map<UUID, BukkitTask> respawnTasks = new ConcurrentHashMap<>();

    public CombatListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DAMAGE – zachytíme letální poškození
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        GameManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;

        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return;

        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        double resultHealth = player.getHealth() - event.getFinalDamage();
        if (resultHealth <= 0) {
            event.setCancelled(true);
            handleDeath(player, session, gm);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DEATH – záchytná síť pro nativní smrt (/kill, fall into void apod.)
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        GameManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;

        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return;

        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        // Zrušíme nativní smrt: keep items, no death screen
        event.setCancelled(true);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.setHealth(20.0);
            handleDeath(player, session, gm);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VOID DETECTION – pád pod dno arény
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Optimalizace: kontrolujeme pouze Y
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        GameManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;

        Player player = event.getPlayer();

        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return;

        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        ArenaData arena = gm.getCurrentArena();
        if (arena == null) return;

        // Pád více než 10 bloků pod dno arény
        if (event.getTo().getY() < arena.minY() - 10) {
            handleDeath(player, session, gm);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HANDLESMRT – přechod do SPECTATOR + respawn odpočet
    // ══════════════════════════════════════════════════════════════════════════

    private void handleDeath(Player player, PlayerSession session, GameManager gm) {
        // Zabráníme dvojímu spuštění
        if (session.isSpectator()) return;

        session.setState(PlayerSession.State.SPECTATOR);
        player.setHealth(20.0);
        player.setGameMode(GameMode.SPECTATOR);
        player.setFoodLevel(20);
        player.setSaturation(20);

        // Teleport do středu arény (nahoře pro výhled)
        ArenaData arena = gm.getCurrentArena();
        if (arena != null && arena.getWorld() != null) {
            Location center = arena.getCenter();
            center.setY(center.getY() + 15);
            player.teleport(center);
        }

        // Zobrazíme "zemřel jsi" title
        showDeathTitle(player);

        // Spustíme odpočet
        startRespawnCountdown(player, session, gm, DEFAULT_RESPAWN_SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RESPAWN ODPOČET
    // ══════════════════════════════════════════════════════════════════════════

    private void startRespawnCountdown(Player player, PlayerSession session,
                                       GameManager gm, int totalSeconds) {
        cancelRespawnTask(player.getUniqueId());

        BukkitTask task = new BukkitRunnable() {
            int remaining = totalSeconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    respawnTasks.remove(player.getUniqueId());
                    return;
                }
                if (!gm.isRunning()) {
                    cancel();
                    respawnTasks.remove(player.getUniqueId());
                    return;
                }

                if (remaining > 0) {
                    showRespawnCountdownTitle(player, remaining);
                    remaining--;
                } else {
                    cancel();
                    respawnTasks.remove(player.getUniqueId());
                    performRespawn(player, session, gm);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);

        respawnTasks.put(player.getUniqueId(), task);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RESPAWN – návrat do PLAYING
    // ══════════════════════════════════════════════════════════════════════════

    private void performRespawn(Player player, PlayerSession session, GameManager gm) {
        if (!player.isOnline()) return;

        session.setState(PlayerSession.State.PLAYING);
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20);

        // Brnění zpět
        if (session.getTeamId() != null) {
            gm.giveTeamArmorPublic(player, session.getTeamId());
        }

        // Teleport na náhodné místo v aréně
        teleportToRandomArenaSpot(player, gm);

        // Respawn title
        showRespawnTitle(player);
    }

    private void teleportToRandomArenaSpot(Player player, GameManager gm) {
        ArenaData arena = gm.getCurrentArena();
        if (arena == null || arena.getWorld() == null) return;

        java.util.Random rng = new java.util.Random();
        int rangeX = arena.maxX() - arena.minX();
        int rangeZ = arena.maxZ() - arena.minZ();
        int x = arena.minX() + (rangeX > 0 ? rng.nextInt(rangeX + 1) : 0);
        int z = arena.minZ() + (rangeZ > 0 ? rng.nextInt(rangeZ + 1) : 0);
        int y = arena.minY() + 2;

        Location loc = new Location(arena.getWorld(), x + 0.5, y, z + 0.5);
        player.teleport(loc);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ODPOJENÍ BĚHEM HRY
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();

        cancelRespawnTask(uid);

        // Vyčistíme turf cache
        TurfListener tl = plugin.getTurfListener();
        if (tl != null) tl.clearPlayerCache(uid);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TITLE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void showDeathTitle(Player player) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.death.spectator-title");
        String title = sec != null
                ? sec.getString("title", "<bold><red>✖ Zemřel jsi!</red></bold>")
                : "<bold><red>✖ Zemřel jsi!</red></bold>";
        String sub = sec != null
                ? sec.getString("subtitle", "<gray>Respawn za " + DEFAULT_RESPAWN_SECONDS + "s...</gray>")
                .replace("<time>", String.valueOf(DEFAULT_RESPAWN_SECONDS))
                : "<gray>Respawn za " + DEFAULT_RESPAWN_SECONDS + "s...</gray>";
        int fi = sec != null ? sec.getInt("fade-in",  5) : 5;
        int st = sec != null ? sec.getInt("stay",    40) : 40;
        int fo = sec != null ? sec.getInt("fade-out", 5) : 5;
        player.showTitle(buildTitle(title, sub, fi, st, fo));
    }

    private void showRespawnCountdownTitle(Player player, int seconds) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.death.countdown-title");
        String titleRaw = (sec != null
                ? sec.getString("title", "<bold><red>{time}</red></bold>")
                : "<bold><red>{time}</red></bold>")
                .replace("{time}", String.valueOf(seconds));
        String sub = sec != null
                ? sec.getString("subtitle", "<gray>Respawn...</gray>")
                : "<gray>Respawn...</gray>";
        int fi = sec != null ? sec.getInt("fade-in",  0) : 0;
        int st = sec != null ? sec.getInt("stay",    25) : 25;
        int fo = sec != null ? sec.getInt("fade-out", 5) : 5;
        player.showTitle(buildTitle(titleRaw, sub, fi, st, fo));
    }

    private void showRespawnTitle(Player player) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.death.respawn-title");
        String title = sec != null
                ? sec.getString("title", "<bold><green>✔ Respawn!</green></bold>")
                : "<bold><green>✔ Respawn!</green></bold>";
        String sub = sec != null ? sec.getString("subtitle", "") : "";
        int fi = sec != null ? sec.getInt("fade-in",  5) : 5;
        int st = sec != null ? sec.getInt("stay",    20) : 20;
        int fo = sec != null ? sec.getInt("fade-out", 5) : 5;
        player.showTitle(buildTitle(title, sub, fi, st, fo));
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

    // ── Cleanup helpers ───────────────────────────────────────────────────────

    private void cancelRespawnTask(UUID uuid) {
        BukkitTask task = respawnTasks.remove(uuid);
        if (task != null && !task.isCancelled()) task.cancel();
    }

    public void cancelAllRespawnTasks() {
        respawnTasks.forEach((uid, task) -> {
            if (task != null && !task.isCancelled()) task.cancel();
        });
        respawnTasks.clear();
    }
}