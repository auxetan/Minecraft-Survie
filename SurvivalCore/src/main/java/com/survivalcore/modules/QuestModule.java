package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
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
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Quêtes journalières — 3 quêtes aléatoires par joueur par jour.
 * Types : KILL, GATHER, CRAFT, TRAVEL, BREW.
 * Inspiré BetonQuest : tracking de progression via listeners.
 */
public class QuestModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private YamlConfiguration questsConfig;

    // Pool de définitions de quêtes
    private final List<QuestDefinition> questPool = new ArrayList<>();

    // Cache : UUID → liste de 3 quêtes actives du jour
    private final Map<UUID, List<ActiveQuest>> playerQuests = new ConcurrentHashMap<>();

    // Cache de distance pour TRAVEL
    private final Map<UUID, org.bukkit.Location> lastLocations = new ConcurrentHashMap<>();
    // Throttle PlayerMoveEvent (1 check / seconde max par joueur)
    private final Map<UUID, Long> lastMoveCheck = new ConcurrentHashMap<>();
    // Tracking brewing stand → joueur (pour quêtes BREW)
    private final Map<org.bukkit.Location, UUID> brewingPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        loadQuestsConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadOrGenerateQuests(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("quetes").setExecutor(new QuestCommand());

        // Planifier le reset à minuit
        scheduleMidnightReset();

        plugin.getLogger().info("Quest module enabled — " + questPool.size() + " quêtes dans le pool.");
    }

    @Override
    public void onDisable() {
        // Sauvegarder toutes les progressions
        for (Map.Entry<UUID, List<ActiveQuest>> entry : playerQuests.entrySet()) {
            for (ActiveQuest q : entry.getValue()) {
                saveQuestProgressSync(entry.getKey(), q);
            }
        }
        plugin.getLogger().info("Quest module disabled.");
    }

    @Override
    public String getName() {
        return "Quest";
    }

    // ─── Config ─────────────────────────────────────────────────

    private void loadQuestsConfig() {
        File f = new File(plugin.getDataFolder(), "data/quests.yml");
        if (!f.exists()) plugin.saveResource("data/quests.yml", false);
        questsConfig = YamlConfiguration.loadConfiguration(f);

        ConfigurationSection sec = questsConfig.getConfigurationSection("quests");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection q = sec.getConfigurationSection(key);
            if (q == null) continue;
            questPool.add(new QuestDefinition(
                    key,
                    q.getString("type", "KILL"),
                    q.getString("target", ""),
                    q.getInt("amount", 1),
                    q.getString("display", key),
                    q.getString("difficulty", "EASY"),
                    q.getDouble("reward-money", 50),
                    q.getInt("reward-xp", 100)
            ));
        }
    }

    // ─── API publique ───────────────────────────────────────────

    public List<ActiveQuest> getPlayerQuests(UUID uuid) {
        return playerQuests.getOrDefault(uuid, Collections.emptyList());
    }

    public String today() {
        return LocalDate.now().toString();
    }

    // ─── Génération de quêtes ───────────────────────────────────

    private void loadOrGenerateQuests(UUID uuid) {
        String date = today();

        plugin.getDatabaseManager().executeAsync(conn -> {
            List<ActiveQuest> quests = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT quest_id, progress, completed FROM quests WHERE uuid = ? AND date = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, date);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String qId = rs.getString("quest_id");
                    QuestDefinition def = getQuestDef(qId);
                    if (def != null) {
                        quests.add(new ActiveQuest(def, rs.getInt("progress"), rs.getInt("completed") == 1));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement quêtes: " + e.getMessage());
            }
            return quests;
        }).thenAccept(quests -> {
            if (quests.isEmpty()) {
                // Générer 3 nouvelles quêtes
                List<ActiveQuest> generated = generateDailyQuests();
                playerQuests.put(uuid, generated);
                // Sauvegarder en DB
                for (ActiveQuest q : generated) {
                    saveQuestProgressAsync(uuid, q);
                }
            } else {
                playerQuests.put(uuid, quests);
            }
        });
    }

    private List<ActiveQuest> generateDailyQuests() {
        List<QuestDefinition> pool = new ArrayList<>(questPool);
        Collections.shuffle(pool);
        List<ActiveQuest> result = new ArrayList<>();
        for (int i = 0; i < Math.min(3, pool.size()); i++) {
            result.add(new ActiveQuest(pool.get(i), 0, false));
        }
        return result;
    }

    private QuestDefinition getQuestDef(String id) {
        for (QuestDefinition def : questPool) {
            if (def.id.equals(id)) return def;
        }
        return null;
    }

    // ─── Progression Tracking ───────────────────────────────────

    private void progressQuest(UUID uuid, String type, String target, int amount) {
        List<ActiveQuest> quests = playerQuests.get(uuid);
        if (quests == null) return;

        Player player = Bukkit.getPlayer(uuid);
        for (ActiveQuest q : quests) {
            if (q.completed) continue;
            if (!q.definition.type.equals(type)) continue;
            if (!q.definition.target.equals(target)) continue;

            q.progress = Math.min(q.progress + amount, q.definition.amount);
            saveQuestProgressAsync(uuid, q);

            if (q.progress >= q.definition.amount) {
                q.completed = true;
                completeQuest(player, uuid, q);
            }
        }
    }

    private void completeQuest(Player player, UUID uuid, ActiveQuest quest) {
        // Récompenses
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco != null) {
            eco.depositEarned(uuid, quest.definition.rewardMoney);
        }

        // XP générale (bonus Explorateur +15%)
        int finalXp = quest.definition.rewardXp;
        SkillModule skillModule = plugin.getModule(SkillModule.class);
        if (skillModule != null && skillModule.hasSkill(uuid, "explorer")) {
            finalXp = (int) (finalXp * 1.15);
        }
        final int xpToGrant = finalXp;
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET general_xp = general_xp + ?, quests_done = quests_done + 1 WHERE uuid = ?")) {
                ps.setInt(1, xpToGrant);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur completion quête: " + e.getMessage());
            }
        }).thenRun(() -> {
            if (skillModule != null) skillModule.refreshPlayerPoints(uuid);
        });

        if (player != null && player.isOnline()) {
            player.sendMessage("§a§l✦ QUÊTE ACCOMPLIE ! §7" + quest.definition.display);
            player.sendMessage("§7Récompense : §6+" + (int) quest.definition.rewardMoney + " ✦ §7| §b+" + quest.definition.rewardXp + " XP");
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

            // Broadcast via AnnouncerModule
            AnnouncerModule announcer = plugin.getModule(AnnouncerModule.class);
            if (announcer != null) {
                announcer.announceQuestComplete(player.getName(), quest.definition.display,
                        (int) quest.definition.rewardMoney, quest.definition.rewardXp);
            }
        }

        saveQuestProgressAsync(uuid, quest);
    }

    // ─── Listeners ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player player = event.getEntity().getKiller();
        progressQuest(player.getUniqueId(), "KILL", event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material mat = event.getBlock().getType();

        // GATHER — on track le matériau droppé
        // Pour les minerais, on track le drop plutôt que le bloc
        String target = mat.name();
        // Mapping ORE → drop
        if (target.contains("DIAMOND_ORE")) target = "DIAMOND";
        else if (target.contains("IRON_ORE")) target = "RAW_IRON";
        else if (target.contains("GOLD_ORE")) target = "RAW_GOLD";
        else if (target.contains("COAL_ORE")) target = "COAL";
        else if (target.contains("LAPIS_ORE")) target = "LAPIS_LAZULI";
        else if (target.contains("REDSTONE_ORE")) target = "REDSTONE";
        else if (target.contains("EMERALD_ORE")) target = "EMERALD";

        progressQuest(player.getUniqueId(), "GATHER", target, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String mat = event.getRecipe().getResult().getType().name();
        int amount = event.getRecipe().getResult().getAmount();
        progressQuest(player.getUniqueId(), "CRAFT", mat, amount);
    }

    /** Track quel joueur utilise quel brewing stand (pour quêtes BREW). */
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

        BrewerInventory contents = event.getContents();
        for (int i = 0; i < 3; i++) {
            ItemStack potion = contents.getItem(i);
            if (potion != null && potion.getType() != Material.AIR) {
                progressQuest(uuid, "BREW", potion.getType().name(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Throttle : 1 traitement maximum par seconde pour économiser le CPU (mini PC)
        long now = System.currentTimeMillis();
        if (now - lastMoveCheck.getOrDefault(uuid, 0L) < 1000L) return;
        lastMoveCheck.put(uuid, now);

        org.bukkit.Location last = lastLocations.get(uuid);
        if (last == null || !last.getWorld().equals(player.getWorld())) {
            lastLocations.put(uuid, player.getLocation().clone());
            return;
        }

        double distance = last.distance(player.getLocation());
        if (distance >= 1.0) {
            lastLocations.put(uuid, player.getLocation().clone());
            progressQuest(uuid, "TRAVEL", "WALK", (int) distance);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loadOrGenerateQuests(player.getUniqueId());

        // Notification
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage("§a✦ Tes quêtes du jour sont prêtes ! Tape §e/quetes §apour les voir.");
            }
        }, 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastLocations.remove(uuid);
        lastMoveCheck.remove(uuid);
        brewingPlayers.values().removeIf(u -> u.equals(uuid));
    }

    // ─── Midnight Reset ─────────────────────────────────────────

    private void scheduleMidnightReset() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = LocalDate.now().plusDays(1).atTime(LocalTime.MIDNIGHT);
        long secondsUntilMidnight = ChronoUnit.SECONDS.between(now, nextMidnight);
        long ticksUntilMidnight = secondsUntilMidnight * 20L;

        new BukkitRunnable() {
            @Override
            public void run() {
                resetDailyQuests();
                // Replanifier pour le lendemain
                scheduleMidnightReset();
            }
        }.runTaskLater(plugin, Math.max(20L, ticksUntilMidnight));
    }

    private void resetDailyQuests() {
        playerQuests.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadOrGenerateQuests(p.getUniqueId());
            p.sendMessage("§a✦ Nouvelles quêtes du jour disponibles ! Tape §e/quetes");
        }
        plugin.getLogger().info("Quêtes journalières réinitialisées.");
    }

    // ─── Persistance ────────────────────────────────────────────

    private void saveQuestProgressAsync(UUID uuid, ActiveQuest quest) {
        String date = today();
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO quests (uuid, quest_id, progress, completed, date) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, quest.definition.id);
                ps.setInt(3, quest.progress);
                ps.setInt(4, quest.completed ? 1 : 0);
                ps.setString(5, date);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde quête: " + e.getMessage());
            }
        });
    }

    private void saveQuestProgressSync(UUID uuid, ActiveQuest quest) {
        String date = today();
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO quests (uuid, quest_id, progress, completed, date) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, quest.definition.id);
                    ps.setInt(3, quest.progress);
                    ps.setInt(4, quest.completed ? 1 : 0);
                    ps.setString(5, date);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Erreur sauvegarde sync quête: " + e.getMessage());
        }
    }

    // ─── Commande /quetes ───────────────────────────────────────

    private class QuestCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            List<ActiveQuest> quests = playerQuests.get(player.getUniqueId());
            if (quests == null || quests.isEmpty()) {
                player.sendMessage("§7Aucune quête active aujourd'hui.");
                return true;
            }
            player.sendMessage("§6§l✦ §eQuêtes du Jour §6§l✦");
            for (int i = 0; i < quests.size(); i++) {
                ActiveQuest q = quests.get(i);
                String color = q.completed ? "§a" : difficultyColor(q.definition.difficulty);
                String status = q.completed ? "§a✓ Terminée" : "§7" + q.progress + "§8/§7" + q.definition.amount;
                String bar = makeProgressBar(q.progress, q.definition.amount, q.completed);
                player.sendMessage(color + (i + 1) + ". " + q.definition.display + " " + bar + " " + status);
            }
            return true;
        }
    }

    private String difficultyColor(String difficulty) {
        return switch (difficulty) {
            case "EASY" -> "§a";
            case "MEDIUM" -> "§e";
            case "HARD" -> "§c";
            default -> "§f";
        };
    }

    private String makeProgressBar(int current, int max, boolean completed) {
        if (completed) return "§a██████████";
        int filled = max > 0 ? (int) ((double) current / max * 10) : 0;
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < 10; i++) {
            if (i < filled) sb.append("█");
            else { sb.append("§7█"); }
        }
        return sb.toString();
    }

    // ─── Data classes ───────────────────────────────────────────

    public static class QuestDefinition {
        public final String id, type, target, display, difficulty;
        public final int amount, rewardXp;
        public final double rewardMoney;

        public QuestDefinition(String id, String type, String target, int amount,
                               String display, String difficulty, double rewardMoney, int rewardXp) {
            this.id = id;
            this.type = type;
            this.target = target;
            this.amount = amount;
            this.display = display;
            this.difficulty = difficulty;
            this.rewardMoney = rewardMoney;
            this.rewardXp = rewardXp;
        }
    }

    public static class ActiveQuest {
        public final QuestDefinition definition;
        public int progress;
        public boolean completed;

        public ActiveQuest(QuestDefinition definition, int progress, boolean completed) {
            this.definition = definition;
            this.progress = progress;
            this.completed = completed;
        }
    }
}
