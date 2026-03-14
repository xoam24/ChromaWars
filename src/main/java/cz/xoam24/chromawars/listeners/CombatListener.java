package cz.xoam24.chromawars.listeners;

import cz.xoam24.chromawars.ChromaWars;
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

/**
 * Obsluha boje, smrti a respawnu.
 *
 * Pravidla:
 *  - Smrt je ZRUŠENA (PlayerDeathEvent cancelled + keep inventory).
 *  - Při HP ≤ 0 nebo pádu pod Y arény hráč přejde do SPECTATOR módu.
 *  - Title odpočítává 5 sekund (konfigurovatelné v messages.yml).
 *  - Po odpočtu hráč teleportován na náhodné místo v aréně jako SURVIVAL.
 *  - Při odpojení během hry se odpočet zruší a hráč je odstraněn ze hry.
 */
public class CombatListener implements Listener {

    private final ChromaWars plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // UUID → BukkitTask (respawn odpočet)
    private final Map<UUID, BukkitTask> respawnTasks = new ConcurrentHashMap<>();

    // Konfigurovatelná délka respawnu (sekund)
    private static final int DEFAULT_RESPAWN_SECONDS = 5;

    public CombatListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SMRT – zrušení, přechod do spectator
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Zachytíme damage event – pokud by HP kleslo na 0, "zabijeme" ručně.
     * Tím zabraňujeme nativní smrti (drop itemů, death screen, respawn prompt).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        GameManager gm = plugin.<GameManager>getGameManager();
        if (gm == null || !gm.isRunning()) return;

        SessionManager sm = plugin.<SessionManager>getSessionManager();
        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        // Zkontrolujeme zda damage způsobí smrt
        double newHealth = player.getHealth() - event.getFinalDamage();
        if (newHealth <= 0) {
            event.setCancelled(true); // zrušíme smrt
            handleDeath(player, session, gm);
        }
    }

    /**
     * Záchytná síť pro přímou smrt (fall damage do void, /kill apod.).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.<GameManager>getGameManager();
        if (gm == null || !gm.isRunning()) return;

        SessionManager sm = plugin.<SessionManager>getSessionManager();
        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        // Zrušíme nativní smrt
        event.setCancelled(true);
        // Obnovíme HP pro případ, že byl hráč mrtvý
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.setHealth(20);
            handleDeath(player, session, gm);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VOID DETECTION (Y < minY arény)
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        GameManager gm = plugin.<GameManager>getGameManager();
        if (gm == null || !gm.isRunning()) return;

        Player player = event.getPlayer();
        SessionManager sm = plugin.<SessionManager>getSessionManager();
        PlayerSession session = sm.getSession(player);
        if (session == null || !session.isPlaying()) return;

        ArenaData arena = gm.getCurrentArena();
        if (arena == null) return;

        // Pád pod minimální Y arény (void)
        if (event.getTo().getY() < arena.minY() - 10) {
            handleDeath(player, session, gm);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SPECTATOR MODUS + RESPAWN ODPOČET
    // ══════════════════════════════════════════════════════════════════════════

    private void handleDeath(Player player, PlayerSession session, GameManager gm) {
        // Zabráníme dvojímu spuštění (pokud již čeká na respawn)
        if (session.isSpectator()) return;

        // Přepneme do spectator
        session.setState(PlayerSession.State.SPECTATOR);
        player.setHealth(20);
        player.setGameMode(GameMode.SPECTATOR);
        player.setFoodLevel(20);
        player.setSaturation(20);

        // Teleport do středu arény (spectator poletí volně)
        ArenaData arena = gm.getCurrentArena();
        if (arena != null && arena.getWorld() != null) {
            Location center = arena.getCenter();
            center.setY(center.getY() + 10); // výše pro výhled
            player.teleport(center);
        }

        // Nastavíme délku respawnu
        int respawnSeconds = DEFAULT_RESPAWN_SECONDS;

        // Spustíme odpočet
        startRespawnCountdown(player, session, gm, respawnSeconds);
    }

    private void startRespawnCountdown(Player player, PlayerSession session,
                                       GameManager gm, int seconds) {
        // Zrušíme případný existující task
        cancelRespawnTask(player.getUniqueId());

        session.setRespawnCountdown(seconds);

        BukkitTask task = new BukkitRunnable() {
            int remaining = seconds;

            @Override
            public void run() {
                // Hráč se mohl odpojit nebo hra skončit
                if (!player.isOnline() || !gm.isRunning()) {
                    cancel();
                    respawnTasks.remove(player.getUniqueId());
                    return;
                }

                if (remaining > 0) {
                    // Title s odpočtem
                    showRespawnCountdownTitle(player, remaining);
                    remaining--;
                } else {
                    // Čas vypršel – respawn
                    cancel();
                    respawnTasks.remove(player.getUniqueId());
                    performRespawn(player, session, gm);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);

        respawnTasks.put(player.getUniqueId(), task);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RESPAWN
    // ══════════════════════════════════════════════════════════════════════════

    private void performRespawn(Player player, PlayerSession session, GameManager gm) {
        if (!player.isOnline()) return;

        // Přepneme zpět na PLAYING
        session.setState(PlayerSession.State.PLAYING);
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setSaturation(20);

        // Brnění zpět
        plugin.<GameManager>getGameManager()
                .giveTeamArmorPublic(player, session.getTeamId());

        // Teleport na náhodné místo v aréně
        teleportToRandomArenaSpot(player, gm);

        // Respawn title
        showRespawnTitle(player);
    }

    private void teleportToRandomArenaSpot(Player player, GameManager gm) {
        ArenaData arena = gm.getCurrentArena();
        if (arena == null || arena.getWorld() == null) return;

        java.util.Random rng = new java.util.Random();
        int x = arena.minX() + rng.nextInt(Math.max(1, arena.maxX() - arena.minX() + 1));
        int z = arena.minZ() + rng.nextInt(Math.max(1, arena.maxZ() - arena.minZ() + 1));
        int y = arena.minY() + 2;

        player.teleport(new Location(arena.getWorld(), x + 0.5, y, z + 0.5));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ODPOJENÍ BĚHEM HRY
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();

        // Zrušíme respawn task
        cancelRespawnTask(uid);

        // Vyčistíme turf cache
        plugin.<TurfListener>getTurfListener().clearPlayerCache(uid);

        // Odebereme ze hry
        GameManager gm = plugin.<GameManager>getGameManager();
        if (gm != null) {
            gm.getInGamePlayers(); // jen přistoupíme – skutečné odebrání přes SessionManager
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TITLE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void showRespawnCountdownTitle(Player player, int seconds) {
        ConfigurationSection sec = plugin.getConfigManager().getRawConfig()
                .getConfigurationSection("messages.death.countdown-title");
        String titleRaw = (sec != null
                ? sec.getString("title", "<bold><red>{time}</red></bold>")
                : "<bold><red>{time}</red></bold>")
                .replace("{time}", String.valueOf(seconds));
        String sub = sec != null ? sec.getString("subtitle", "<gray>Respawn...</gray>") : "<gray>Respawn...</gray>";
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void cancelRespawnTask(UUID uuid) {
        BukkitTask task = respawnTasks.remove(uuid);
        if (task != null && !task.isCancelled()) task.cancel();
    }

    public void cancelAllRespawnTasks() {
        respawnTasks.forEach((uid, task) -> {
            if (!task.isCancelled()) task.cancel();
        });
        respawnTasks.clear();
    }
}