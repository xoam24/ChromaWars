package cz.xoam24.chromawars;

import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final ChromaWars plugin;
    private FileConfiguration config;

    public ConfigManager(ChromaWars plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Generování výchozích hodnot, pokud chybí
        if (!config.contains("settings.default-elo")) {
            config.set("settings.default-elo", 1000);

            // Výchozí týmy
            config.set("teams.red.name", "Červení");
            config.set("teams.red.hex", "&#FF0000");
            config.set("teams.red.material", "RED_CONCRETE");
            config.set("teams.red.max-players", 5);

            config.set("teams.blue.name", "Modří");
            config.set("teams.blue.hex", "&#0000FF");
            config.set("teams.blue.material", "BLUE_CONCRETE");
            config.set("teams.blue.max-players", 5);

            plugin.saveConfig();
        }
    }

    public int getDefaultElo() {
        return config.getInt("settings.default-elo", 1000);
    }

    public FileConfiguration getConfig() {
        return config;
    }
}