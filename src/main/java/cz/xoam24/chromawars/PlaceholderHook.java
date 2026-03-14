package cz.xoam24.chromawars;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
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
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("player_elo") && player != null) {
            // V reálu napojíme na PlayerSession v Kroku 3
            return "1000";
        }
        if (params.equals("player_team") && player != null) {
            // V reálu napojíme na PlayerSession v Kroku 3
            return "Žádný";
        }
        if (params.equals("player_position") && player != null && player.getName() != null) {
            int pos = plugin.getEloManager().getPlayerPosition(player.getName());
            return pos > 0 ? String.valueOf(pos) : "N/A";
        }

        // Dynamický Cache Regex: %chromawars_<cislo>_name% nebo %chromawars_<cislo>_elo%
        // Hledáme pattern: číslo_typ (např. 1_name)
        String[] split = params.split("_");
        if (split.length == 2) {
            try {
                int position = Integer.parseInt(split[0]);
                String type = split[1];

                if (type.equalsIgnoreCase("name")) {
                    return plugin.getEloManager().getNameAtPosition(position);
                } else if (type.equalsIgnoreCase("elo")) {
                    return plugin.getEloManager().getEloAtPosition(position);
                } else if (type.equalsIgnoreCase("position")) {
                    return String.valueOf(position);
                }
            } catch (NumberFormatException e) {
                // Pokud to není číslo, ignorujeme
            }
        }

        return null; // Neznámý placeholder
    }
}