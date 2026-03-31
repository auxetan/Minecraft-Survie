package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import com.survivalcore.ui.GuiBackground;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Module Leaderboards — classements top 6 par stat + scoreboard latéral.
 * Scoreboard mis à jour toutes les 600 ticks (30s).
 */
public class LeaderboardModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // Stats disponibles pour le classement
    private static final List<StatDef> STATS = List.of(
            new StatDef("kills_mobs", "Mobs tués", Material.IRON_SWORD),
            new StatDef("kills_players", "Joueurs tués", Material.DIAMOND_SWORD),
            new StatDef("deaths", "Morts", Material.SKELETON_SKULL),
            new StatDef("blocks_mined", "Blocs minés", Material.DIAMOND_PICKAXE),
            new StatDef("money_earned", "Argent gagné", Material.GOLD_INGOT),
            new StatDef("quests_done", "Quêtes complétées", Material.WRITABLE_BOOK),
            new StatDef("weekly_done", "Missions hebdo", Material.NETHER_STAR)
    );

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;

        plugin.getCommand("classement").setExecutor(new LeaderboardCommand());
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Scoreboard latéral — mise à jour périodique
        boolean scoreboardEnabled = plugin.getConfig().getBoolean("scoreboard", true);
        int updateTicks = plugin.getConfig().getInt("scoreboard-update-ticks", 600);

        if (scoreboardEnabled) {
            // runTaskTimer (sync) : Bukkit.getOnlinePlayers() n'est pas thread-safe en async
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        updateScoreboard(p);
                    }
                }
            }.runTaskTimer(plugin, 100L, updateTicks);
        }

        plugin.getLogger().info("Leaderboard module enabled.");
    }

    @Override
    public void onDisable() {
        // Retirer les scoreboards
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        }
        plugin.getLogger().info("Leaderboard module disabled.");
    }

    @Override
    public String getName() {
        return "Leaderboard";
    }

    // ─── Scoreboard Latéral ─────────────────────────────────────

    private void updateScoreboard(Player player) {
        UUID uuid = player.getUniqueId();

        // Récupérer les données depuis la DB
        plugin.getDatabaseManager().executeAsync(conn -> {
            Map<String, String> data = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT money, job, job_level, quests_done, weekly_done FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    data.put("money", String.format("%.0f", rs.getDouble("money")));
                    data.put("job", rs.getString("job"));
                    data.put("job_level", String.valueOf(rs.getInt("job_level")));
                    data.put("quests_done", String.valueOf(rs.getInt("quests_done")));
                    data.put("weekly_done", String.valueOf(rs.getInt("weekly_done")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur scoreboard: " + e.getMessage());
            }
            return data;
        }).thenAccept(data -> {
            // Appliquer sur le thread principal
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                setPlayerScoreboard(player, data);
            });
        });
    }

    private void setPlayerScoreboard(Player player, Map<String, String> data) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective("survivalcore");

        if (obj == null) {
            // First time — create fresh
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            obj = board.registerNewObjective("survivalcore", Criteria.DUMMY,
                    Component.text("§6§l✦ SurvivalCraft"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            // Copier dans une liste avant d'itérer pour éviter ConcurrentModificationException
            for (String entry : new ArrayList<>(board.getEntries())) {
                board.resetScores(entry);
            }
        }

        String money = data.getOrDefault("money", "0");
        String job = data.getOrDefault("job", "NONE");
        String jobLevel = data.getOrDefault("job_level", "1");

        ClassModule classModule = plugin.getModule(ClassModule.class);
        String classDisplay = "Aucune";
        if (classModule != null) {
            String classId = classModule.getPlayerClassId(player.getUniqueId());
            if (classId != null && !classId.equals("NONE")) {
                classDisplay = classModule.getClassDisplayName(classId);
            }
        }

        QuestModule questModule = plugin.getModule(QuestModule.class);
        int questsDone = 0;
        int questsTotal = 0;
        if (questModule != null) {
            var quests = questModule.getPlayerQuests(player.getUniqueId());
            questsTotal = quests.size();
            questsDone = (int) quests.stream().filter(q -> q.completed).count();
        }

        WeeklyModule weeklyModule = plugin.getModule(WeeklyModule.class);
        String weeklyStatus = "§70/1";
        if (weeklyModule != null) {
            var participation = weeklyModule.getParticipation(player.getUniqueId());
            if (participation != null && participation.completed) {
                weeklyStatus = "§a1/1 ✓";
            }
        }

        setScore(obj, "§7", 8);
        setScore(obj, "§7Argent: §e" + money + " ✦", 7);
        setScore(obj, "§7Classe: §d" + classDisplay, 6);
        setScore(obj, "§7Job: §b" + formatJob(job) + " Niv." + jobLevel, 5);
        setScore(obj, "§7Quêtes: §a" + questsDone + "/" + questsTotal, 4);
        setScore(obj, "§7Semaine: " + weeklyStatus, 3);
        setScore(obj, "§8", 2);
        setScore(obj, "§8§osurvivalcraft.fr", 1);

        player.setScoreboard(board);
    }

    private void setScore(Objective obj, String text, int score) {
        Score s = obj.getScore(text);
        s.setScore(score);
    }

    private String formatJob(String job) {
        if (job == null || job.equals("NONE")) return "Aucun";
        JobModule jobModule = plugin.getModule(JobModule.class);
        if (jobModule != null) return jobModule.getJobDisplayName(job);
        return job;
    }

    // ─── GUI Leaderboard ────────────────────────────────────────

    public void openLeaderboard(Player player) {
        openLeaderboardPage(player, 0);
    }

    private void openLeaderboardPage(Player player, int statIndex) {
        if (statIndex < 0 || statIndex >= STATS.size()) statIndex = 0;
        StatDef stat = STATS.get(statIndex);
        int finalStatIndex = statIndex;

        // Charger le top 6 depuis la DB
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<LeaderEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, " + stat.column + " as val FROM players ORDER BY " + stat.column + " DESC LIMIT 6")) {
                ResultSet rs = ps.executeQuery();
                int rank = 1;
                while (rs.next()) {
                    entries.add(new LeaderEntry(rank++, rs.getString("name"), rs.getDouble("val")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur leaderboard: " + e.getMessage());
            }
            return entries;
        }).thenAccept(entries -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                openLeaderboardGui(player, stat, entries, finalStatIndex);
            });
        });
    }

    private void openLeaderboardGui(Player player, StatDef stat, List<LeaderEntry> entries, int statIndex) {
        Gui gui = Gui.gui()
                .title(GuiBackground.LEADERBOARD.title("§8✦ §6Classement : " + stat.displayName + " §8✦"))
                .rows(6)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Row 1: Header with stat tabs (slots 1-7, clickable icons for each stat)
        for (int i = 0; i < STATS.size() && i < 7; i++) {
            StatDef tabStat = STATS.get(i);
            int tabSlot = i;

            ItemStack tabItem = new ItemStack(tabStat.icon);
            ItemMeta tabMeta = tabItem.getItemMeta();
            tabMeta.displayName(Component.text("§f" + tabStat.displayName));

            // Highlight current stat with enchant glow
            if (i == statIndex) {
                tabMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
                tabMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            tabItem.setItemMeta(tabMeta);

            final int finalI = i;
            gui.setItem(tabSlot, new GuiItem(tabItem, e -> openLeaderboardPage(player, finalI)));
        }

        // Row 2-5: Leaderboard entries
        // #1 at slot 12 (center, big) - use PLAYER_HEAD with SkullMeta
        // #2 at slot 20 (left of center)
        // #3 at slot 22 (right of center)
        // #4 at slot 28
        // #5 at slot 30
        // #6 at slot 32
        int[] leaderboardSlots = {12, 20, 22, 28, 30, 32};
        String[] rankColors = {"§6", "§f", "§c", "§7", "§7", "§7"};
        String[] rankMedals = {"§6§l★ CHAMPION ★", "", "", "", "", ""};

        for (int i = 0; i < Math.min(6, entries.size()); i++) {
            LeaderEntry entry = entries.get(i);
            int slot = leaderboardSlots[i];
            String rankColor = rankColors[i];

            ItemStack entryItem;
            ItemMeta entryMeta;

            if (i == 0) {
                // #1: Use PLAYER_HEAD
                entryItem = new ItemStack(Material.PLAYER_HEAD);
                entryMeta = entryItem.getItemMeta();
                if (entryMeta instanceof SkullMeta skullMeta) {
                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.name));
                    entryMeta = skullMeta;
                }
            } else {
                // #2-6: Use regular material blocks
                Material[] rankMaterials = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK,
                        Material.QUARTZ_BLOCK, Material.STONE, Material.COBBLESTONE};
                entryItem = new ItemStack(rankMaterials[i]);
                entryMeta = entryItem.getItemMeta();
            }

            if (entryMeta != null) {
                entryMeta.displayName(Component.text(rankColor + "#" + entry.rank + " §f" + entry.name));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7" + stat.displayName + " : §e" + (int) entry.value));
                if (i == 0 && !rankMedals[0].isEmpty()) {
                    lore.add(Component.text(rankMedals[0]));
                }
                entryMeta.lore(lore);

                entryItem.setItemMeta(entryMeta);
            }
            gui.setItem(slot, new GuiItem(entryItem));
        }

        // Row 6: Border + Your Rank item at slot 49
        GuiItem borderItem = ItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        for (int i = 45; i < 54; i++) {
            if (i != 48) { // Skip slot 49 (your rank)
                gui.setItem(i, borderItem);
            }
        }

        // Your Rank at slot 49 (48 in 0-indexed) — placeholder, updated async after open
        ItemStack yourRankItem = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta yourRankMeta = yourRankItem.getItemMeta();
        yourRankMeta.displayName(Component.text("§bVotre Classement"));
        yourRankMeta.lore(List.of(
                Component.text("§7" + stat.displayName),
                Component.text("§7Chargement...")
        ));
        yourRankItem.setItemMeta(yourRankMeta);
        gui.setItem(48, new GuiItem(yourRankItem));

        gui.open(player);
        fetchAndUpdateRank(player, gui, stat);
    }

    private void fetchAndUpdateRank(Player player, Gui gui, StatDef stat) {
        UUID uuid = player.getUniqueId();
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps1 = conn.prepareStatement(
                    "SELECT " + stat.column + " FROM players WHERE uuid = ?")) {
                ps1.setString(1, uuid.toString());
                ResultSet rs1 = ps1.executeQuery();
                if (rs1.next()) {
                    int value = stat.column.equals("money_earned")
                            ? (int) rs1.getDouble(stat.column)
                            : rs1.getInt(stat.column);
                    try (PreparedStatement ps2 = conn.prepareStatement(
                            "SELECT COUNT(*) + 1 AS rank FROM players WHERE " + stat.column + " > ?")) {
                        if (stat.column.equals("money_earned")) ps2.setDouble(1, rs1.getDouble(stat.column));
                        else ps2.setInt(1, value);
                        ResultSet rs2 = ps2.executeQuery();
                        if (rs2.next()) return new int[]{rs2.getInt("rank"), value};
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Rank query error: " + e.getMessage());
            }
            return new int[]{0, 0};
        }).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                ItemStack rankItem = new ItemStack(Material.WRITABLE_BOOK);
                ItemMeta meta = rankItem.getItemMeta();
                meta.displayName(Component.text("§bVotre Classement"));
                meta.lore(List.of(
                        Component.text("§7" + stat.displayName),
                        Component.text("§f#" + result[0] + " - §e" + result[1] + " points")
                ));
                rankItem.setItemMeta(meta);
                gui.updateItem(48, new GuiItem(rankItem));
            });
        });
    }

    // ─── Events ─────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                updateScoreboard(event.getPlayer());
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    // ─── Commande ───────────────────────────────────────────────

    private class LeaderboardCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            openLeaderboard(player);
            return true;
        }
    }

    // ─── Data ───────────────────────────────────────────────────

    private record StatDef(String column, String displayName, Material icon) {}
    private record LeaderEntry(int rank, String name, double value) {}
}
