package cz.xoam24.chromawars;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChromaWars extends JavaPlugin {

    private ArenaManager arenaManager;
    private EloManager eloManager;
    private GameManager gameManager;
    private MenuManager menuManager;
    // Sem si časem přidej DatabaseManager, ScoreboardManager atd.

    @Override
    public void onEnable() {
        // Uložení výchozího config.yml
        saveDefaultConfig();

        // Inicializace manažerů (Záleží na pořadí, pokud na sebe odkazují!)
        this.eloManager = new EloManager(this);
        this.arenaManager = new ArenaManager(this);
        this.gameManager = new GameManager(this);
        this.menuManager = new MenuManager(this);

        // Registrace eventů (např. MenuManager potřebuje Listener pro klikání)
        getServer().getPluginManager().registerEvents(this.menuManager, this);
        // getServer().getPluginManager().registerEvents(new CombatListener(this), this);

        // PlaceholderAPI Hook
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
            getLogger().info("PlaceholderAPI uspesne napojeno!");
        }

        getLogger().info("ChromaWars byl uspesne spusten!");
    }

    @Override
    public void onDisable() {
        getLogger().info("ChromaWars byl uspesne vypnut!");
    }

    // --- GETTERY --- (Tyto metody opraví ty "cannot find symbol" errory)
    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public EloManager getEloManager() {
        return eloManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }
}