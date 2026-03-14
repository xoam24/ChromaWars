package cz.xoam24.chromawars.listeners;

import cz.xoam24.chromawars.ChromaWars;
import cz.xoam24.chromawars.managers.GameManager;
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
import java.util.regex.Pattern;

public class CombatListener implements Listener {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private static final int RESPAWN_SECONDS = 5;

    private final Map<UUID, BukkitTask> respawnTasks = new ConcurrentHashMap<>();

    public CombatListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DAMAGE
    // ══════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════
    //  DEATH (záchytná síť)
    // ══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        GameManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;

        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return;

        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        event.setCancelled(true);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.setHealth(20.0);
            handleDeath(player, session, gm);
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VOID DETECTION
    // ══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
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

        if (event.getTo().getY() < arena.minY() - 10) {
            handleDeath(player, session, gm);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HANDLE DEATH
    // ══════════════════════════════════════════════════════════════════════

    private void handleDeath(Player player, PlayerSession session, GameManager gm) {
        if (session.isSpectator()) return;

        session.setState(PlayerSession.State.SPECTATOR);
        player.setHealth(20.0);
        player.setGameMode(GameMode.SPECTATOR);
        player.setFoodLevel(20);
        player.setSaturation(20);

        // Teleport do středu arény (výše pro dobrý výhled)
        ArenaData arena = gm.getCurrentArena();
        if (arena != null && arena.getWorld() != null) {
            Location center = arena.getCenter();
            center.setY(center.getY() + 15);
            player.teleport(center);
        }

        showDeathTitle(player);
        startRespawnCountdown(player, session, gm, RESPAWN_SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RESPAWN ODPOČET
    // ══════════════════════════════════════════════════════════════════════

    private void startRespawnCountdown(Player player, PlayerSession session,
                                       GameManager gm, int totalSeconds) {
        cancelRespawnTask(player.getUniqueId());

        BukkitTask task = new BukkitRunnable() {
            int remaining = totalSeconds;

            @Override
            public void run() {
                if (!player.isOnline() || !gm.isRunning()) {
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

    // ══════════════════════════════════════════════════════════════════════
    //  PERFORM RESPAWN
    // ══════════════════════════════════════════════════════════════════════

    private void performRespawn(Player player, PlayerSession session, GameManager gm) {
        if (!player.isOnline()) return;

        session.setState(PlayerSession.State.PLAYING);
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20);

        if (session.getTeamId() != null) {
            gm.giveTeamArmorPublic(player, session.getTeamId());
        }

        // ── Spawn: preferujeme nastavený team spawn ────────────────────────
        Location spawnLoc = null;
        if (session.getTeamId() != null && gm.getCurrentArena() != null) {
            spawnLoc = gm.getTeamSpawnLocation(
                    gm.getCurrentArena().name(), session.getTeamId());
        }
        if (spawnLoc != null) {
            player.teleport(spawnLoc);
        } else {
            gm.teleportToRandomArenaSpot(player);
        }

        showRespawnTitle(player);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  QUIT
    // ══════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelRespawnTask(player.getUniqueId());
        TurfListener tl = plugin.getTurfListener();
        if (tl != null) tl.clearPlayerCache(player.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TITLE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void showDeathTitle(Player player) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.death.spectator-title");
        String title = sec != null
                ? sec.getString("title", "<bold><red>✖ Zemřel jsi!</red></bold>")
                : "<bold><red>✖ Zemřel jsi!</red></bold>";
        String sub = sec != null
                ? sec.getString("subtitle", "<gray>Respawn za " + RESPAWN_SECONDS + "s...</gray>")
                : "<gray>Respawn za " + RESPAWN_SECONDS + "s...</gray>";
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
                parseMM(title),
                parseMM(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fi * 50L),
                        Duration.ofMillis(st * 50L),
                        Duration.ofMillis(fo * 50L)));
    }

    private net.kyori.adventure.text.Component parseMM(String input) {
        if (input == null || input.isBlank())
            return net.kyori.adventure.text.Component.empty();
        String converted = LEGACY_HEX.matcher(input)
                .replaceAll(mr -> "<#" + mr.group(1) + ">");
        return mm.deserialize(converted);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    private void cancelRespawnTask(UUID uuid) {
        BukkitTask t = respawnTasks.remove(uuid);
        if (t != null && !t.isCancelled()) t.cancel();
    }

    public void cancelAllRespawnTasks() {
        respawnTasks.forEach((uid, t) -> { if (t != null && !t.isCancelled()) t.cancel(); });
        respawnTasks.clear();
    }
}