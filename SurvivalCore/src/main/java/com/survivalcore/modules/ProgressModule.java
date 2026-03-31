package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProgressModule — affiche des barres de progression colorées en haut de
 * l'écran (API BossBar) pour :
 *   • Les 3 quêtes journalières actives du joueur (mise à jour auto)
 *   • Les cooldowns de skills activés par d'autres modules
 *
 * Couleurs : EASY=GREEN  MEDIUM=YELLOW  HARD=RED  Complétée=PURPLE
 * Cooldown : BLUE (combat), PINK (magie), WHITE (divers)
 */
public class ProgressModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // UUID → 3 boss bars quêtes (indices 0-2)
    private final Map<UUID, List<BossBar>> questBars = new ConcurrentHashMap<>();

    // UUID → task périodique de mise à jour
    private final Map<UUID, BukkitTask> updateTasks = new ConcurrentHashMap<>();

    // Cooldown bars temporaires : on les crée et planifie la suppression
    private final Map<String, BossBar> cooldownBars = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Start bars for already online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            startQuestBars(p);
        }
        plugin.getLogger().info("ProgressModule enabled.");
    }

    @Override
    public void onDisable() {
        updateTasks.values().forEach(BukkitTask::cancel);
        updateTasks.clear();
        questBars.values().forEach(bars -> bars.forEach(BossBar::removeAll));
        questBars.clear();
        cooldownBars.values().forEach(BossBar::removeAll);
        cooldownBars.clear();
    }

    @Override
    public String getName() { return "Progress"; }

    // ─── Lifecycle ───────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Petit délai pour laisser le QuestModule charger les quêtes
        Bukkit.getScheduler().runTaskLater(plugin, () -> startQuestBars(event.getPlayer()), 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopQuestBars(event.getPlayer().getUniqueId());
    }

    // ─── Quest Bars ─────────────────────────────────────────────

    public void startQuestBars(Player player) {
        UUID uuid = player.getUniqueId();

        // Stop existing bars first
        stopQuestBars(uuid);

        // Create 3 placeholder bars
        List<BossBar> bars = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BossBar bar = Bukkit.createBossBar("§7...", BarColor.WHITE, BarStyle.SOLID);
            bar.setVisible(false);
            bar.addPlayer(player);
            bars.add(bar);
        }
        questBars.put(uuid, bars);

        // Schedule auto-refresh every 40 ticks (2 s)
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    cancel();
                    return;
                }
                refreshQuestBars(p, bars);
            }
        }.runTaskTimer(plugin, 20L, 40L);
        updateTasks.put(uuid, task);
    }

    private void stopQuestBars(UUID uuid) {
        BukkitTask task = updateTasks.remove(uuid);
        if (task != null) task.cancel();

        List<BossBar> bars = questBars.remove(uuid);
        if (bars != null) bars.forEach(BossBar::removeAll);
    }

    /** Refresh all 3 quest bars for the given player. */
    private void refreshQuestBars(Player player, List<BossBar> bars) {
        QuestModule qm = plugin.getModule(QuestModule.class);
        if (qm == null) {
            bars.forEach(b -> b.setVisible(false));
            return;
        }

        List<QuestModule.ActiveQuest> quests = qm.getPlayerQuests(player.getUniqueId());
        if (quests == null) quests = Collections.emptyList();

        for (int i = 0; i < 3; i++) {
            BossBar bar = bars.get(i);
            if (i >= quests.size()) {
                bar.setVisible(false);
                continue;
            }
            QuestModule.ActiveQuest aq = quests.get(i);
            if (aq.completed) {
                bar.setTitle("§a§l✔ §7" + aq.definition.display);
                bar.setColor(BarColor.GREEN);
                bar.setProgress(1.0);
                bar.setVisible(true);
                continue;
            }
            double pct = aq.definition.amount == 0 ? 0 :
                    Math.min(1.0, (double) aq.progress / aq.definition.amount);
            String diff = aq.definition.difficulty;
            BarColor color = switch (diff.toUpperCase()) {
                case "EASY"   -> BarColor.GREEN;
                case "MEDIUM" -> BarColor.YELLOW;
                case "HARD"   -> BarColor.RED;
                default       -> BarColor.WHITE;
            };
            String title = "§e⚑ §f" + aq.definition.display
                    + " §7(" + aq.progress + "/" + aq.definition.amount + ")";
            bar.setTitle(title);
            bar.setColor(color);
            bar.setProgress(Math.max(0.001, pct)); // 0 would throw IllegalArgumentException
            bar.setVisible(true);
        }
    }

    /** Called by QuestModule when a quest progresses — triggers immediate refresh. */
    public void notifyQuestProgress(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        List<BossBar> bars = questBars.get(uuid);
        if (bars != null) refreshQuestBars(p, bars);
    }

    // ─── Cooldown Bars ──────────────────────────────────────────

    /**
     * Show a temporary cooldown/progress bar at the top of the screen.
     *
     * @param player        Target player
     * @param label         Text shown on the bar (§-codes supported)
     * @param durationTicks Total duration of the cooldown in ticks (20 ticks = 1 s)
     * @param color         Bar color (BarColor.BLUE, PINK, WHITE, etc.)
     */
    public void showCooldownBar(Player player, String label, long durationTicks, BarColor color) {
        String key = player.getUniqueId() + ":" + label;

        // Remove existing bar with same key
        BossBar existing = cooldownBars.remove(key);
        if (existing != null) existing.removeAll();

        BossBar bar = Bukkit.createBossBar(label, color, BarStyle.SEGMENTED_10);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        cooldownBars.put(key, bar);

        long interval = Math.max(1L, durationTicks / 20L); // update ~20 times
        new BukkitRunnable() {
            long elapsed = 0;
            @Override
            public void run() {
                elapsed += interval;
                double remaining = 1.0 - (double) elapsed / durationTicks;
                if (remaining <= 0 || !player.isOnline()) {
                    bar.removeAll();
                    cooldownBars.remove(key);
                    cancel();
                    return;
                }
                bar.setProgress(Math.max(0.001, remaining));
                // Colour shifts from original to yellow near end
                if (remaining < 0.25) bar.setColor(BarColor.YELLOW);
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    /**
     * Show a short flash bar (quest complete, achievement, etc.).
     * Appears purple for 3 seconds then disappears.
     */
    public void showFlashBar(Player player, String message) {
        BossBar bar = Bukkit.createBossBar("§d§l" + message, BarColor.PURPLE, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> bar.removeAll(), 60L); // 3 s
    }
}
