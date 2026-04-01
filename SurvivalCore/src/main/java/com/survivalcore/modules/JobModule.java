package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Jobs — 6 métiers (Mineur, Bûcheron, Fermier, Chasseur, Alchimiste, Cuisinier).
 * Tous les métiers sont actifs simultanément — pas de choix, pas de cooldown.
 * Inspiré de Palladium : boss bar XP + GUI détaillé par métier.
 */
public class JobModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // Cache : UUID → { jobId → JobProgress }
    // Tous les jobs sont trackés en parallèle pour chaque joueur
    private final Map<UUID, Map<String, JobProgress>> allJobs = new ConcurrentHashMap<>();

    // Config jobs chargée depuis jobs.yml
    private YamlConfiguration jobsConfig;

    // Actions par job : JOB_ID → (MATERIAL/ENTITY → Reward)
    private final Map<String, Map<String, JobReward>> jobActions = new HashMap<>();

    // Tracking brewing stand → joueur (pour ALCHEMIST XP)
    private final Map<org.bukkit.Location, UUID> brewingPlayers = new ConcurrentHashMap<>();

    // Cache des milestones déjà réclamés : UUID → Set de "JOB_ID:MILESTONE"
    private final Map<UUID, Set<String>> claimedMilestones = new ConcurrentHashMap<>();

    // Compteurs debounce stats — flushés toutes les 60s
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> pendingBlocksMined = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> pendingKillsMobs = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> pendingKillsPlayers = new ConcurrentHashMap<>();

    // (Boss bars supprimées — XP affiché via action bar, moins intrusif)

    // Leveling config
    private int baseXp = 100;
    private double exponent = 1.5;
    private int maxLevel = 100;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        loadJobsConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerJobs(p.getUniqueId());
            loadClaimedMilestones(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("job").setExecutor(new JobCommand());

        // Flush des compteurs de stats toutes les 60s
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushStatCounters, 1200L, 1200L);

        plugin.getLogger().info("Job module enabled — " + jobActions.size() + " jobs chargés (mode multi-métiers).");
    }

    @Override
    public void onDisable() {
        flushStatCounters();
        for (UUID uuid : allJobs.keySet()) {
            savePlayerJobsSync(uuid);
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
        if (!jobsFile.exists()) plugin.saveResource("data/jobs.yml", false);
        jobsConfig = YamlConfiguration.loadConfiguration(jobsFile);

        ConfigurationSection levelSec = jobsConfig.getConfigurationSection("leveling");
        if (levelSec != null) {
            baseXp = levelSec.getInt("base-xp", 100);
            exponent = levelSec.getDouble("exponent", 1.5);
            maxLevel = levelSec.getInt("max-level", 100);
        }

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

    /** Retourne tous les IDs de métier (exclut leveling et bonuses). */
    public List<String> getJobIds() {
        List<String> ids = new ArrayList<>();
        for (String key : jobsConfig.getKeys(false)) {
            if (!key.equals("leveling") && !key.equals("bonuses")) ids.add(key);
        }
        return ids;
    }

    public String getJobDisplayName(String jobId) {
        return jobsConfig.getString(jobId + ".display-name", jobId);
    }

    public Material getJobIcon(String jobId) {
        String iconName = jobsConfig.getString(jobId + ".icon", "STONE");
        try { return Material.valueOf(iconName); }
        catch (IllegalArgumentException e) { return Material.STONE; }
    }

    // ─── API publique ───────────────────────────────────────────

    /** Retourne la progression d'un joueur dans un métier spécifique. */
    public JobProgress getJobProgress(UUID uuid, String jobId) {
        Map<String, JobProgress> jobs = allJobs.get(uuid);
        if (jobs == null) return new JobProgress(0, 1);
        return jobs.getOrDefault(jobId, new JobProgress(0, 1));
    }

    /** Retourne le niveau du joueur dans un métier spécifique. */
    public int getJobLevel(UUID uuid, String jobId) {
        return getJobProgress(uuid, jobId).level;
    }

    /** Compatibilité MenuModule — retourne le niveau du métier le plus avancé. */
    public int getPlayerJobLevel(UUID uuid) {
        Map<String, JobProgress> jobs = allJobs.get(uuid);
        if (jobs == null || jobs.isEmpty()) return 1;
        return jobs.values().stream().mapToInt(p -> p.level).max().orElse(1);
    }

    /** Compatibilité MenuModule — retourne l'ID du métier le plus avancé. */
    public String getPlayerJobId(UUID uuid) {
        Map<String, JobProgress> jobs = allJobs.get(uuid);
        if (jobs == null || jobs.isEmpty()) return "NONE";
        return jobs.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().level))
                .map(Map.Entry::getKey)
                .orElse("NONE");
    }

    /** Retourne tous les jobs du joueur. */
    public Map<String, JobProgress> getAllJobs(UUID uuid) {
        return allJobs.getOrDefault(uuid, new ConcurrentHashMap<>());
    }

    /** XP requise pour passer au niveau donné. */
    public int xpForLevel(int level) {
        return (int) (baseXp * Math.pow(level, exponent));
    }

    // ─── Reward Logic ───────────────────────────────────────────

    /**
     * Récompense TOUS les métiers qui ont une action correspondant à actionKey.
     * Plus de choix de métier — tous les métiers actifs sont récompensés.
     */
    private void rewardAction(Player player, String actionKey) {
        rewardActionMulti(player, actionKey, 1);
    }

    private void rewardActionMulti(Player player, String actionKey, int count) {
        UUID uuid = player.getUniqueId();
        Map<String, JobProgress> jobs = allJobs.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        EconomyModule eco = plugin.getModule(EconomyModule.class);
        SkillModule skillModule = plugin.getModule(SkillModule.class);
        boolean hasCollector = skillModule != null && skillModule.hasSkill(uuid, "collector");

        boolean anyRewarded = false;
        for (Map.Entry<String, Map<String, JobReward>> jobEntry : jobActions.entrySet()) {
            String jobId = jobEntry.getKey();
            JobReward reward = jobEntry.getValue().get(actionKey);
            if (reward == null) continue;

            JobProgress prog = jobs.computeIfAbsent(jobId, k -> new JobProgress(0, 1));

            // Multiplicateurs
            double moneyMult = 1.0;
            if (prog.level >= 10) moneyMult += 0.05;
            if (hasCollector) moneyMult += 0.20;

            double money = reward.money * moneyMult * count;
            int xp = reward.xp * count;

            // Argent
            if (eco != null && money > 0) eco.depositFromEarning(uuid, money);

            // XP job
            prog.xp += xp;
            checkLevelUp(player, jobId, prog);

            // Action bar pour CE métier
            showXpActionBar(player, jobId, prog, xp);

            anyRewarded = true;
        }

        if (anyRewarded) {
            savePlayerJobsAsync(uuid);
            addGeneralXp(uuid, 1); // 1 XP général par action
        }
    }

    private void checkLevelUp(Player player, String jobId, JobProgress prog) {
        if (prog.level >= maxLevel) return;
        int required = xpForLevel(prog.level + 1);
        while (prog.xp >= required && prog.level < maxLevel) {
            prog.xp -= required;
            prog.level++;
            required = xpForLevel(prog.level + 1);

            player.sendMessage("§6§l✦ §eJob Level Up ! §f" + getJobDisplayName(jobId) + " §7→ Niveau §b" + prog.level);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            checkMilestone(player, jobId, prog);
        }
    }

    // ─── Action Bar XP ──────────────────────────────────────────

    private void showXpActionBar(Player player, String jobId, JobProgress prog, int xpGained) {
        int required = xpForLevel(prog.level + 1);
        int filled = Math.min(10, Math.max(0, (int) (10.0 * prog.xp / required)));
        int pct = Math.min(100, (int) (100.0 * prog.xp / required));

        String barFill  = "█".repeat(filled);
        String barEmpty = "░".repeat(10 - filled);

        Component msg = Component.text("✦ ", NamedTextColor.GOLD)
                .append(Component.text(getJobDisplayName(jobId), NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" Niv." + prog.level + "  ", NamedTextColor.WHITE))
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text(barFill, NamedTextColor.GREEN))
                .append(Component.text(barEmpty, NamedTextColor.DARK_GREEN))
                .append(Component.text("]  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(pct + "%  ", NamedTextColor.AQUA))
                .append(Component.text("+" + xpGained + " xp", NamedTextColor.GREEN));

        player.sendActionBar(msg);
    }

    // ─── Paliers de Récompenses ─────────────────────────────────

    private void checkMilestone(Player player, String jobId, JobProgress prog) {
        UUID uuid = player.getUniqueId();
        String cacheKey = jobId + ":" + prog.level;
        Set<String> claimed = claimedMilestones.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (claimed.contains(cacheKey)) return;

        String jobName = getJobDisplayName(jobId);

        switch (prog.level) {
            case 5 -> {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.depositFromEarning(uuid, 200);
                giveJobItems(player, jobId, 5);
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
                giveJobItems(player, jobId, 10);
                ClaimModule claim = plugin.getModule(ClaimModule.class);
                if (claim != null) claim.addBonusClaims(uuid, 1);
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
                giveJobItems(player, jobId, 20);
                ClaimModule claim = plugin.getModule(ClaimModule.class);
                if (claim != null) claim.addBonusClaims(uuid, 2);
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
                giveJobItems(player, jobId, 30);
                ClaimModule claim = plugin.getModule(ClaimModule.class);
                if (claim != null) claim.addBonusClaims(uuid, 3);
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
                giveJobItems(player, jobId, 50);
                ClaimModule claim = plugin.getModule(ClaimModule.class);
                if (claim != null) claim.addBonusClaims(uuid, 5);
                AnnouncerModule ann = plugin.getModule(AnnouncerModule.class);
                if (ann != null) ann.announceJobMilestone(player.getName(), jobName, 50, "Grand Maître");
                player.sendMessage("§8§m                                        ");
                player.sendMessage("§5§l✦✦ GRAND MAÎTRE ! ✦✦ §f" + jobName + " Niv.50");
                player.sendMessage("§7Récompense : §6+10000 ✦ §7+ §aPack Légendaire §7+ §b5 Claims");
                player.sendMessage("§8§m                                        ");
            }
            default -> { return; }
        }

        claimed.add(cacheKey);
        final int milestoneLevel = prog.level;
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

    private ItemStack enchantedItem(Material material, String displayName, int milestone) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(displayName));
        List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.text("§7Récompense de palier Niv." + milestone));
        lore.add(net.kyori.adventure.text.Component.text("§8Lié au métier"));
        meta.lore(lore);
        boolean isSword = material.name().contains("SWORD");
        boolean isBow = material.name().equals("BOW");
        if (milestone >= 5) {
            if (isSword) meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, Math.min(milestone / 5, 5), true);
            else if (isBow) meta.addEnchant(org.bukkit.enchantments.Enchantment.POWER, Math.min(milestone / 5, 5), true);
            else meta.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY, Math.min(milestone / 5, 5), true);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, Math.min(milestone / 10 + 1, 3), true);
        }
        if (milestone >= 20) {
            if (isSword) meta.addEnchant(org.bukkit.enchantments.Enchantment.LOOTING, Math.min(milestone / 15, 3), true);
            else if (!isBow) meta.addEnchant(org.bukkit.enchantments.Enchantment.FORTUNE, Math.min(milestone / 15, 3), true);
        }
        if (milestone >= 50) meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
        item.setItemMeta(meta);
        return item;
    }

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

    // ─── Listeners ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String mat = block.getType().name();

        // Stats globales (débounce)
        pendingBlocksMined.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();

        // Cultures Ageable (blé, carottes, etc.) : XP seulement si mature
        // Les autres blocs (minerais, bois, etc.) récompensent toujours
        if (block.getBlockData() instanceof org.bukkit.block.data.Ageable) {
            if (isMatureCrop(block)) {
                rewardAction(player, mat);
            }
            // Culture immature → pas d'XP (évite le farm de pousse)
        } else {
            rewardAction(player, mat);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player player = event.getEntity().getKiller();

        if (event.getEntity() instanceof Player) {
            pendingKillsPlayers.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            return;
        }

        String entityName = event.getEntityType().name();
        rewardAction(player, entityName);
        pendingKillsMobs.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getType() != InventoryType.BREWING) return;
        org.bukkit.Location loc = event.getView().getTopInventory().getLocation();
        if (loc != null) brewingPlayers.put(loc.getBlock().getLocation(), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        org.bukkit.Location loc = event.getBlock().getLocation();
        UUID uuid = brewingPlayers.get(loc);
        if (uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
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
        String mat = event.getItemType().name();
        rewardActionMulti(player, mat, event.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String mat = event.getRecipe().getResult().getType().name();
        rewardAction(player, mat);
    }

    private boolean isMatureCrop(Block block) {
        if (block.getBlockData() instanceof org.bukkit.block.data.Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }
        Material t = block.getType();
        return t == Material.MELON || t == Material.PUMPKIN || t == Material.SUGAR_CANE
                || t == Material.CACTUS || t == Material.BAMBOO || t == Material.COCOA;
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loadPlayerJobs(uuid);
        loadClaimedMilestones(uuid);
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        flushPlayerCounters(uuid);
        savePlayerJobsAsync(uuid);
        allJobs.remove(uuid);
        claimedMilestones.remove(uuid);
    }

    // ─── Flush stats ─────────────────────────────────────────────

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

    // ─── Persistance SQLite ─────────────────────────────────────

    private void loadPlayerJobs(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            Map<String, JobProgress> jobs = new ConcurrentHashMap<>();

            // Charger depuis player_jobs (multi-métiers)
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT job_id, xp, level FROM player_jobs WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    jobs.put(rs.getString("job_id"), new JobProgress(rs.getInt("xp"), rs.getInt("level")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement player_jobs: " + e.getMessage());
            }

            // Migration : si vide, lire l'ancien job unique depuis players
            if (jobs.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT job, job_xp, job_level FROM players WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        String oldJob = rs.getString("job");
                        if (oldJob != null && !oldJob.equals("NONE") && !oldJob.isBlank()) {
                            jobs.put(oldJob, new JobProgress(rs.getInt("job_xp"), rs.getInt("job_level")));
                        }
                    }
                } catch (Exception ignored) {}
            }

            return jobs;
        }).thenAccept(jobs -> allJobs.put(uuid, jobs));
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

    private void savePlayerJobsAsync(UUID uuid) {
        Map<String, JobProgress> jobs = allJobs.get(uuid);
        if (jobs == null) return;
        Map<String, JobProgress> snapshot = new HashMap<>(jobs);
        plugin.getDatabaseManager().runAsync(conn -> {
            for (Map.Entry<String, JobProgress> entry : snapshot.entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO player_jobs (uuid, job_id, xp, level) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, entry.getKey());
                    ps.setInt(3, entry.getValue().xp);
                    ps.setInt(4, entry.getValue().level);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("Erreur sauvegarde player_jobs: " + e.getMessage());
                }
            }
        });
    }

    private void savePlayerJobsSync(UUID uuid) {
        Map<String, JobProgress> jobs = allJobs.get(uuid);
        if (jobs == null) return;
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn == null || conn.isClosed()) return;
            for (Map.Entry<String, JobProgress> entry : jobs.entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO player_jobs (uuid, job_id, xp, level) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, entry.getKey());
                    ps.setInt(3, entry.getValue().xp);
                    ps.setInt(4, entry.getValue().level);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur sauvegarde sync player_jobs: " + e.getMessage());
        }
    }

    // ─── GUI — Liste des métiers (style Palladium) ───────────────

    public void openJobGui(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, JobProgress> jobs = allJobs.getOrDefault(uuid, new ConcurrentHashMap<>());
        List<String> jobIds = getJobIds();

        Gui gui = Gui.gui()
                .title(Component.text("§0✦ §e§lMes Métiers §0✦"))
                .rows(6)
                .disableAllInteractions()
                .create();

        // Fond noir
        GuiItem black = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(black);

        // ── Row 0 : Header doré ──────────────────────────────────
        GuiItem golden = ItemBuilder.from(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        for (int i = 0; i < 9; i++) {
            if (i != 4) gui.setItem(i, golden);
        }

        // Calcul des niveaux totaux pour l'en-tête
        int totalLevels = jobs.values().stream().mapToInt(p -> p.level).sum();
        int maxLevel = jobIds.size() * this.maxLevel;

        gui.setItem(4, ItemBuilder.from(Material.NETHER_STAR)
                .name(Component.text("§e§lMes Métiers"))
                .lore(
                        Component.text("§7Tous les métiers sont actifs simultanément."),
                        Component.text("§7Gagne de l'XP en jouant normalement !"),
                        Component.text(""),
                        Component.text("§8Niveaux totaux : §e" + totalLevels + "§8/§7" + maxLevel),
                        Component.text(""),
                        Component.text("§8▶ Clique sur un métier pour les détails")
                )
                .asGuiItem());

        // ── Row 1 : 3 premiers métiers (slots 10, 12, 14) ────────
        // Séparateurs colorés autour de chaque carte
        // Layout : 9 | [col] | [job] | [col] | [job] | [col] | [job] | [col] | 17
        // slots :   9    10     11     12      13      14      15      16      17

        // ── Row 3 : 3 métiers suivants (slots 28, 30, 32) ────────

        // Grille 3x2 : rangée 1 = slots 10,12,14 / rangée 2 = slots 28,30,32
        int[][] jobLayout = {{10, 12, 14}, {28, 30, 32}};
        int jobIndex = 0;
        for (int[] row : jobLayout) {
            for (int slot : row) {
                if (jobIndex >= jobIds.size()) break;
                String jobId = jobIds.get(jobIndex);
                JobProgress prog = jobs.getOrDefault(jobId, new JobProgress(0, 1));

                // Vitraux colorés adjacents (left + right de chaque carte)
                Material glass = getJobGlassColor(jobId);
                GuiItem colorGlass = ItemBuilder.from(glass).name(Component.text(" ")).asGuiItem();
                if (slot > 9) gui.setItem(slot - 1, colorGlass);
                gui.setItem(slot + 1, colorGlass);

                addJobCard(gui, player, jobId, prog, slot);
                jobIndex++;
            }
        }

        // ── Row 2 séparateur doré ─────────────────────────────────
        for (int i = 18; i <= 26; i++) gui.setItem(i, golden);

        // ── Row 4 séparateur ──────────────────────────────────────
        for (int i = 36; i <= 44; i++) gui.setItem(i, golden);

        // ── Row 5 : Retour menu ────────────────────────────────────
        gui.setItem(49, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Retour au Menu"))
                .asGuiItem(e -> {
                    MenuModule menu = plugin.getModule(MenuModule.class);
                    if (menu != null) menu.openMainMenu(player);
                }));

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
        gui.open(player);
    }

    /** Carte d'un métier dans le menu principal — style Palladium. */
    private void addJobCard(Gui gui, Player player, String jobId, JobProgress prog, int slot) {
        Material icon = getJobIcon(jobId);
        String displayName = getJobDisplayName(jobId);
        String desc = jobsConfig.getString(jobId + ".description", "");
        String color = getJobColor(jobId);

        int required = xpForLevel(prog.level + 1);
        String xpBar = makeXpBar(prog.xp, required);
        int xpLeft = required - prog.xp;
        Set<String> claimed = claimedMilestones.getOrDefault(player.getUniqueId(), Collections.emptySet());

        // Prochain palier non encore atteint
        int[] milestones = {5, 10, 20, 30, 50};
        String nextMilestone = "§a✦ Grand Maître atteint !";
        for (int m : milestones) {
            if (!claimed.contains(jobId + ":" + m)) {
                nextMilestone = "§7Prochain palier : " + color + "Niv." + m;
                break;
            }
        }

        List<Component> lore = new ArrayList<>();
        if (!desc.isEmpty()) lore.add(Component.text("§8" + desc));
        lore.add(Component.text("§8─────────────────────────"));
        lore.add(Component.text("§7Niveau §f" + prog.level + " §8/ §7" + maxLevel));
        lore.add(Component.text(xpBar));
        lore.add(Component.text("§7" + prog.xp + " §8/ §7" + required + " XP §8(encore §7" + xpLeft + " XP§8)"));
        lore.add(Component.text("§8─────────────────────────"));

        // Paliers visuels
        StringBuilder mbar = new StringBuilder("§8Paliers : ");
        for (int m : milestones) {
            boolean done = claimed.contains(jobId + ":" + m);
            mbar.append(done ? "§a✦ " : "§8○ ");
        }
        lore.add(Component.text(mbar.toString().stripTrailing()));
        lore.add(Component.text(nextMilestone));
        lore.add(Component.text("§8─────────────────────────"));
        lore.add(Component.text("§e▶ Clic pour voir les détails"));

        gui.setItem(slot, ItemBuilder.from(icon)
                .name(Component.text(color + "§l" + displayName))
                .lore(lore)
                .asGuiItem(e -> openJobDetail(player, jobId)));
    }

    // ─── GUI — Détail d'un métier (style Palladium) ─────────────

    public void openJobDetail(Player player, String jobId) {
        UUID uuid = player.getUniqueId();
        Map<String, JobProgress> jobs = allJobs.getOrDefault(uuid, new ConcurrentHashMap<>());
        JobProgress prog = jobs.getOrDefault(jobId, new JobProgress(0, 1));
        Set<String> claimed = claimedMilestones.getOrDefault(uuid, Collections.emptySet());

        String displayName = getJobDisplayName(jobId);
        String desc = jobsConfig.getString(jobId + ".description", "");
        String color = getJobColor(jobId);
        int required = xpForLevel(prog.level + 1);
        int xpLeft = required - prog.xp;
        String xpBar = makeXpBar(prog.xp, required);

        Gui gui = Gui.gui()
                .title(Component.text("§0✦ " + color + "§l" + displayName + " §0✦"))
                .rows(6)
                .disableAllInteractions()
                .create();

        // Fond noir
        GuiItem black = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(black);

        // Verre coloré du métier
        Material jobGlass = getJobGlassColor(jobId);
        GuiItem colorGlass = ItemBuilder.from(jobGlass).name(Component.text(" ")).asGuiItem();

        // ── Row 0 : Header coloré ────────────────────────────────
        for (int i = 0; i < 9; i++) {
            if (i != 4) gui.setItem(i, colorGlass);
        }

        // Slot 4 : Info principale du métier
        List<Component> headerLore = new ArrayList<>();
        if (!desc.isEmpty()) headerLore.add(Component.text("§8" + desc));
        headerLore.add(Component.text("§8─────────────────────────"));
        headerLore.add(Component.text("§7Niveau §f" + prog.level + " §8/ §7" + maxLevel));
        headerLore.add(Component.text(xpBar));
        headerLore.add(Component.text("§7" + prog.xp + " §8/ §7" + required + " XP §8(encore §7" + xpLeft + "§8)"));
        headerLore.add(Component.text("§8─────────────────────────"));
        // XP total cumulée
        int totalXpEarned = getTotalXpForLevel(prog.level) + prog.xp;
        headerLore.add(Component.text("§7XP totale gagnée : §a" + totalXpEarned));

        gui.setItem(4, ItemBuilder.from(getJobIcon(jobId))
                .name(Component.text(color + "§l" + displayName))
                .lore(headerLore)
                .asGuiItem());

        // ── Row 1 : Séparateur + titre paliers ──────────────────
        gui.setItem(9,  colorGlass);
        gui.setItem(17, colorGlass);
        gui.setItem(13, ItemBuilder.from(Material.GOLD_BLOCK)
                .name(Component.text("§6§lPaliers de Récompenses"))
                .lore(
                        Component.text("§7Atteins ces niveaux pour"),
                        Component.text("§7débloquer des récompenses !"),
                        Component.text(""),
                        Component.text("§a✦ §7= Palier obtenu"),
                        Component.text("§e⚡ §7= Niveau atteint"),
                        Component.text("§8○ §7= Non atteint")
                )
                .asGuiItem());

        // ── Row 2 : Les 5 paliers ────────────────────────────────
        int[] milestones  = {5, 10, 20, 30, 50};
        String[] mNames   = {"Apprenti", "Compagnon", "Expert", "Maître", "Grand Maître"};
        String[] mColors  = {"§7", "§a", "§b", "§d", "§5"};
        String[] mRewards = {"+200 ✦ + Pack Apprenti",
                             "+500 ✦ + Pack Compagnon + §b1 Claim",
                             "+1500 ✦ + Pack Expert + §b2 Claims",
                             "+3000 ✦ + Pack Maître + §b3 Claims",
                             "+10000 ✦ + Pack Légendaire + §b5 Claims"};
        Material[] mIcons = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND,
                             Material.NETHERITE_INGOT, Material.NETHER_STAR};
        int[] mSlots = {19, 21, 23, 25, 27};

        for (int i = 0; i < milestones.length; i++) {
            int m = milestones[i];
            boolean claimedM = claimed.contains(jobId + ":" + m);
            boolean reached  = prog.level >= m;
            String stateColor = claimedM ? "§a" : reached ? "§e" : "§8";
            String stateLabel = claimedM ? "§a✦ OBTENU" : reached ? "§e⚡ Niveau atteint !" : "§8○ Non atteint";

            List<Component> mLore = new ArrayList<>();
            mLore.add(Component.text("§8─────────────────────────"));
            mLore.add(Component.text("§7Palier : " + mColors[i] + "§l" + mNames[i] + " §8(Niv." + m + ")"));
            mLore.add(Component.text("§7Récompense : §6" + mRewards[i]));
            mLore.add(Component.text("§8─────────────────────────"));
            if (!claimedM && !reached) {
                int totalXpHave = getTotalXpForLevel(prog.level) + prog.xp;
                int totalXpNeed = getTotalXpForLevel(m);
                int remaining   = Math.max(0, totalXpNeed - totalXpHave);
                mLore.add(Component.text("§8Encore §7~" + remaining + " XP §8à gagner"));
            }
            mLore.add(Component.text(stateLabel));

            gui.setItem(mSlots[i], ItemBuilder.from(claimedM ? Material.LIME_DYE : reached ? Material.YELLOW_DYE : mIcons[i])
                    .name(Component.text(stateColor + "§l" + mNames[i] + " §8— Niv." + m))
                    .lore(mLore)
                    .asGuiItem());
        }

        // ── Séparateur central ────────────────────────────────────
        gui.setItem(18, colorGlass);
        gui.setItem(26, colorGlass);

        // ── Row 3 : Titre section actions ────────────────────────
        Map<String, JobReward> actions = jobActions.getOrDefault(jobId, Collections.emptyMap());
        List<Map.Entry<String, JobReward>> topActions = actions.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().xp, a.getValue().xp))
                .limit(7)
                .toList();

        gui.setItem(28, ItemBuilder.from(Material.EXPERIENCE_BOTTLE)
                .name(Component.text("§a§lComment gagner de l'XP"))
                .lore(
                        Component.text("§7Actions récompensées pour"),
                        Component.text(color + "§l" + displayName + "§7 (top " + topActions.size() + ")"),
                        Component.text(""),
                        Component.text("§8Les récompenses augmentent"),
                        Component.text("§8avec le niveau.")
                )
                .asGuiItem());

        // ── Row 3-4 : Actions XP (slots 29-35) ───────────────────
        int[] actionSlots = {29, 30, 31, 32, 33, 34, 35};
        for (int i = 0; i < topActions.size() && i < actionSlots.length; i++) {
            Map.Entry<String, JobReward> entry = topActions.get(i);
            String actionKey = entry.getKey();
            JobReward reward  = entry.getValue();

            Material actionIcon;
            try { actionIcon = Material.valueOf(actionKey); }
            catch (Exception ex) { actionIcon = Material.PAPER; }

            String formattedName = formatActionName(actionKey);
            gui.setItem(actionSlots[i], ItemBuilder.from(actionIcon)
                    .name(Component.text("§f§l" + formattedName))
                    .lore(
                            Component.text("§8─────────────────"),
                            Component.text("§7XP     : §a+" + reward.xp + " XP"),
                            Component.text("§7Argent : §6+" + String.format("%.2f", reward.money) + " ✦"),
                            Component.text("§8─────────────────"),
                            Component.text("§8Par action effectuée")
                    )
                    .asGuiItem());
        }

        // ── Row 5 : Barre de fond + retour ────────────────────────
        for (int i = 45; i <= 53; i++) {
            if (i != 49) gui.setItem(i, colorGlass);
        }
        gui.setItem(49, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Retour aux Métiers"))
                .asGuiItem(e -> openJobGui(player)));

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.2f);
        gui.open(player);
    }

    /** XP totale cumulée pour atteindre un niveau donné depuis le niveau 1. */
    private int getTotalXpForLevel(int targetLevel) {
        int total = 0;
        for (int lvl = 1; lvl < targetLevel; lvl++) {
            total += xpForLevel(lvl + 1);
        }
        return total;
    }

    private String formatActionName(String key) {
        String[] parts = key.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private String makeXpBar(int current, int max) {
        int barLength = 20;
        int filled = max > 0 ? Math.min((int) ((double) current / max * barLength), barLength) : 0;
        StringBuilder bar = new StringBuilder("§a");
        for (int i = 0; i < filled; i++) bar.append("█");
        bar.append("§8");
        for (int i = filled; i < barLength; i++) bar.append("█");
        return bar.toString();
    }

    /** Retourne la couleur associée à un métier (pour les panneaux de verre). */
    private Material getJobGlassColor(String jobId) {
        return switch (jobId) {
            case "MINER"     -> Material.GRAY_STAINED_GLASS_PANE;
            case "LUMBERJACK"-> Material.GREEN_STAINED_GLASS_PANE;
            case "FARMER"    -> Material.YELLOW_STAINED_GLASS_PANE;
            case "HUNTER"    -> Material.RED_STAINED_GLASS_PANE;
            case "ALCHEMIST" -> Material.PURPLE_STAINED_GLASS_PANE;
            case "COOK"      -> Material.ORANGE_STAINED_GLASS_PANE;
            default          -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        };
    }

    /** Retourne la couleur texte associée à un métier. */
    private String getJobColor(String jobId) {
        return switch (jobId) {
            case "MINER"     -> "§7";
            case "LUMBERJACK"-> "§a";
            case "FARMER"    -> "§e";
            case "HUNTER"    -> "§c";
            case "ALCHEMIST" -> "§5";
            case "COOK"      -> "§6";
            default          -> "§b";
        };
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
            if (args.length > 0 && args[0].equalsIgnoreCase("info") && args.length >= 2) {
                // /job info <jobId> → ouvre le détail
                String jobId = args[1].toUpperCase();
                if (!getJobIds().contains(jobId)) {
                    player.sendMessage("§cJob inconnu. Jobs disponibles : §f" + String.join(", ", getJobIds()));
                    return true;
                }
                openJobDetail(player, jobId);
                return true;
            }
            openJobGui(player);
            return true;
        }
    }

    // ─── Data classes ───────────────────────────────────────────

    /** Progression d'un joueur dans un métier (XP + niveau). */
    public static class JobProgress {
        public int xp;
        public int level;

        public JobProgress(int xp, int level) {
            this.xp = xp;
            this.level = Math.max(1, level);
        }
    }

    /** Récompense d'une action (XP + argent). */
    public record JobReward(int xp, double money) {}
}
