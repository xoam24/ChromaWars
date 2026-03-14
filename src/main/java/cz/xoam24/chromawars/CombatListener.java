package cz.xoam24.chromawars;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class CombatListener implements Listener {

    private final ChromaWars plugin;

    public CombatListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        PlayerSession session = PlayerSession.getSession(player);
        if (session == null || session.getState() != PlayerSession.State.PLAYING) return;

        if (player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            handleDeath(player, session);
        }
    }

    @EventHandler
    public void onVoidFall(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getLocation().getY() < 0) {
            PlayerSession session = PlayerSession.getSession(player);
            if (session != null && session.getState() == PlayerSession.State.PLAYING) {
                handleDeath(player, session);
            }
        }
    }

    private void handleDeath(Player player, PlayerSession session) {
        session.setState(PlayerSession.State.SPECTATOR);
        player.setGameMode(GameMode.SPECTATOR);
        player.setHealth(20.0);
        player.getInventory().clear();
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);

        new BukkitRunnable() {
            int time = 5;

            @Override
            public void run() {
                if (!player.isOnline() || session.getState() != PlayerSession.State.SPECTATOR) {
                    this.cancel();
                    return;
                }

                if (time > 0) {
                    player.sendTitle("§cZemřel jsi!", "Respawn za §e" + time + " §fsekund...", 0, 25, 0);
                    time--;
                } else {
                    // Respawn
                    session.setState(PlayerSession.State.PLAYING);
                    player.setGameMode(GameMode.SURVIVAL);
                    session.giveTeamArmor(player);
                    // player.teleport(nahodnySpawn);
                    player.sendTitle("§aRespawn!", "Zpět do boje!", 0, 20, 0);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}