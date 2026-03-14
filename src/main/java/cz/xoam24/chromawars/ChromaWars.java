package cz.xoam24.chromawars;

import org.bukkit.plugin.java.JavaPlugin;

public class ChromaWars extends JavaPlugin {

    private static ChromaWars instance;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private EloManager eloManager;

    @Override
    public void onEnable() {
        instance = this;

        // Inicializace Configu a Zpráv
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);

        // Inicializace Databáze
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.connect();
        this.databaseManager.createTables();

        // Inicializace ELO a načtení Cache
        this.eloManager = new EloManager(this);
        this.eloManager.updateLeaderboardCache();

        getLogger().info("ChromaWars byl uspesne spusten!");
    }

    @Override
    public void onDisable() {
        if (this.databaseManager != null) {
            this.databaseManager.disconnect();
        }
        getLogger().info("ChromaWars byl vypnut.");
    }

    public static ChromaWars getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public EloManager getEloManager() { return eloManager; }
}