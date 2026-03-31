package com.survivalcore;

import com.survivalcore.data.DatabaseManager;
import com.survivalcore.modules.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Classe principale du plugin SurvivalCore.
 * Gère le cycle de vie de tous les modules et de la base de données.
 */
public final class SurvivalCore extends JavaPlugin {

    private static SurvivalCore instance;
    private DatabaseManager databaseManager;
    private final List<CoreModule> modules = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;

        // Sauvegarder la config par défaut
        saveDefaultConfig();

        // Initialiser la base de données (async)
        databaseManager = new DatabaseManager(this);
        databaseManager.initialize().whenComplete((v, ex) -> {
            if (ex != null) {
                getLogger().log(Level.SEVERE, "Échec de l'initialisation de la base de données. Arrêt du plugin.", ex);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Une fois la DB prête, activer les modules sur le thread principal
            getServer().getScheduler().runTask(this, this::enableModules);
        });

        getLogger().info("SurvivalCore v" + getDescription().getVersion() + " — démarrage en cours...");
    }

    /**
     * Enregistre et active tous les modules.
     * Appelé sur le thread principal une fois la DB initialisée.
     */
    private void enableModules() {
        // Enregistrer tous les modules
        registerModule(new EconomyModule());
        registerModule(new MenuModule());
        registerModule(new ClassModule());
        registerModule(new JobModule());
        registerModule(new SkillModule());
        registerModule(new QuestModule());
        registerModule(new WeeklyModule());
        registerModule(new ShopModule());
        registerModule(new AuctionHouseModule());
        registerModule(new ModPageModule());
        registerModule(new DeathModule());
        registerModule(new MinimapModule());
        registerModule(new ArcModule());
        registerModule(new LeaderboardModule());
        registerModule(new AnnouncerModule());
        registerModule(new ClaimModule());

        // Activer chaque module
        for (CoreModule module : modules) {
            try {
                module.onEnable(this);
                getLogger().info("Module [" + module.getName() + "] activé.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Erreur lors de l'activation du module [" + module.getName() + "]", e);
            }
        }

        // Commande admin
        getCommand("admin").setExecutor(new AdminCommand());

        getLogger().info("SurvivalCore — tous les modules sont chargés (" + modules.size() + " modules).");
    }

    // ─── Commande /admin ────────────────────────────────────────

    private class AdminCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage("§cUsage : /admin <reload|givemoney|setweekly>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    reloadConfig();
                    sender.sendMessage("§a✦ Config rechargée.");
                }
                case "givemoney" -> {
                    if (args.length < 3) {
                        sender.sendMessage("§cUsage : /admin givemoney <joueur> <montant>");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("§cJoueur introuvable.");
                        return true;
                    }
                    double amount;
                    try { amount = Double.parseDouble(args[2]); }
                    catch (NumberFormatException e) { sender.sendMessage("§cMontant invalide."); return true; }
                    EconomyModule eco = getModule(EconomyModule.class);
                    if (eco != null) {
                        eco.deposit(target.getUniqueId(), amount);
                        sender.sendMessage("§a✦ " + eco.formatMoney(amount) + " donnés à " + target.getName());
                    }
                }
                case "setweekly" -> {
                    com.survivalcore.modules.WeeklyModule weekly = getModule(com.survivalcore.modules.WeeklyModule.class);
                    if (weekly != null) {
                        weekly.forceNewMission();
                        sender.sendMessage("§a✦ Nouvelle mission hebdomadaire générée et envoyée aux joueurs connectés.");
                    } else {
                        sender.sendMessage("§cModule Weekly non disponible.");
                    }
                }
                default -> sender.sendMessage("§cCommande inconnue. Usage : /admin <reload|givemoney|setweekly>");
            }
            return true;
        }
    }

    private void registerModule(CoreModule module) {
        modules.add(module);
    }

    @Override
    public void onDisable() {
        // Désactiver les modules en ordre inverse
        for (int i = modules.size() - 1; i >= 0; i--) {
            try {
                modules.get(i).onDisable();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Erreur lors de la désactivation du module [" + modules.get(i).getName() + "]", e);
            }
        }
        modules.clear();

        // Fermer la base de données
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("SurvivalCore désactivé.");
    }

    /**
     * Retourne l'instance singleton du plugin.
     */
    public static SurvivalCore getInstance() {
        return instance;
    }

    /**
     * Retourne le gestionnaire de base de données.
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * Retourne un module par sa classe.
     */
    @SuppressWarnings("unchecked")
    public <T extends CoreModule> T getModule(Class<T> clazz) {
        for (CoreModule module : modules) {
            if (clazz.isInstance(module)) {
                return (T) module;
            }
        }
        return null;
    }
}
