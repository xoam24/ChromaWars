package cz.xoam24.chromawars;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PlaceholderHook extends PlaceholderExpansion {

    private final ChromaWars plugin;

    public PlaceholderHook(ChromaWars plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chromawars";
    }

    @Override
    public @NotNull String getAuthor() {
        return "xoam24";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // %chromawars_elo%
        if (params.equalsIgnoreCase("elo")) {
            return String.valueOf(plugin.getEloManager().getElo(player));
        }

        // %chromawars_arena%
        if (params.equalsIgnoreCase("arena")) {
            // Zde by byla metoda z ArenaManageru, např.:
            // return plugin.getArenaManager().getArena(player);
            return "Neni ve hre";
        }

        return null; // Placeholder nebyl nalezen
    }
}