package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Mission Hebdomadaire commune.
 * Chaque lundi à minuit : nouvelle mission tirée au sort, livre signé envoyé.
 * Tracking participation individuelle par UUID en SQLite.
 */
public class WeeklyModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private YamlConfiguration weeklyConfig;
    private final List<WeeklyMissionDef> missionPool = new ArrayList<>();

    // Mission courante
    private WeeklyMissionDef currentMission;
    private String currentWeek; // "2026-W13" format

    // Participation cache : UUID → progress
    private final Map<UUID, WeeklyProgress> participations = new ConcurrentHashMap<>();
    // Throttle PlayerMoveEvent (1 check / seconde max par joueur)
    private final Map<UUID, Long> lastMoveCheck = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        loadWeeklyConfig();
        currentWeek = getWeekString();

        // Charger ou générer la mission de la semaine
        loadCurrentMission();

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadParticipation(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduleMondayReset();

        plugin.getLogger().info("Weekly module enabled — " + missionPool.size() + " missions dans le pool.");
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, WeeklyProgress> entry : participations.entrySet()) {
            saveParticipationSync(entry.getKey(), entry.getValue());
        }
        plugin.getLogger().info("Weekly module disabled.");
    }

    @Override
    public String getName() {
        return "Weekly";
    }

    // ─── Config ─────────────────────────────────────────────────

    private void loadWeeklyConfig() {
        File f = new File(plugin.getDataFolder(), "data/weekly.yml");
        if (!f.exists()) plugin.saveResource("data/weekly.yml", false);
        weeklyConfig = YamlConfiguration.loadConfiguration(f);

        ConfigurationSection sec = weeklyConfig.getConfigurationSection("missions");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection m = sec.getConfigurationSection(key);
            if (m == null) continue;
            missionPool.add(new WeeklyMissionDef(
                    key,
                    m.getString("type", "KILL"),
                    m.getString("target", ""),
                    m.getInt("amount_per_player", 10),
                    m.getString("display", key),
                    m.getString("description", ""),
                    m.getDouble("reward-money", 300),
                    m.getInt("reward-xp", 500)
            ));
        }
    }

    // ─── API publique ───────────────────────────────────────────

    public WeeklyMissionDef getCurrentMission() {
        return currentMission;
    }

    public WeeklyProgress getParticipation(UUID uuid) {
        return participations.get(uuid);
    }

    /** Forcer une nouvelle mission (commande admin). */
    public void forceNewMission() {
        participations.clear();
        generateNewMission();
        for (Player p : Bukkit.getOnlinePlayers()) {
            participations.put(p.getUniqueId(), new WeeklyProgress(0, false));
        }
        plugin.getLogger().info("Mission hebdomadaire forcée par un admin.");
    }

    public String getWeekString() {
        LocalDate now = LocalDate.now();
        int week = now.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = now.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
        return year + "-W" + String.format("%02d", week);
    }

    // ─── Mission Loading ────────────────────────────────────────

    private void loadCurrentMission() {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT mission_type, mission_data FROM weekly_mission WHERE week = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, currentWeek);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String missionId = rs.getString("mission_data");
                    for (WeeklyMissionDef def : missionPool) {
                        if (def.id.equals(missionId)) return def;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement weekly: " + e.getMessage());
            }
            return null;
        }).thenAccept(mission -> {
            if (mission != null) {
                currentMission = mission;
            } else {
                // Générer une nouvelle mission
                generateNewMission();
            }
        });
    }

    private void generateNewMission() {
        if (missionPool.isEmpty()) return;
        currentMission = missionPool.get(new Random().nextInt(missionPool.size()));

        // Sauvegarder en DB
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO weekly_mission (week, mission_type, mission_data) VALUES (?, ?, ?)")) {
                ps.setString(1, currentWeek);
                ps.setString(2, currentMission.type);
                ps.setString(3, currentMission.id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur création weekly: " + e.getMessage());
            }
        });

        // Envoyer le livre à tous les joueurs connectés
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                sendMissionBook(p);
            }
        });
    }

    // ─── Livre Signé ────────────────────────────────────────────

    private void sendMissionBook(Player player) {
        if (currentMission == null) return;

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("§6§l Mission de la Semaine");
        meta.setAuthor("SurvivalCraft");
        meta.setGeneration(BookMeta.Generation.ORIGINAL);

        // Page 1 : titre et description
        String page1 = "§6§l📖 Mission de la Semaine\n\n"
                + "§0" + currentMission.display + "\n\n"
                + "§7" + currentMission.description + "\n\n"
                + "§8Objectif : §0" + currentMission.amountPerPlayer + " par joueur";

        // Page 2 : récompenses
        String page2 = "§6§lRécompenses\n\n"
                + "§0Individuel :\n"
                + "§7• " + (int) currentMission.rewardMoney + " ✦\n"
                + "§7• " + currentMission.rewardXp + " XP\n\n"
                + "§6Si tout le monde complète :\n"
                + "§e• Bonus x2 pour tous !";

        meta.addPages(Component.text(page1), Component.text(page2));
        book.setItemMeta(meta);

        player.getInventory().addItem(book);
        player.sendMessage("§d§l★ §eTu as reçu la Mission de la Semaine !");
    }

    // ─── Progression Tracking ───────────────────────────────────

    private void progressWeekly(UUID uuid, String type, String target, int amount) {
        if (currentMission == null) return;
        if (!currentMission.type.equals(type) || !currentMission.target.equals(target)) return;

        WeeklyProgress prog = participations.get(uuid);
        if (prog == null || prog.completed) return;

        prog.progress = Math.min(prog.progress + amount, currentMission.amountPerPlayer);
        saveParticipationAsync(uuid, prog);

        if (prog.progress >= currentMission.amountPerPlayer && !prog.completed) {
            prog.completed = true;
            completeWeeklyForPlayer(uuid, prog);
        }
    }

    private void completeWeeklyForPlayer(UUID uuid, WeeklyProgress prog) {
        Player player = Bukkit.getPlayer(uuid);

        // Récompenses individuelles
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco != null) {
            eco.deposit(uuid, currentMission.rewardMoney);
        }

        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET general_xp = general_xp + ?, weekly_done = weekly_done + 1 WHERE uuid = ?")) {
                ps.setInt(1, currentMission.rewardXp);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur completion weekly: " + e.getMessage());
            }
        });

        saveParticipationAsync(uuid, prog);

        // Broadcast via AnnouncerModule
        int completedCount = (int) participations.values().stream().filter(p -> p.completed).count();
        int totalPlayers = participations.size();

        AnnouncerModule announcer = plugin.getModule(AnnouncerModule.class);
        if (announcer != null && player != null) {
            announcer.announceWeeklyProgress(player.getName(), completedCount, totalPlayers);
        }

        // Vérifier si tout le monde a complété
        if (completedCount == totalPlayers && totalPlayers > 0) {
            allCompleteBonus();
        }
    }

    private void allCompleteBonus() {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Bonus x2
            if (eco != null) {
                eco.deposit(p.getUniqueId(), currentMission.rewardMoney);
            }
        }

        // Broadcast via AnnouncerModule
        AnnouncerModule announcer = plugin.getModule(AnnouncerModule.class);
        if (announcer != null) {
            announcer.announceWeeklyAllComplete();
        }
    }

    // ─── Listeners ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player player = event.getEntity().getKiller();
        progressWeekly(player.getUniqueId(), "KILL", event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String target = event.getBlock().getType().name();
        // Mapping ores → drops
        if (target.contains("DIAMOND_ORE")) target = "DIAMOND";
        else if (target.contains("ANCIENT_DEBRIS")) target = "ANCIENT_DEBRIS";
        progressWeekly(player.getUniqueId(), "GATHER", target, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        if (currentMission == null || !currentMission.type.equals("TRAVEL")) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Throttle : 1 traitement maximum par seconde (mini PC)
        long now = System.currentTimeMillis();
        if (now - lastMoveCheck.getOrDefault(uuid, 0L) < 1000L) return;
        lastMoveCheck.put(uuid, now);

        // Check si c'est le bon monde
        boolean isNether = player.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER;
        if (currentMission.target.equals("WALK_NETHER") && !isNether) return;
        if (currentMission.target.equals("WALK") && isNether) return;

        progressWeekly(uuid, "TRAVEL", currentMission.target, 1);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loadParticipation(uuid);

        // Envoyer le livre si c'est une nouvelle connexion cette semaine
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline() && currentMission != null) {
                WeeklyProgress prog = participations.get(uuid);
                if (prog != null && prog.progress == 0 && !prog.completed) {
                    sendMissionBook(event.getPlayer());
                }
            }
        }, 60L);
    }

    // ─── Monday Reset ───────────────────────────────────────────

    private void scheduleMondayReset() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMonday = now.toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atTime(LocalTime.MIDNIGHT);
        long seconds = ChronoUnit.SECONDS.between(now, nextMonday);
        long ticks = seconds * 20L;

        new BukkitRunnable() {
            @Override
            public void run() {
                resetWeekly();
                scheduleMondayReset();
            }
        }.runTaskLater(plugin, Math.max(20L, ticks));
    }

    private void resetWeekly() {
        currentWeek = getWeekString();
        participations.clear();
        generateNewMission();

        for (Player p : Bukkit.getOnlinePlayers()) {
            participations.put(p.getUniqueId(), new WeeklyProgress(0, false));
        }

        plugin.getLogger().info("Mission hebdomadaire réinitialisée — " + currentWeek);
    }

    // ─── Persistance ────────────────────────────────────────────

    private void loadParticipation(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT progress, completed FROM weekly_participation WHERE uuid = ? AND week = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, currentWeek);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new WeeklyProgress(rs.getInt("progress"), rs.getInt("completed") == 1);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement weekly participation: " + e.getMessage());
            }
            return new WeeklyProgress(0, false);
        }).thenAccept(prog -> {
            participations.put(uuid, prog);
            // S'assurer que l'entrée existe en DB
            saveParticipationAsync(uuid, prog);
        });
    }

    private void saveParticipationAsync(UUID uuid, WeeklyProgress prog) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO weekly_participation (uuid, week, progress, completed) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, currentWeek);
                ps.setInt(3, prog.progress);
                ps.setInt(4, prog.completed ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde weekly participation: " + e.getMessage());
            }
        });
    }

    private void saveParticipationSync(UUID uuid, WeeklyProgress prog) {
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO weekly_participation (uuid, week, progress, completed) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, currentWeek);
                    ps.setInt(3, prog.progress);
                    ps.setInt(4, prog.completed ? 1 : 0);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Erreur sauvegarde sync weekly: " + e.getMessage());
        }
    }

    // ─── Data ───────────────────────────────────────────────────

    public static class WeeklyMissionDef {
        public final String id, type, target, display, description;
        public final int amountPerPlayer, rewardXp;
        public final double rewardMoney;

        public WeeklyMissionDef(String id, String type, String target, int amountPerPlayer,
                                String display, String description, double rewardMoney, int rewardXp) {
            this.id = id; this.type = type; this.target = target;
            this.amountPerPlayer = amountPerPlayer; this.display = display;
            this.description = description; this.rewardMoney = rewardMoney; this.rewardXp = rewardXp;
        }
    }

    public static class WeeklyProgress {
        public int progress;
        public boolean completed;

        public WeeklyProgress(int progress, boolean completed) {
            this.progress = progress;
            this.completed = completed;
        }
    }
}
