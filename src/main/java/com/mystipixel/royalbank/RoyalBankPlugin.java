package com.mystipixel.royalbank;

import com.mystipixel.royalbank.command.BankCommand;
import com.mystipixel.royalbank.service.BankService;
import com.mystipixel.royalbank.config.ConfigValidator;
import com.mystipixel.royalbank.config.LevelManager;
import com.mystipixel.royalbank.data.BankDatabase;
import com.mystipixel.royalbank.gui.BankGui;
import com.mystipixel.royalbank.hooks.RoyalBankPlaceholderExpansion;
import com.mystipixel.royalbank.hooks.VaultHook;
import com.mystipixel.royalbank.message.MessageManager;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class RoyalBankPlugin extends JavaPlugin {

    /** bStats project id. Identifies the plugin, not the server, so it is fixed rather than configurable. */
    private static final int BSTATS_PLUGIN_ID = 31375;
    private VaultHook vaultHook;
    private BankDatabase database;
    private LevelManager levelManager;
    private BankService bankService;
    private BankGui bankGui;
    private MessageManager messageManager;
    private RoyalBankPlaceholderExpansion placeholderExpansion;
    private Metrics metrics;
    private boolean fullyEnabled = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultGuiFiles();
        this.messageManager = new MessageManager(this);
        this.vaultHook = new VaultHook(this);

        this.levelManager = new LevelManager(this);
        this.levelManager.reload();
        if (levelManager.getLevels().isEmpty()) {
            getLogger().severe("No valid RoyalBank levels are configured. Plugin will disable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.database = new BankDatabase(this);
        if (!database.connect()) {
            getLogger().severe("Could not open the RoyalBank SQLite database. Plugin will disable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Vault is a hard dependency, but the economy *provider* (EssentialsX, CMI, an EcoBits currency
        // with vault:true, etc.) can register slightly after we enable. Try now; if it isn't ready yet,
        // wait for it to register instead of disabling outright.
        if (vaultHook.setupEconomy()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalBank is waiting for one to register"
                    + " (install an economy plugin, or enable vault:true on a currency). /bank is unavailable until then.");
            getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
            // Fallback in case the provider registered before our listener was active.
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    /** Completes startup once a Vault economy provider is available. Idempotent. */
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        this.bankService = new BankService(this, database, levelManager, vaultHook);
        database.migrateSharedAccountsToPlayerBanks(levelManager.getStartingLevel());
        // Expose the shared-account API so other plugins (e.g. RoyalSkyblock coop banks) can soft-depend on it.
        getServer().getServicesManager().register(
                com.mystipixel.royalbank.api.RoyalBankAPI.class, bankService, this,
                org.bukkit.plugin.ServicePriority.Normal);
        setupAuditSink();
        this.bankGui = new BankGui(this, bankService);
        new ConfigValidator(this, levelManager).validate();
        pruneTransactionsIfEnabled();

        BankCommand bankCommand = new BankCommand(this, bankService, levelManager, bankGui);
        PluginCommand command = getCommand("bank");
        if (command != null) {
            command.setExecutor(bankCommand);
            command.setTabCompleter(bankCommand);
        }

        getServer().getPluginManager().registerEvents(bankService, this);
        getServer().getPluginManager().registerEvents(bankGui, this);
        setupPlaceholders();
        setupMetrics();

        // Cache any players already online (covers a live plugin reload, not just a fresh start).
        for (Player player : getServer().getOnlinePlayers()) {
            bankService.loadAccount(player);
        }

        getLogger().info("RoyalBank enabled with " + levelManager.getLevels().size() + " configured levels.");
    }

    private void tryLateEnable() {
        if (fullyEnabled) {
            return;
        }
        if (vaultHook.setupEconomy()) {
            finishEnable();
        } else {
            getLogger().severe("Still no Vault economy provider after waiting. Install an economy plugin"
                    + " (e.g. EssentialsX) or enable vault:true on an EcoBits currency, then run /reload confirm or restart.");
        }
    }

    /** Listens for an economy provider registering after we enabled, then completes startup once. */
    private final class EconomyWaiter implements Listener {
        @EventHandler
        public void onServiceRegister(ServiceRegisterEvent event) {
            if (!fullyEnabled && event.getProvider().getService() == Economy.class && vaultHook.setupEconomy()) {
                getLogger().info("Vault economy provider detected. Finishing RoyalBank startup.");
                finishEnable();
            }
        }
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (database != null) {
            database.close();
        }
    }

    private void saveDefaultGuiFiles() {
        saveGuiResource("main.yml");
        saveGuiResource("deposit.yml");
        saveGuiResource("withdraw.yml");
        saveGuiResource("transactions.yml");
        saveGuiResource("confirm-upgrade.yml");
    }

    private void saveGuiResource(String fileName) {
        java.io.File file = new java.io.File(getDataFolder(), "gui/" + fileName);
        if (!file.exists()) {
            saveResource("gui/" + fileName, false);
        }
    }

    /**
     * If the EconGuard core is installed, report bank activity to it instead of the built-in monitor.
     * The EconGuard-referencing class is only loaded inside this guard, so RoyalBank still runs fine
     * (with its built-in anti-abuse) when EconGuard is absent.
     */
    private void setupAuditSink() {
        if (getServer().getPluginManager().getPlugin("EconGuard") == null) {
            return;
        }
        try {
            bankService.setAuditSink(new com.mystipixel.royalbank.hooks.EconGuardAuditSink());
            getLogger().info("EconGuard detected; reporting bank activity to the central anti-abuse core.");
        } catch (Throwable throwable) {
            getLogger().warning("EconGuard present but its hook failed to load (" + throwable.getMessage()
                    + "); using RoyalBank's built-in anti-abuse instead.");
        }
    }

    private void setupPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        placeholderExpansion = new RoyalBankPlaceholderExpansion(this, bankService);
        placeholderExpansion.register();
        getLogger().info("PlaceholderAPI placeholders registered with identifier %royalbank_...%. ");
    }

    private void setupMetrics() {

        this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("account_tiers_configured", () -> String.valueOf(levelManager.getLevels().size())));
        metrics.addCustomChart(new SimplePie("interest_cooldown_hours", () -> String.valueOf(getConfig().getLong("settings.interest-cooldown-hours", 31L))));
        metrics.addCustomChart(new SimplePie("first_deposit_bonus_enabled", () -> String.valueOf(getConfig().getBoolean("settings.first-deposit-bonus.enabled", true))));
        metrics.addCustomChart(new SimplePie("upgrade_unlock_enabled", () -> String.valueOf(getConfig().getDouble("settings.upgrade-unlock-combined-balance", 0.0) > 0.0)));
    }

    public void reloadRoyalBank() {
        reloadConfig();
        messageManager.reload();
        levelManager.reload();
        if (levelManager.getLevels().isEmpty()) {
            getLogger().severe("Reload failed: no valid RoyalBank levels are configured. Keeping plugin enabled, but bank actions may fail until config is fixed.");
        }
        if (bankGui != null) {
            bankGui.reload();
        }
        new ConfigValidator(this, levelManager).validate();
        pruneTransactionsIfEnabled();
    }

    public BankDatabase getDatabase() {
        return database;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    private void pruneTransactionsIfEnabled() {
        if (database == null || !getConfig().getBoolean("transactions.prune-on-startup", true)) {
            return;
        }
        int maxPerPlayer = getConfig().getInt("transactions.max-per-player", 100);
        // Runs on a dedicated connection off the main thread so the full-table scan never stalls the server.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int removed = database.pruneTransactions(maxPerPlayer);
            if (removed > 0) {
                getLogger().info("Pruned " + removed + " old bank transactions. Keeping newest " + maxPerPlayer + " per player.");
            }
        });
    }
}
