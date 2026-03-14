package cz.xoam24.chromawars;

import cz.xoam24.chromawars.listeners.CombatListener;
import cz.xoam24.chromawars.listeners.TurfListener;
import cz.xoam24.chromawars.managers.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChromaWars extends JavaPlugin {

    private static ChromaWars instance;

    // ── Krok 1 ────────────────────────────────────────────────────────────────
    private ConfigManager    configManager;
    private MessageManager   messageManager;
    private DatabaseManager  databaseManager;
    private EloManager       eloManager;

    // ── Krok 2 ────────────────────────────────────────────────────────────────
    private WandManager      wandManager;
    private ArenaManager     arenaManager;
    private MenuManager      menuManager;
    private CommandManager   commandManager;
    private PlaceholderHook  placeholderHook;

    // ── Krok 3 ────────────────────────────────────────────────────────────────
    private SessionManager    sessionManager;
    private ScoreboardManager scoreboardManager;
    private GameManager       gameManager;
    private TurfListener      turfListener;
    private CombatListener    combatListener;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config (musí být první)
        this.configManager = new ConfigManager(this);
        configManager.loadAll();

        // 2. Zprávy
        this.messageManager = new MessageManager(this);
        messageManager.load();

        // 3. Databáze
        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Databáze se nezdařila inicializovat! Vypínám plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. ELO + Leaderboard cache
        this.eloManager = new EloManager(this);
        eloManager.updateLeaderboardCacheAsync();

        // 5. Wand, Arény, Menu, Příkazy
        this.wandManager    = new WandManager(this);
        this.arenaManager   = new ArenaManager(this);
        this.menuManager    = new MenuManager(this);
        this.commandManager = new CommandManager(this);

        // 6. SessionManager (musí být před GameManagerem!)
        this.sessionManager = new SessionManager(this);

        // 7. ScoreboardManager
        this.scoreboardManager = new ScoreboardManager(this);

        // 8. Listenery
        this.turfListener   = new TurfListener(this);
        this.combatListener = new CombatListener(this);

        // 9. GameManager (spustí lobby loop)
        this.gameManager = new GameManager(this);

        // 10. PlaceholderAPI (soft-depend)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderHook = new PlaceholderHook(this);
            placeholderHook.register();
            getLogger().info("PlaceholderAPI hook zaregistrován.");
        }

        getLogger().info("ChromaWars v" + getDescription().getVersion() + " načten!");
        getLogger().info("Arény: " + configManager.getArenaNames().size()
                + " | Týmy: " + configManager.getTeams().size());
    }

    @Override
    public void onDisable() {
        if (gameManager != null && gameManager.isRunning()) {
            gameManager.endGame(null);
        }
        if (combatListener != null) {
            combatListener.cancelAllRespawnTasks();
        }
        if (scoreboardManager != null) {
            scoreboardManager.stopAll();
            scoreboardManager.removeAllScoreboards();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("ChromaWars byl úspěšně vypnut.");
    }

    // ── Gettery – všechny silně typované (žádný Object / generický cast) ──────

    public static ChromaWars getInstance()           { return instance; }

    public ConfigManager     getConfigManager()      { return configManager; }
    public MessageManager    getMessageManager()     { return messageManager; }
    public DatabaseManager   getDatabaseManager()    { return databaseManager; }
    public EloManager        getEloManager()         { return eloManager; }

    public WandManager       getWandManager()        { return wandManager; }
    public ArenaManager      getArenaManager()       { return arenaManager; }
    public MenuManager       getMenuManager()        { return menuManager; }
    public CommandManager    getCommandManager()     { return commandManager; }
    public PlaceholderHook   getPlaceholderHook()    { return placeholderHook; }

    public SessionManager    getSessionManager()     { return sessionManager; }
    public ScoreboardManager getScoreboardManager()  { return scoreboardManager; }
    public GameManager       getGameManager()        { return gameManager; }
    public TurfListener      getTurfListener()       { return turfListener; }
    public CombatListener    getCombatListener()     { return combatListener; }
}