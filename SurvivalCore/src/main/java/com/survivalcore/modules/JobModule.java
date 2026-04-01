package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import com.survivalcore.data.DatabaseManager;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.InputStreamReader;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Jobs — 6 jobs (Mineur, Fermier, Chasseur, Alchimiste, Bûcheron, Cuisinier).
 * Inspiré de Jobs Reborn : listeners d'actions → XP job + argent async.
 */
public class JobModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // Cache joueur → données de job
    private final Map<UUID, JobData> playerJobs = new ConcurrentHashMap<>();

    // Config jobs chargée depuis jobs.yml
    private YamlConfiguration jobsConfig;

    // Actions par job : JOB_ID → (MATERIAL/ENTITY → Reward)
    private final Map<String, Map<String, JobReward>> jobActions = new HashMap<>();

    // Tracking brewing stand → joueur (pour ALCHEMIST XP)
    private final Map<org.bukkit.Location, UUID> brewingPlayers = new ConcurrentHashMap<>();

    // Cache des milestones déjà réclamés : UUID → Set de "JOB_ID:MILESTONE"
    private final Map<UUID, Set<String>> claimedMilestones = new ConcurrentHashMap<>();

    // Compteurs debounce stats — flushés toutes les 60s au lieu de chaque event
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> pendingBlocksMined = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> pendingKillsMobs = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> pendingKillsPlayers = new ConcurrentHashMap<>();

    // Boss bars XP job — une par joueur, masquée après 5s d'inactivité
    private final Map<UUID, BossBar> xpBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> xpBarHideTasks = new ConcurrentHashMap<>();

    // Leveling config
    private int baseXp = 100;
    private double exponent = 1.5;
    private int maxLevel = 100;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;

        // Charger la config jobs.yml
        loadJobsConfig();

        // Charger les données des joueurs connectés
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerJob(p.getUniqueId());
            loadClaimedMilestones(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("job").setExecutor(new JobCommand());

        // Flush des compteurs de stats toutes les 60s (1200 ticks)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushStatCounters, 1200L, 1200L);

        plugin.getLogger().info("Job module enabled — " + jobActions.size() + " jobs chargés.");
    }

    @Override
    public void onDisable() {
        // Masquer toutes les boss bars XP
        for (Map.Entry<UUID, BossBar> entry : xpBossBars.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.hideBossBar(entry.getValue());
        }
        xpBossBars.clear();
        xpBarHideTasks.clear();
        // Vider les compteurs de stats avant la fermeture
        flushStatCounters();
        // Sauvegarder toutes les données
        for (Map.Entry<UUID, JobData> entry : playerJobs.entrySet()) {
            savePlayerJobSync(entry.getKey(), entry.getValue());
        }
        plugin.getLogger().info("Job module disabled.");
    }

    @Override
    public String getName() {
        return "Job";
    }

    // ─── Config ─────────────────────────────────────────────────

    private void loadJobsConfig() {
        File jobsFile = new File(plugin.getDataFolder(), "data/jobs.yml");
        if (!jobsFile.exists()) {
            plugin.saveResource("data/jobs.yml", false);
        }
        jobsConfig = YamlConfiguration.loadConfiguration(jobsFile);

        // Leveling
        ConfigurationSection levelSec = jobsConfig.getConfigurationSection("leveling");
        if (levelSec != null) {
            baseXp = levelSec.getInt("base-xp", 100);
            exponent = levelSec.getDouble("exponent", 1.5);
            maxLevel = levelSec.getInt("max-level", 100);
        }

        // Charger chaque job
        for (String jobId : getJobIds()) {
            ConfigurationSection actionsSec = jobsConfig.getConfigurationSection(jobId + ".actions");
            if (actionsSec == null) continue;

            Map<String, JobReward> actions = new HashMap<>();
            for (String key : actionsSec.getKeys(false)) {
                int xp = actionsSec.getInt(key + ".xp", 0);
                double money = actionsSec.getDouble(key + ".money", 0.0);
                actions.put(key, new JobReward(xp, money));
            }
            jobActions.put(jobId, actions);
        }
    }

    /** Retourne la liste des IDs de job (exclut leveling et bonuses). */
    public List<String> getJobIds() {
        List<String> ids = new ArrayList<>();
        for (String key : jobsConfig.getKeys(false)) {
            if (!key.equals("leveling") && !key.equals("bonuses")) {
                ids.add(key);
            }
        }
        return ids;
    }

    public String getJobDisplayName(String jobId) {
        return jobsConfig.getString(jobId + ".display-name", jobId);
    }

    public Material getJobIcon(String jobId) {
        String iconName = jobsConfig.getString(jobId + ".icon", "STONE");
        try {
            return Material.valueOf(iconName);
        } catch (IllegalArgumentException e) {
            return Material.STONE;
        }
    }

    // ─── API publique ───────────────────────────────────────────

    public JobData getPlayerJob(UUID uuid) {
        return playerJobs.get(uuid);
    }

    public String getPlayerJobId(UUID uuid) {
        JobData data = playerJobs.get(uuid);
        return data != null ? data.jobId : "NONE";
    }

    public int getPlayerJobLevel(UUID uuid) {
        JobData data = playerJobs.get(uuid);
        return data != null ? data.level : 1;
    }

    public int getPlayerJobXp(UUID uuid) {
        JobData data = playerJobs.get(uuid);
        return data != null ? data.xp : 0;
    }

    /** XP requise pour passer au niveau donné. */
    public int xpForLevel(int level) {
        return (int) (baseXp * Math.pow(level, exponent));
    }

    /** Tente de changer le job d'un joueur. Retourne false si cooldown pas écoulé. */
    public boolean setPlayerJob(UUID uuid, String jobId) {
        JobData data = playerJobs.computeIfAbsent(uuid, k -> new JobData("NONE", 0, 1, 0));
        long now = System.currentTimeMillis();
        long threeDays = 3L * 24 * 60 * 60 * 1000;

        // Vérifier cooldown (3 jours) — sauf si pas de job
        if (!data.jobId.equals("NONE") && (now - data.lastChange) < threeDays) {
            return false;
        }

        data.jobId = jobId;
        data.xp = 0;
        data.level = 1;
        data.lastChange = now;
        playerJobs.put(uuid, data);
        savePlayerJobAsync(uuid, data);
        return true;
    }

    // ─── Reward Logic ───────────────────────────────────────────

    /**
     * Donne les récompenses pour une action donnée (matériau ou entité).
     * Appelé depuis les listeners.
     */
    private void rewardAction(Player player, String actionKey) {
        UUID uuid = player.getUniqueId();
        JobData data = playerJobs.get(uuid);
        if (data == null || data.jobId.equals("NONE")) return;

        Map<String, JobReward> actions = jobActions.get(data.jobId);
        if (actions == null) return;

        JobReward reward = actions.get(actionKey);
        if (reward == null) return;

        // Bonus de niveau 10+ : +5% argent
        double moneyMultiplier = 1.0;
        if (data.level >= 10) moneyMultiplier += 0.05;
        // Bonus compétence Collecteur (+20% argent job)
        SkillModule skillModule = plugin.getModule(SkillModule.class);
        if (skillModule != null && skillModule.hasSkill(uuid, "collector")) {
            moneyMultiplier += 0.20;
        }

        double money = reward.money * moneyMultiplier;
        int xp = reward.xp;

        // Donner l'argent via EconomyModule
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco != null && money > 0) {
            eco.depositFromEarning(uuid, money);
        }

        // Ajouter l'XP job
        data.xp += xp;
        checkLevelUp(player, data);

        // Afficher la boss bar XP
        showXpBossBar(player, data, xp);

        // Sauvegarder async
        savePlayerJobAsync(uuid, data);

        // XP générale (pour SkillModule plus tard)
        addGeneralXp(uuid, xp);
    }

    private void checkLevelUp(Player player, JobData data) {
        if (data.level >= maxLevel) return;

        int required = xpForLevel(data.level + 1);
        while (data.xp >= required && data.level < maxLevel) {
            data.xp -= required;
            data.level++;
            required = xpForLevel(data.level + 1);

            // Notification de level up
            player.sendMessage("§6§l✦ §eJob Level Up ! §f" + getJobDisplayName(data.jobId) + " §7→ Niveau §b" + data.level);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);

            // Vérifier les paliers de récompenses
            checkMilestone(player, data);
        }
    }

    // ─── Boss Bar XP ────────────────────────────────────────────

    private void showXpBossBar(Player player, JobData data, int xpGained) {
        UUID uuid = player.getUniqueId();
        int required = xpForLevel(data.level + 1);
        float progress = Math.min(1.0f, Math.max(0f, (float) data.xp / required));
        String jobName = getJobDisplayName(data.jobId);

        Component title = Component.text("✦ ", NamedTextColor.GOLD)
                .append(Component.text(jobName, NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" Niv." + data.level, NamedTextColor.WHITE))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text(data.xp + "/" + required + " XP", NamedTextColor.AQUA))
                .append(Component.text(" (+" + xpGained + ")", NamedTextColor.GREEN));

        BossBar bar = xpBossBars.get(uuid);
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            xpBossBars.put(uuid, bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }

        // Annuler le précédent task de masquage
        Integer oldTask = xpBarHideTasks.remove(uuid);
        if (oldTask != null) {
            Bukkit.getScheduler().cancelTask(oldTask);
        }

        // Masquer après 4 secondes d'inactivité
        BossBar finalBar = bar;
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.hideBossBar(finalBar);
            xpBossBars.remove(uuid);
            xpBarHideTasks.remove(uuid);
        }, 80L).getTaskId(); // 80 ticks = 4 secondes
        xpBarHideTasks.put(uuid, taskId);
    }

    // ─── Paliers de Récompenses ────────────────────────────────

    /**
     * Système de paliers : aux niveaux clés, le joueur reçoit des récompenses spéciales.
     * Niv 5  : bonus argent + items basiques du métier
     * Niv 10 : +5% argent permanent + items intermédiaires + déblocage 1 claim
     * Niv 20 : items rares du métier + titre + déblocage 2 claims
     * Niv 30 : items légendaires + déblocage 3 claims
     * Niv 50 : Maître — stuff unique + déblocage 5 claims
     */
    private void checkMilestone(Player player, JobData data) {
        UUID uuid = player.getUniqueId();
        String jobName = getJobDisplayName(data.jobId);

        // Vérifier si ce palier a déjà été réclamé
        String cacheKey = data.jobId + ":" + data.level;
        Set<String> claimed = claimedMilestones.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (claimed.contains(cacheKey)) return;

        switch (data.level) {
            case 5 -> {
                // Palier Apprenti
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.depositFromEarning(uuid, 200);
                giveJobItems(player, data.jobId, 5);

                AnnouncerModule ann = plugin.getModule(AnnouncerModule.class);
                if (ann != null) ann.announceJobMilestone(player.getName(), jobName, 5, "Apprenti");

                player.sendMessage("§8§m                                        ");
                player.sendMessage("§6§l✦ PALIER APPRENTI DÉBLOQUÉ ! §f" + jobName + " Niv.5");
                player.sendMessage("§7Récompense : §6+200 ✦ §7+ §aPack Apprenti");
                player.sendMessage("§8§m                                        ");
            }
            case 10 -> {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.depositFromEarning(uuid, 500);
                giveJobItems(player, data.jobId, 10);

                AnnouncerModule ann = plugin.getModule(AnnouncerModule.class);
                if (ann != null) ann.announceJobMilestone(player.getName(), jobName, 10, "Compagnon");

                player.sendMessage("§8§m                                        ");
                player.sendMessage("§e§l✦ PALIER COMPAGNON DÉBLOQUÉ ! §f" + jobName + " Niv.10");
                player.sendMessage("§7Récompense : §6+500 ✦ §7+ §aPack Compagnon §7+ §b1 Claim");
                player.sendMessage("§7§o+5% argent permanent sur toutes les actions");
                player.sendMessage("§8§m                                        ");
            }
            case 20 -> {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.depositFromEarning(uuid, 1500);
                giveJobItems(player, data.jobId, 20);

                AnnouncerModule ann = plugin.getModule(AnnouncerModule.class);
                if (ann != null) ann.announceJobMilestone(player.getName(), jobName, 20, "Expert");

                player.sendMessage("§8§m                                        ");
                player.sendMessage("§b§l✦ PALIER EXPERT DÉBLOQUÉ ! §f" + jobName + " Niv.20");
                player.sendMessage("§7Récompense : §6+1500 ✦ §7+ §aPack Expert §7+ §b2 Claims");
                player.sendMessage("§8§m                                        ");
            }
            case 30 -> {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.depositFromEarning(uuid, 3000);
                giveJobItems(player, data.jobId, 30);

                AnnouncerModule ann = plugin.getModule(AnnouncerModule.class);
                if (ann != null) ann.announceJobMilestone(player.getName(), jobName, 30, "Maître");

                player.sendMessage("§8§m                                        ");
                player.sendMessage("§d§l✦ PALIER MAÎTRE DÉBLOQUÉ ! §f" + jobName + " Niv.30");
                player.sendMessage("§7Récompense : §6+3000 ✦ §7+ §aPack Maître §7+ §b3 Claims");
                player.sendMessage("§8§m                                        ");
            }
            case 50 -> {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.depositFromEarning(uuid, 10000);
                giveJobItems(player, data.jobId, 50);

                AnnouncerModule ann = plugin.getModule(AnnouncerModule.class);
                if (ann != null) ann.announceJobMilestone(player.getName(), jobName, 50, "Grand Maître");

                player.sendMessage("§8§m                                        ");
                player.sendMessage("§5§l✦✦ GRAND MAÎTRE ! ✦✦ §f" + jobName + " Niv.50");
                player.sendMessage("§7Récompense : §6+10000 ✦ §7+ §aPack Légendaire §7+ §b5 Claims");
                player.sendMessage("§8§m                                        ");
            }
            default -> { return; } // Pas un palier connu — ne pas enregistrer
        }

        // Marquer le palier comme réclamé en cache et en DB
        claimed.add(cacheKey);
        final int milestoneLevel = data.level;
        final String jobId = data.jobId;
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO job_milestones (uuid, job_id, milestone) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, jobId);
                ps.setInt(3, milestoneLevel);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur insertion job_milestone: " + e.getMessage());
            }
        });
    }

    /** Donne des items spécifiques au métier selon le palier. */
    private void giveJobItems(Player player, String jobId, int milestone) {
        List<ItemStack> items = new java.util.ArrayList<>();

        switch (jobId) {
            case "MINER" -> {
                if (milestone >= 5) items.add(enchantedItem(Material.IRON_PICKAXE, "§6Pioche d'Apprenti Mineur", 5));
                if (milestone >= 10) items.add(enchantedItem(Material.DIAMOND_PICKAXE, "§bPioche de Compagnon", 10));
                if (milestone >= 20) items.add(new ItemStack(Material.DIAMOND_BLOCK, 3));
                if (milestone >= 30) items.add(enchantedItem(Material.NETHERITE_PICKAXE, "§dPioche du Maître Mineur", 30));
                if (milestone >= 50) {
                    items.add(new ItemStack(Material.NETHERITE_BLOCK, 2));
                    items.add(enchantedItem(Material.NETHERITE_PICKAXE, "§5§l Pioche Légendaire", 50));
                }
            }
            case "LUMBERJACK" -> {
                if (milestone >= 5) items.add(enchantedItem(Material.IRON_AXE, "§6Hache d'Apprenti Bûcheron", 5));
                if (milestone >= 10) items.add(enchantedItem(Material.DIAMOND_AXE, "§bHache de Compagnon", 10));
                if (milestone >= 20) items.add(new ItemStack(Material.OAK_LOG, 64));
                if (milestone >= 30) items.add(enchantedItem(Material.NETHERITE_AXE, "§dHache du Maître Bûcheron", 30));
                if (milestone >= 50) items.add(enchantedItem(Material.NETHERITE_AXE, "§5§lHache Légendaire", 50));
            }
            case "FARMER" -> {
                if (milestone >= 5) items.add(enchantedItem(Material.IRON_HOE, "§6Faux d'Apprenti Fermier", 5));
                if (milestone >= 10) items.add(new ItemStack(Material.GOLDEN_CARROT, 32));
                if (milestone >= 20) items.add(enchantedItem(Material.DIAMOND_HOE, "§bFaux d'Expert", 20));
                if (milestone >= 30) items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 3));
                if (milestone >= 50) items.add(enchantedItem(Material.NETHERITE_HOE, "§5§lFaux Légendaire", 50));
            }
            case "HUNTER" -> {
                if (milestone >= 5) items.add(enchantedItem(Material.IRON_SWORD, "§6Épée d'Apprenti Chasseur", 5));
                if (milestone >= 10) items.add(enchantedItem(Material.DIAMOND_SWORD, "§bÉpée de Compagnon", 10));
                if (milestone >= 20) items.add(new ItemStack(Material.TOTEM_OF_UNDYING));
                if (milestone >= 30) items.add(enchantedItem(Material.NETHERITE_SWORD, "§dÉpée du Maître Chasseur", 30));
                if (milestone >= 50) items.add(enchantedItem(Material.NETHERITE_SWORD, "§5§lÉpée Légendaire", 50));
            }
            case "ALCHEMIST" -> {
                if (milestone >= 5) items.add(new ItemStack(Material.BREWING_STAND));
                if (milestone >= 10) items.add(new ItemStack(Material.BLAZE_ROD, 16));
                if (milestone >= 20) items.add(new ItemStack(Material.DRAGON_BREATH, 8));
                if (milestone >= 30) items.add(new ItemStack(Material.NETHER_STAR));
                if (milestone >= 50) items.add(new ItemStack(Material.BEACON));
            }
            case "COOK" -> {
                if (milestone >= 5) items.add(new ItemStack(Material.SMOKER));
                if (milestone >= 10) items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
                if (milestone >= 20) items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                if (milestone >= 30) items.add(new ItemStack(Material.CAKE, 16));
                if (milestone >= 50) items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 8));
            }
        }

        for (ItemStack item : items) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    /** Crée un item enchanté custom avec nom et enchantements basés sur le palier. */
    private ItemStack enchantedItem(Material material, String displayName, int milestone) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(displayName));

        List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.text("§7Récompense de palier Niv." + milestone));
        lore.add(net.kyori.adventure.text.Component.text("§8Lié au métier"));
        meta.lore(lore);

        // Enchantements progressifs selon le palier et le type d'item
        String matName = material.name();
        boolean isSword = matName.contains("SWORD");
        boolean isBow = matName.equals("BOW");

        if (milestone >= 5) {
            if (isSword) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, Math.min(milestone / 5, 5), true);
            } else if (isBow) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.POWER, Math.min(milestone / 5, 5), true);
            } else {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY, Math.min(milestone / 5, 5), true);
            }
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, Math.min(milestone / 10 + 1, 3), true);
        }
        if (milestone >= 20) {
            if (isSword) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.LOOTING, Math.min(milestone / 15, 3), true);
            } else if (!isBow) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.FORTUNE, Math.min(milestone / 15, 3), true);
            }
        }
        if (milestone >= 50) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
        }

        item.setItemMeta(meta);
        return item;
    }

    /** Ajoute de l'XP générale au joueur (pour l'arbre de compétences). */
    private void addGeneralXp(UUID uuid, int amount) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET general_xp = general_xp + ? WHERE uuid = ?")) {
                ps.setInt(1, amount);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur ajout general_xp: " + e.getMessage());
            }
        }).thenRun(() -> {
            SkillModule skill = plugin.getModule(SkillModule.class);
            if (skill != null) skill.refreshPlayerPoints(uuid);
        });
    }

    // ─── Listeners (inspiré Jobs Reborn) ────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String jobId = getPlayerJobId(player.getUniqueId());

        // Incrémenter blocks_mined pour TOUS les joueurs (débounce — flush toutes les 60s)
        pendingBlocksMined.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();

        // Mineur : minerais et pierres
        if (jobId.equals("MINER")) {
            String mat = event.getBlock().getType().name();
            rewardAction(player, mat);
        }

        // Bûcheron : bois
        if (jobId.equals("LUMBERJACK")) {
            String mat = event.getBlock().getType().name();
            rewardAction(player, mat);
        }

        // Fermier : récoltes (vérifier si c'est une culture mature)
        if (jobId.equals("FARMER")) {
            Block block = event.getBlock();
            Material type = block.getType();
            if (isMatureCrop(block)) {
                rewardAction(player, type.name());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player player = event.getEntity().getKiller();
        String jobId = getPlayerJobId(player.getUniqueId());

        // Incrémenter kills_players si la victime est un joueur (débounce)
        if (event.getEntity() instanceof Player) {
            pendingKillsPlayers.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            return;
        }

        // Chasseur : mobs
        if (jobId.equals("HUNTER")) {
            String entityName = event.getEntityType().name();
            rewardAction(player, entityName);
        }

        // Incrémenter kills_mobs pour tous les joueurs (débounce)
        pendingKillsMobs.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }

    /** Track quel joueur utilise quel brewing stand (pour ALCHEMIST). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getType() != InventoryType.BREWING) return;
        org.bukkit.Location loc = event.getView().getTopInventory().getLocation();
        if (loc != null) {
            brewingPlayers.put(loc.getBlock().getLocation(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        org.bukkit.Location loc = event.getBlock().getLocation();
        UUID uuid = brewingPlayers.get(loc);
        if (uuid == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;

        String jobId = getPlayerJobId(uuid);
        if (!jobId.equals("ALCHEMIST")) return;

        // Récompenser pour chaque potion dans les slots 0-2 du brewing stand
        BrewerInventory contents = event.getContents();
        for (int i = 0; i < 3; i++) {
            ItemStack potion = contents.getItem(i);
            if (potion != null && potion.getType() != Material.AIR) {
                rewardAction(player, potion.getType().name());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        Player player = event.getPlayer();
        String jobId = getPlayerJobId(player.getUniqueId());

        // Cuisinier : extraction du fourneau/fumoir
        if (jobId.equals("COOK")) {
            String mat = event.getItemType().name();
            for (int i = 0; i < event.getItemAmount(); i++) {
                rewardAction(player, mat);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String jobId = getPlayerJobId(player.getUniqueId());

        // Cuisinier : craft de nourriture
        if (jobId.equals("COOK")) {
            String mat = event.getRecipe().getResult().getType().name();
            rewardAction(player, mat);
        }
    }

    /** Vérifie si un crop est à maturité. */
    private boolean isMatureCrop(Block block) {
        if (block.getBlockData() instanceof org.bukkit.block.data.Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }
        // Melon, pumpkin, cactus, sugar_cane ne sont pas Ageable
        Material t = block.getType();
        return t == Material.MELON || t == Material.PUMPKIN || t == Material.SUGAR_CANE
                || t == Material.CACTUS || t == Material.BAMBOO || t == Material.COCOA;
    }

    // ─── Listeners join/quit ────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loadPlayerJob(uuid);
        loadClaimedMilestones(uuid);
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Nettoyer la boss bar XP
        BossBar bar = xpBossBars.remove(uuid);
        if (bar != null) event.getPlayer().hideBossBar(bar);
        Integer hideTask = xpBarHideTasks.remove(uuid);
        if (hideTask != null) Bukkit.getScheduler().cancelTask(hideTask);
        // Vider les compteurs du joueur avant qu'il parte
        flushPlayerCounters(uuid);
        JobData data = playerJobs.remove(uuid);
        if (data != null) {
            savePlayerJobAsync(uuid, data);
        }
        claimedMilestones.remove(uuid);
    }

    // ─── Flush des compteurs de stats (débounce) ─────────────────

    private void flushStatCounters() {
        flushCounter(pendingBlocksMined, "blocks_mined");
        flushCounter(pendingKillsMobs, "kills_mobs");
        flushCounter(pendingKillsPlayers, "kills_players");
    }

    private void flushCounter(Map<UUID, java.util.concurrent.atomic.AtomicInteger> counters, String column) {
        for (var entry : counters.entrySet()) {
            int count = entry.getValue().getAndSet(0);
            if (count <= 0) continue;
            final int finalCount = count;
            plugin.getDatabaseManager().runAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET " + column + " = " + column + " + ? WHERE uuid = ?")) {
                    ps.setInt(1, finalCount);
                    ps.setString(2, entry.getKey().toString());
                    ps.executeUpdate();
                } catch (Exception e) {
                    plugin.getLogger().warning("Flush " + column + ": " + e.getMessage());
                }
            });
        }
    }

    /** Flush uniquement les compteurs d'un joueur spécifique (à sa déconnexion). */
    private void flushPlayerCounters(UUID uuid) {
        flushSingleCounter(uuid, pendingBlocksMined, "blocks_mined");
        flushSingleCounter(uuid, pendingKillsMobs, "kills_mobs");
        flushSingleCounter(uuid, pendingKillsPlayers, "kills_players");
    }

    private void flushSingleCounter(UUID uuid, Map<UUID, java.util.concurrent.atomic.AtomicInteger> counters, String column) {
        var counter = counters.remove(uuid);
        if (counter == null) return;
        int count = counter.get();
        if (count <= 0) return;
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET " + column + " = " + column + " + ? WHERE uuid = ?")) {
                ps.setInt(1, count);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().warning("Flush " + column + " (quit): " + e.getMessage());
            }
        });
    }

    // ─── Persistance SQLite async ───────────────────────────────

    private void loadPlayerJob(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT job, job_xp, job_level, last_job_change FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new JobData(
                            rs.getString("job"),
                            rs.getInt("job_xp"),
                            rs.getInt("job_level"),
                            rs.getLong("last_job_change")
                    );
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement job: " + e.getMessage());
            }
            return new JobData("NONE", 0, 1, 0);
        }).thenAccept(data -> playerJobs.put(uuid, data));
    }

    private void loadClaimedMilestones(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            Set<String> keys = ConcurrentHashMap.newKeySet();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT job_id, milestone FROM job_milestones WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    keys.add(rs.getString("job_id") + ":" + rs.getInt("milestone"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement milestones: " + e.getMessage());
            }
            return keys;
        }).thenAccept(keys -> claimedMilestones.put(uuid, keys));
    }

    private void savePlayerJobAsync(UUID uuid, JobData data) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET job = ?, job_xp = ?, job_level = ?, last_job_change = ? WHERE uuid = ?")) {
                ps.setString(1, data.jobId);
                ps.setInt(2, data.xp);
                ps.setInt(3, data.level);
                ps.setLong(4, data.lastChange);
                ps.setString(5, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde job: " + e.getMessage());
            }
        });
    }

    private void savePlayerJobSync(UUID uuid, JobData data) {
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET job = ?, job_xp = ?, job_level = ?, last_job_change = ? WHERE uuid = ?")) {
                    ps.setString(1, data.jobId);
                    ps.setInt(2, data.xp);
                    ps.setInt(3, data.level);
                    ps.setLong(4, data.lastChange);
                    ps.setString(5, uuid.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Erreur sauvegarde sync job: " + e.getMessage());
        }
    }

    // ─── Job Selector GUI ───────────────────────────────────────

    public void openJobGui(Player player) {
        UUID uuid = player.getUniqueId();
        JobData playerData = playerJobs.getOrDefault(uuid, new JobData("NONE", 0, 1, 0));

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §eChoisir ton Job §8✦"))
                .rows(6)
                .disableAllInteractions()
                .create();

        // Black glass border (all slots)
        GuiItem border = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(border);

        // Row 1 Slot 4: Current job info
        String currentJobDisplay;
        String levelInfo;
        String xpInfo;
        if (playerData.jobId.equals("NONE")) {
            currentJobDisplay = "§7Aucun job";
            levelInfo = "§8Pas encore de job";
            xpInfo = makeXpBar(0, 100);
        } else {
            currentJobDisplay = "§6" + getJobDisplayName(playerData.jobId);
            levelInfo = "§eNiveau §b" + playerData.level + " §8/ " + maxLevel;
            int required = xpForLevel(playerData.level + 1);
            xpInfo = makeXpBar(playerData.xp, required) + " §7" + playerData.xp + "/" + required;
        }

        gui.setItem(4, ItemBuilder.from(Material.NETHER_STAR)
                .name(Component.text("§f§lTon Job"))
                .lore(
                        Component.text(currentJobDisplay),
                        Component.text(levelInfo),
                        Component.text(""),
                        Component.text(xpInfo)
                )
                .asGuiItem());

        // Row 2-3: Job cards at slots 10, 12, 14, 16 (row 2) and 28, 30 (row 3)
        int[] jobSlots = {10, 12, 14, 16, 28, 30};
        List<String> jobIds = getJobIds();

        for (int i = 0; i < jobIds.size() && i < jobSlots.length; i++) {
            String jobId = jobIds.get(i);
            addJobCard(gui, player, uuid, playerData, jobId, jobSlots[i]);
        }

        // Row 5: Milestone info at slot 40
        List<Component> milestoneLore = new ArrayList<>();
        milestoneLore.add(Component.text("§eParaliers débloqués :"));
        milestoneLore.add(Component.text(""));

        int[] milestones = {5, 10, 20, 30, 50};
        for (int m : milestones) {
            boolean reached = playerData.level >= m;
            String marker = reached ? "§a✓" : "§8✗";
            milestoneLore.add(Component.text(marker + " §eNiv. " + m + " §7(§b" + getMilestoneReward(m) + "§7)"));
        }

        gui.setItem(40, ItemBuilder.from(Material.BOOK)
                .name(Component.text("§d§lPaliers"))
                .lore(milestoneLore)
                .asGuiItem());

        // Play sound
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
        gui.open(player);
    }

    private void addJobCard(Gui gui, Player player, UUID uuid, JobData playerData, String jobId, int slot) {
        Material icon = getJobIcon(jobId);
        String displayName = getJobDisplayName(jobId);

        List<Component> lore = new ArrayList<>();

        // Job description
        String desc = jobsConfig.getString(jobId + ".description", "");
        if (!desc.isEmpty()) {
            lore.add(Component.text("§7" + desc));
            lore.add(Component.text(""));
        }

        // If this is the current job, show level and XP
        if (playerData.jobId.equals(jobId)) {
            lore.add(Component.text("§a✓ Ton job actuel"));
            lore.add(Component.text("§eNiveau : §b" + playerData.level));
            int required = xpForLevel(playerData.level + 1);
            lore.add(Component.text(makeXpBar(playerData.xp, required)));
            lore.add(Component.text("§7" + playerData.xp + "/" + required + " XP"));
        } else {
            lore.add(Component.text("§a▶ Clic pour choisir"));
        }

        Material cardMaterial = playerData.jobId.equals(jobId) ? Material.LIME_STAINED_GLASS_PANE : icon;

        gui.setItem(slot, ItemBuilder.from(cardMaterial)
                .name(Component.text((playerData.jobId.equals(jobId) ? "§a" : "§f") + "§l" + displayName))
                .lore(lore)
                .asGuiItem(event -> {
                    if (playerData.jobId.equals(jobId)) {
                        player.sendMessage("§7Tu as déjà ce job !");
                        return;
                    }

                    long now = System.currentTimeMillis();
                    long threeDays = 3L * 24 * 60 * 60 * 1000;
                    if (!playerData.jobId.equals("NONE") && (now - playerData.lastChange) < threeDays) {
                        long remaining = (threeDays - (now - playerData.lastChange)) / 1000 / 60 / 60 / 24;
                        player.sendMessage("§cTu dois attendre §e" + remaining + " jours §cavant de changer de job.");
                        return;
                    }

                    // Select the job
                    if (setPlayerJob(uuid, jobId)) {
                        player.sendMessage("§a✦ Tu es maintenant §f" + displayName + " §a!");
                        player.closeInventory();
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                    } else {
                        player.sendMessage("§cErreur lors de la sélection du job.");
                    }
                }));
    }

    private String makeXpBar(int current, int max) {
        int barLength = 20;
        int filled = (int) ((double) current / max * barLength);
        filled = Math.min(filled, barLength);
        int empty = barLength - filled;

        StringBuilder bar = new StringBuilder();
        bar.append("§a");
        for (int i = 0; i < filled; i++) {
            bar.append("|");
        }
        bar.append("§7");
        for (int i = 0; i < empty; i++) {
            bar.append("|");
        }
        return bar.toString();
    }

    private String getMilestoneReward(int level) {
        return switch (level) {
            case 5 -> "+200 ✦";
            case 10 -> "+500 ✦";
            case 20 -> "+1500 ✦";
            case 30 -> "+3000 ✦";
            case 50 -> "+10000 ✦";
            default -> "?";
        };
    }

    // ─── Commande /job ──────────────────────────────────────────

    private class JobCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }

            UUID uuid = player.getUniqueId();

            if (args.length == 0) {
                // Open job GUI
                openJobGui(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("choose") && args.length >= 2) {
                String jobId = args[1].toUpperCase();
                if (!getJobIds().contains(jobId)) {
                    player.sendMessage("§cJob invalide. Disponibles : §f" + String.join(", ", getJobIds()));
                    return true;
                }
                if (!setPlayerJob(uuid, jobId)) {
                    long remaining = getRemainingCooldown(uuid);
                    player.sendMessage("§cTu dois attendre encore §e" + formatTime(remaining) + " §cavant de changer de job.");
                    return true;
                }
                player.sendMessage("§a✦ Tu es maintenant §f" + getJobDisplayName(jobId) + " §a!");
                return true;
            }

            if (args[0].equalsIgnoreCase("list")) {
                player.sendMessage("§6§l✦ §eJobs disponibles :");
                for (String id : getJobIds()) {
                    player.sendMessage("  §7- §f" + getJobDisplayName(id) + " §8(§7" + id + "§8)");
                }
                return true;
            }

            player.sendMessage("§cUsage : /job [choose <job>|list]");
            return true;
        }
    }

    private long getRemainingCooldown(UUID uuid) {
        JobData data = playerJobs.get(uuid);
        if (data == null) return 0;
        long threeDays = 3L * 24 * 60 * 60 * 1000;
        long elapsed = System.currentTimeMillis() - data.lastChange;
        return Math.max(0, threeDays - elapsed);
    }

    private String formatTime(long millis) {
        long hours = millis / (1000 * 60 * 60);
        long minutes = (millis / (1000 * 60)) % 60;
        return hours + "h " + minutes + "min";
    }

    // ─── Data classes ───────────────────────────────────────────

    public static class JobData {
        public String jobId;
        public int xp;
        public int level;
        public long lastChange;

        public JobData(String jobId, int xp, int level, long lastChange) {
            this.jobId = jobId;
            this.xp = xp;
            this.level = level;
            this.lastChange = lastChange;
        }
    }

    public record JobReward(int xp, double money) {}
}
