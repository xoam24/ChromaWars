package cz.xoam24.chromawars;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class TurfListener implements Listener {

    private final ChromaWars plugin;

    public TurfListener(ChromaWars plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Obří optimalizace: Paper API metoda hasChangedBlock() - spustí se jen při přechodu z bloku na blok
        if (!event.hasChangedBlock()) return;

        Player player = event.getPlayer();
        PlayerSession session = PlayerSession.getSession(player);

        if (session == null || session.getState() != PlayerSession.State.PLAYING) return;
        if (!plugin.getGameManager().isInGame()) return;

        Block blockBelow = event.getTo().clone().subtract(0, 1, 0).getBlock();
        Material type = blockBelow.getType();

        Material teamMaterial = (session.getTeam() == PlayerSession.Team.RED) ? Material.RED_CONCRETE : Material.BLUE_CONCRETE;

        // Barvíme pouze povolené bloky (např. výchozí bílý beton nebo beton nepřátel)
        if (type == Material.WHITE_CONCRETE || (type == Material.RED_CONCRETE || type == Material.BLUE_CONCRETE) && type != teamMaterial) {
            plugin.getGameManager().addBlock(session.getTeam(), type);
            blockBelow.setType(teamMaterial);
        }
    }
}