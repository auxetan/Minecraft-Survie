package com.survivalcore;

import com.survivalcore.data.DatabaseManager;
import com.survivalcore.modules.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
        registerModule(new EventsModule());
        registerModule(new MenuModule());
        registerModule(new ClassModule());
        registerModule(new JobModule());
        registerModule(new SkillModule());
        registerModule(new QuestModule());
        registerModule(new ProgressModule());
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

        // Commandes core
        getCommand("admin").setExecutor(new AdminCommand());
        getCommand("spawn").setExecutor(new SpawnCommand());
        getCommand("lobby").setExecutor(new SpawnCommand());
        getCommand("commandes").setExecutor(new CommandesCommand());

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
                case "setspawn" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("§cCommande joueur uniquement.");
                        return true;
                    }
                    Location loc = player.getLocation();
                    getConfig().set("spawn.world", loc.getWorld().getName());
                    getConfig().set("spawn.x", loc.getX());
                    getConfig().set("spawn.y", loc.getY());
                    getConfig().set("spawn.z", loc.getZ());
                    getConfig().set("spawn.yaw", (double) loc.getYaw());
                    getConfig().set("spawn.pitch", (double) loc.getPitch());
                    saveConfig();
                    sender.sendMessage("§a✦ Spawn défini à ta position actuelle.");
                }
                default -> sender.sendMessage("§cCommande inconnue. Usage : /admin <reload|givemoney|setweekly|setspawn>");
            }
            return true;
        }
    }

    // ─── Commande /spawn et /lobby ──────────────────────────────

    private class SpawnCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            // Utilise le spawn configuré (config.yml) ou le spawn du monde principal
            Location spawn;
            if (getConfig().isSet("spawn.world")) {
                String worldName = getConfig().getString("spawn.world", "world");
                double x = getConfig().getDouble("spawn.x", 0.5);
                double y = getConfig().getDouble("spawn.y", 64);
                double z = getConfig().getDouble("spawn.z", 0.5);
                float yaw = (float) getConfig().getDouble("spawn.yaw", 0);
                float pitch = (float) getConfig().getDouble("spawn.pitch", 0);
                var world = Bukkit.getWorld(worldName);
                spawn = world != null
                        ? new Location(world, x, y, z, yaw, pitch)
                        : player.getWorld().getSpawnLocation();
            } else {
                spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            player.teleport(spawn);
            player.sendMessage("§a✦ Téléporté au spawn !");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
            return true;
        }
    }

    // ─── Commande /commandes ────────────────────────────────────

    private class CommandesCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            sender.sendMessage("§8§m─────────────────────────────────────");
            sender.sendMessage("§6  ✦ §lCommandes SurvivalCraft §6✦");
            sender.sendMessage("§8§m─────────────────────────────────────");
            sender.sendMessage("§e§lNavigation");
            sender.sendMessage("  §a/spawn §8— §7Retourner au spawn");
            sender.sendMessage("  §a/menu §8— §7Menu principal");
            sender.sendMessage("  §a/waypoint add <nom> §8— §7Ajouter un waypoint");
            sender.sendMessage("  §a/deaths §8— §7Voir position de ta dernière mort");
            sender.sendMessage("§e§lÉconomie & Commerce");
            sender.sendMessage("  §a/balance §8[§7/bal§8] §8— §7Voir ton solde");
            sender.sendMessage("  §a/pay <joueur> <montant> §8— §7Payer un joueur");
            sender.sendMessage("  §a/shop §8— §7Boutique admin");
            sender.sendMessage("  §a/ah §8[§7/hdv§8] §8— §7Hôtel des enchères joueurs");
            sender.sendMessage("  §a/ah sell <prix> [durée_h] §8— §7Vendre un item aux enchères");
            sender.sendMessage("  §a/vendre <prix> §8— §7Mettre un item sur le marché joueur");
            sender.sendMessage("  §a/marche §8— §7Parcourir le marché joueur");
            sender.sendMessage("§e§lPersonnage & Progression");
            sender.sendMessage("  §a/classe §8— §7Choisir / voir ta classe");
            sender.sendMessage("  §a/job §8— §7Choisir / voir ton métier");
            sender.sendMessage("  §a/quetes §8— §7Quêtes journalières");
            sender.sendMessage("  §a/classement §8— §7Classements du serveur");
            sender.sendMessage("§e§lServeur");
            sender.sendMessage("  §a/claim §8— §7Protéger un chunk");
            sender.sendMessage("  §a/coffre-commun §8— §7Coffre commun (dépôt communautaire)");
            sender.sendMessage("  §a/mods §8— §7Page de téléchargement des mods recommandés");
            sender.sendMessage("§8§m─────────────────────────────────────");
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
