package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import com.survivalcore.ui.GuiBackground;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Module Menu Principal — GUI 54 slots (6 rows) accessible via /menu and F key (swap hand).
 * Beautiful professional survival RPG menu with gradient glass borders and comprehensive features.
 * All tabs are connected to existing modules.
 */
public class MenuModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        plugin.getCommand("menu").setExecutor(new MenuCommand());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Menu module enabled.");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("Menu module disabled.");
    }

    @Override
    public String getName() {
        return "Menu";
    }

    // ─── F Key Swap Handler ─────────────────────────────────────

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        // Shift+F ouvre le menu ; F seul = comportement vanilla (échange offhand)
        if (!event.getPlayer().isSneaking()) return;
        event.setCancelled(true);
        openMainMenu(event.getPlayer());
    }

    // ─── Main Menu (6 rows = 54 slots) ──────────────────────────

    public void openMainMenu(Player player) {
        Gui gui = Gui.gui()
                .title(GuiBackground.MENU.title("§8✦ §bSurvival Menu §8✦"))
                .rows(6)
                .disableAllInteractions()
                .create();

        // Fill all slots with black glass first
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // ─── Row 1 (0-8): Top Border Gradient & Player Head Profile ───
        // Gradient: dark gray (8) → gray (7) → light gray (8)
        gui.setItem(0, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(1, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(2, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(3, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));

        // Slot 4: Player Head with Profile Info
        gui.setItem(4, createPlayerHead(player));

        gui.setItem(5, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(6, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(7, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(8, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // ─── Row 2 (9-17): Core Gameplay Features ───────────────────
        gui.setItem(9, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 10: Class
        gui.setItem(10, ItemBuilder.from(Material.DIAMOND_SWORD)
                .name(Component.text("§b⚔ Ma Classe"))
                .lore(
                        Component.text("§7Affiche et change ta classe"),
                        Component.text("§7de combat RPG."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    ClassModule classModule = plugin.getModule(ClassModule.class);
                    if (classModule != null) classModule.openClassSelector(player);
                }));

        gui.setItem(11, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 12: Job
        gui.setItem(12, ItemBuilder.from(Material.DIAMOND_PICKAXE)
                .name(Component.text("§e🔨 Mon Job"))
                .lore(
                        Component.text("§7Consulte ton job et"),
                        Component.text("§7ta progression."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    JobModule jobModule = plugin.getModule(JobModule.class);
                    if (jobModule != null) jobModule.openJobGui(player);
                }));

        gui.setItem(13, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 14: Skills
        gui.setItem(14, ItemBuilder.from(Material.EXPERIENCE_BOTTLE)
                .name(Component.text("§a✦ Compétences"))
                .lore(
                        Component.text("§7Dépense tes points"),
                        Component.text("§7de compétence."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    SkillModule skillModule = plugin.getModule(SkillModule.class);
                    if (skillModule != null) skillModule.openSkillTree(player);
                }));

        gui.setItem(15, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 16: Quests
        gui.setItem(16, ItemBuilder.from(Material.BOOK)
                .name(Component.text("§6📜 Quêtes"))
                .lore(
                        Component.text("§7Tes quêtes du jour"),
                        Component.text("§7et récompenses."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    openQuestGui(player);
                }));

        gui.setItem(17, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // ─── Row 3 (18-26): Decorative Separator ────────────────────
        for (int i = 18; i <= 26; i++) {
            gui.setItem(i, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        }

        // ─── Row 4 (27-35): Social/Economy Features ─────────────────
        gui.setItem(27, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 28: Shop
        gui.setItem(28, ItemBuilder.from(Material.GOLD_INGOT)
                .name(Component.text("§6💰 Shop"))
                .lore(
                        Component.text("§7Achète ou revends"),
                        Component.text("§7des items."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    ShopModule shopModule = plugin.getModule(ShopModule.class);
                    if (shopModule != null) shopModule.openShopMain(player);
                }));

        gui.setItem(29, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 30: Market
        gui.setItem(30, ItemBuilder.from(Material.ENDER_CHEST)
                .name(Component.text("§e🏪 Marché"))
                .lore(
                        Component.text("§7Achète les items"),
                        Component.text("§7des autres joueurs."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    ShopModule shopModule = plugin.getModule(ShopModule.class);
                    if (shopModule != null) shopModule.openMarket(player);
                }));

        gui.setItem(31, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 32: Common Chest
        gui.setItem(32, ItemBuilder.from(Material.CHEST)
                .name(Component.text("§a📦 Coffre Commun"))
                .lore(
                        Component.text("§7Accède au coffre"),
                        Component.text("§7communautaire."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    ShopModule shopModule = plugin.getModule(ShopModule.class);
                    if (shopModule != null) shopModule.openCommonChest(player);
                }));

        gui.setItem(33, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 34: Leaderboard
        gui.setItem(34, ItemBuilder.from(Material.FILLED_MAP)
                .name(Component.text("§6🏆 Classements"))
                .lore(
                        Component.text("§7Vois les meilleurs"),
                        Component.text("§7joueurs du serveur."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    LeaderboardModule lbModule = plugin.getModule(LeaderboardModule.class);
                    if (lbModule != null) lbModule.openLeaderboard(player);
                }));

        gui.setItem(35, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // ─── Row 5 (36-44): Exploration Features ────────────────────
        gui.setItem(36, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 37: Arcs
        gui.setItem(37, ItemBuilder.from(Material.WRITABLE_BOOK)
                .name(Component.text("§5🗺 Arcs d'Exploration"))
                .lore(
                        Component.text("§7Quêtes longues et"),
                        Component.text("§7récompenses épiques."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    ArcModule arcModule = plugin.getModule(ArcModule.class);
                    if (arcModule != null) arcModule.openArcSelector(player);
                }));

        gui.setItem(38, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 39: Waypoints
        gui.setItem(39, ItemBuilder.from(Material.COMPASS)
                .name(Component.text("§b🧭 Points de Repère"))
                .lore(
                        Component.text("§7Gère tes waypoints"),
                        Component.text("§7de voyage rapide."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    MinimapModule minimapModule = plugin.getModule(MinimapModule.class);
                    if (minimapModule != null) minimapModule.openWaypointGui(player);
                }));

        gui.setItem(40, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 41: Claims
        gui.setItem(41, ItemBuilder.from(Material.GOLDEN_SHOVEL)
                .name(Component.text("§e⛏ Mes Claims"))
                .lore(
                        Component.text("§7Affiche tes terrains"),
                        Component.text("§7revendiqués."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    ClaimModule claimModule = plugin.getModule(ClaimModule.class);
                    if (claimModule != null) claimModule.openClaimList(player);
                }));

        gui.setItem(42, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Slot 43: Deaths
        gui.setItem(43, ItemBuilder.from(Material.SKELETON_SKULL)
                .name(Component.text("§c💀 Morts"))
                .lore(
                        Component.text("§7Historique de tes"),
                        Component.text("§7morts et trépassés."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    DeathModule deathModule = plugin.getModule(DeathModule.class);
                    if (deathModule != null) deathModule.openDeathGui(player);
                }));

        gui.setItem(44, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // ─── Row 6 (45-53): Bottom Border & Weekly Mission ──────────
        gui.setItem(45, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(46, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(47, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(48, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));

        // Slot 49: Weekly Mission
        gui.setItem(49, ItemBuilder.from(Material.NETHER_STAR)
                .name(Component.text("§d✦ Mission Hebdo"))
                .lore(
                        Component.text("§7Complète la mission"),
                        Component.text("§7communautaire du serveur."),
                        Component.text("§8─────────────────────")
                )
                .asGuiItem(event -> {
                    player.closeInventory();
                    openWeeklyGui(player);
                }));

        gui.setItem(50, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(51, createGlassPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(52, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(53, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));

        // Play sound effect
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);

        gui.open(player);
    }

    // ─── Helper Method: Create Glass Pane ────────────────────────

    private GuiItem createGlassPane(Material material) {
        return ItemBuilder.from(material)
                .name(Component.text(" "))
                .asGuiItem();
    }

    // ─── Helper Method: Create Player Head with Profile Info ──────

    private GuiItem createPlayerHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();

        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);

            int money = getPlayerMoney(player);
            int questsCompleted = getQuestsCompletedToday(player);
            String classInfo = getPlayerClassName(player);
            String weeklyStatus = getWeeklyStatus(player);

            java.util.List<Component> lore = new java.util.ArrayList<>();
            lore.add(Component.text("§8─────────────────────────"));
            lore.add(Component.text("§7Classe : §b" + classInfo));
            lore.add(Component.text("§8─────────────────────────"));

            // Afficher tous les métiers avec leurs niveaux
            JobModule jobModule = plugin.getModule(JobModule.class);
            if (jobModule != null) {
                java.util.Map<String, JobModule.JobProgress> allJobs = jobModule.getAllJobs(player.getUniqueId());
                if (allJobs.isEmpty()) {
                    lore.add(Component.text("§7Métiers : §8Aucune progression"));
                } else {
                    lore.add(Component.text("§e✦ Métiers :"));
                    for (String jobId : jobModule.getJobIds()) {
                        JobModule.JobProgress prog = allJobs.getOrDefault(jobId, new JobModule.JobProgress(0, 1));
                        String jobName = jobModule.getJobDisplayName(jobId);
                        int req = jobModule.xpForLevel(prog.level + 1);
                        int pct = req > 0 ? (int)((double) prog.xp / req * 100) : 0;
                        String bar = prog.level > 1 || prog.xp > 0
                                ? "§8[§a" + "█".repeat(pct / 10) + "§8" + "█".repeat(10 - pct / 10) + "§8] "
                                : "§8[" + "█".repeat(10) + "§8] ";
                        lore.add(Component.text("  §7" + jobName + " §8Niv.§f" + prog.level + " " + bar + "§8(" + pct + "%)"));
                    }
                }
            }

            lore.add(Component.text("§8─────────────────────────"));
            lore.add(Component.text("§6Argent : §f" + money + " ✦"));
            lore.add(Component.text("§aQuêtes : §f" + questsCompleted + "§7/3 aujourd'hui"));
            lore.add(Component.text("§dMission Hebdo : §f" + weeklyStatus));
            lore.add(Component.text("§8─────────────────────────"));

            skullMeta.displayName(Component.text("§b§l" + player.getName()));
            skullMeta.lore(lore);
            head.setItemMeta(skullMeta);
        }

        return ItemBuilder.from(head).asGuiItem();
    }

    // ─── Helper Methods: Get Player Stats ────────────────────────

    private int getPlayerMoney(Player player) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco != null) {
            return (int) eco.getBalance(player.getUniqueId());
        }
        return 0;
    }

    private int getQuestsCompletedToday(Player player) {
        QuestModule quest = plugin.getModule(QuestModule.class);
        if (quest != null) {
            var quests = quest.getPlayerQuests(player.getUniqueId());
            return (int) quests.stream().filter(q -> q.completed).count();
        }
        return 0;
    }

    private String getPlayerClassName(Player player) {
        ClassModule classModule = plugin.getModule(ClassModule.class);
        if (classModule != null) {
            String classId = classModule.getPlayerClassId(player.getUniqueId());
            if (classId != null && !classId.equals("NONE")) {
                return classModule.getClassDisplayName(classId);
            }
        }
        return "Aucune";
    }

    private String getPlayerJobInfo(Player player) {
        JobModule jobModule = plugin.getModule(JobModule.class);
        if (jobModule != null) {
            String jobId = jobModule.getPlayerJobId(player.getUniqueId());
            int level = jobModule.getPlayerJobLevel(player.getUniqueId());
            if (jobId != null && !jobId.equals("NONE")) {
                return jobModule.getJobDisplayName(jobId) + " Niv." + level;
            }
        }
        return "Aucun";
    }

    private String getWeeklyStatus(Player player) {
        WeeklyModule weekly = plugin.getModule(WeeklyModule.class);
        if (weekly != null) {
            var participation = weekly.getParticipation(player.getUniqueId());
            if (participation != null && participation.completed) {
                return "§a✓ Terminée";
            }
            var mission = weekly.getCurrentMission();
            if (mission != null && participation != null) {
                return "§7" + participation.progress + "/" + mission.amountPerPlayer;
            }
        }
        return "§7En attente";
    }

    // ─── Quest Sub-Menu ─────────────────────────────────────────

    private void openQuestGui(Player player) {
        QuestModule quest = plugin.getModule(QuestModule.class);
        if (quest == null) return;

        var quests = quest.getPlayerQuests(player.getUniqueId());
        int doneCount = (int) quests.stream().filter(q -> q.completed).count();

        Gui gui = Gui.gui()
                .title(GuiBackground.QUEST.title("§0✦ §e§lQuêtes du Jour §0✦"))
                .rows(4)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Header doré
        GuiItem golden = ItemBuilder.from(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        for (int i = 0; i < 9; i++) {
            if (i != 4) gui.setItem(i, golden);
        }

        // Titre centré
        gui.setItem(4, ItemBuilder.from(Material.BOOK)
                .name(Component.text("§e§lQuêtes du Jour"))
                .lore(
                        Component.text("§7Complétées : §a" + doneCount + "§7/§f3"),
                        Component.text("§7" + (doneCount == 3 ? "§a✦ Toutes les quêtes accomplies !" : "§8Reviens demain pour de nouvelles quêtes"))
                )
                .asGuiItem());

        // 3 quêtes sur slots 11, 13, 15 (row 1 centrée)
        int[] slots = {11, 13, 15};
        String[] diffColors = {"§a", "§e", "§c"};

        for (int i = 0; i < Math.min(3, quests.size()); i++) {
            QuestModule.ActiveQuest q = quests.get(i);
            boolean done = q.completed;
            Material icon = done ? Material.LIME_DYE
                    : q.definition.difficulty.equals("HARD") ? Material.PAPER
                    : q.definition.difficulty.equals("MEDIUM") ? Material.PAPER
                    : Material.PAPER;
            String diffColor = switch (q.definition.difficulty) {
                case "HARD" -> "§c";
                case "MEDIUM" -> "§e";
                default -> "§a";
            };

            int pct = q.definition.amount > 0 ? (int)((double) q.progress / q.definition.amount * 100) : 0;
            String bar = "§a" + "█".repeat(pct / 10) + "§8" + "█".repeat(10 - pct / 10);

            java.util.List<Component> lore = new java.util.ArrayList<>();
            lore.add(Component.text("§8─────────────────────────"));
            lore.add(Component.text("§7Difficulté : " + diffColor + q.definition.difficulty));
            lore.add(Component.text("§8─────────────────────────"));
            lore.add(Component.text("§7Progression : §f" + q.progress + "§8/§f" + q.definition.amount));
            lore.add(Component.text(bar + " §8(" + pct + "%)"));
            lore.add(Component.text("§8─────────────────────────"));
            lore.add(Component.text("§6Récompense : §f" + (int) q.definition.rewardMoney + " ✦ §8+ §b" + q.definition.rewardXp + " XP"));
            if (done) lore.add(Component.text("§a§l✓ QUÊTE ACCOMPLIE !"));

            Material finalIcon = done ? Material.LIME_DYE : Material.PAPER;
            gui.setItem(slots[i], ItemBuilder.from(finalIcon)
                    .name(Component.text((done ? "§a§l" : "§e§l") + q.definition.display))
                    .lore(lore)
                    .asGuiItem());
        }

        // Bouton retour
        gui.setItem(31, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Retour au Menu"))
                .asGuiItem(e -> openMainMenu(player)));

        gui.open(player);
    }

    // ─── Weekly Mission Sub-Menu ────────────────────────────────

    private void openWeeklyGui(Player player) {
        WeeklyModule weekly = plugin.getModule(WeeklyModule.class);
        if (weekly == null) return;

        Gui gui = Gui.gui()
                .title(GuiBackground.QUEST.title("§8✦ §dMission Hebdo §8✦"))
                .rows(3)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        var mission = weekly.getCurrentMission();
        var participation = weekly.getParticipation(player.getUniqueId());

        if (mission != null) {
            String status = participation != null
                    ? (participation.completed ? "§a✓ Terminée" : "§7" + participation.progress + "/" + mission.amountPerPlayer)
                    : "§70/" + mission.amountPerPlayer;

            gui.setItem(13, ItemBuilder.from(Material.NETHER_STAR)
                    .name(Component.text("§d" + mission.display))
                    .lore(
                            Component.text("§7" + mission.description),
                            Component.text(""),
                            Component.text("§7Ta progression : " + status),
                            Component.text("§7Récompense : §6" + (int) mission.rewardMoney + " ✦ §7+ §b" + mission.rewardXp + " XP"),
                            Component.text("§eBonus x2 si tout le monde complète !")
                    )
                    .asGuiItem());
        } else {
            gui.setItem(13, ItemBuilder.from(Material.BARRIER)
                    .name(Component.text("§7Aucune mission cette semaine"))
                    .asGuiItem());
        }

        // Back button
        gui.setItem(22, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Retour au Menu"))
                .asGuiItem(e -> openMainMenu(player)));

        gui.open(player);
    }

    // ─── Menu Command ───────────────────────────────────────────

    private class MenuCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            openMainMenu(player);
            return true;
        }
    }
}
