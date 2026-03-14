package cz.xoam24.chromawars;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerSession {

    private static final Map<UUID, PlayerSession> sessions = new HashMap<>();

    public enum State { LOBBY, PLAYING, SPECTATOR }
    public enum Team { NONE, RED, BLUE }

    private final UUID uuid;
    private final String name;
    private int elo;
    private State state;
    private Team team;

    public PlayerSession(Player player, int initialElo) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.elo = initialElo;
        this.state = State.LOBBY;
        this.team = Team.NONE;
        sessions.put(uuid, this);
    }

    public static PlayerSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public static PlayerSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public static void removeSession(Player player) {
        sessions.remove(player.getUniqueId());
    }

    public static Map<UUID, PlayerSession> getAllSessions() {
        return sessions;
    }

    public void giveTeamArmor(Player player) {
        Color color = (team == Team.RED) ? Color.RED : Color.BLUE;
        ItemStack helmet = createColoredArmor(Material.LEATHER_HELMET, color);
        ItemStack chestplate = createColoredArmor(Material.LEATHER_CHESTPLATE, color);
        ItemStack leggings = createColoredArmor(Material.LEATHER_LEGGINGS, color);
        ItemStack boots = createColoredArmor(Material.LEATHER_BOOTS, color);

        player.getInventory().setHelmet(helmet);
        player.getInventory().setChestplate(chestplate);
        player.getInventory().setLeggings(leggings);
        player.getInventory().setBoots(boots);
    }

    private ItemStack createColoredArmor(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        if (meta != null) {
            meta.setColor(color);
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    // Gettery a Settery
    public int getElo() { return elo; }
    public void setElo(int elo) { this.elo = elo; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
}