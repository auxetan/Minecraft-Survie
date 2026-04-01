package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Arcs d'Exploration — quêtes longues multi-étapes.
 * Types : REACH_LOCATION, KILL_BOSS, DELIVER_ITEM, EXPLORE_BIOME, GATHER.
 * Un seul arc actif par joueur.
 */
public class ArcModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private YamlConfiguration arcsConfig;

    // Définitions des arcs
    private final Map<String, ArcDefinition> arcDefinitions = new LinkedHashMap<>();
    // Cache : UUID → progression d'arc actif
    private final Map<UUID, ArcProgress> playerArcs = new ConcurrentHashMap<>();
    // Throttle PlayerMoveEvent (1 check / seconde max par joueur)
    private final Map<UUID, Long> lastMoveCheck = new ConcurrentHashMap<>();
    // Cache des coordonnées parsées pour REACH_LOCATION (step_target → double[3])
    private final Map<String, double[]> parsedLocationCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        loadArcsConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerArc(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("arcs").setExecutor(new ArcsCommand());
        plugin.getLogger().info("Arc module enabled — " + arcDefinitions.size() + " arcs chargés.");
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, ArcProgress> entry : playerArcs.entrySet()) {
            saveArcProgressSync(entry.getKey(), entry.getValue());
        }
        plugin.getLogger().info("Arc module disabled.");
    }

    @Override
    public String getName() {
        return "Arc";
    }

    // ─── Commande /arcs ─────────────────────────────────────────

    private class ArcsCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            UUID uuid = player.getUniqueId();
            ArcProgress prog = playerArcs.get(uuid);
            if (prog == null) {
                player.sendMessage("§8§m                                        ");
                player.sendMessage("§6§l✦ ARCS D'EXPLORATION");
                player.sendMessage("§7Aucun arc en cours.");
                player.sendMessage("§7Les arcs se débloquent automatiquement");
                player.sendMessage("§7en explorant le monde !");
                player.sendMessage("§8§m                                        ");
                return true;
            }
            ArcDefinition def = arcDefinitions.get(prog.arcId);
            if (def == null) {
                player.sendMessage("§cArc inconnu.");
                return true;
            }
            player.sendMessage("§8§m                                        ");
            player.sendMessage("§6§l✦ ARC : §e" + def.display);
            player.sendMessage("§7Étape §b" + (prog.currentStep + 1) + "§7/" + def.steps.size());
            if (prog.currentStep < def.steps.size()) {
                ArcStep step = def.steps.get(prog.currentStep);
                player.sendMessage("§7Objectif : §f" + step.description);
                if (step.hint != null) player.sendMessage("§8Indice : §7" + step.hint);
            }
            player.sendMessage("§8§m                                        ");
            return true;
        }
    }

    // ─── Config ─────────────────────────────────────────────────

    private void loadArcsConfig() {
        File f = new File(plugin.getDataFolder(), "data/arcs.yml");
        if (!f.exists()) plugin.saveResource("data/arcs.yml", false);
        arcsConfig = YamlConfiguration.loadConfiguration(f);

        ConfigurationSection arcsSec = arcsConfig.getConfigurationSection("arcs");
        if (arcsSec == null) return;

        for (String arcId : arcsSec.getKeys(false)) {
            ConfigurationSection arcSec = arcsSec.getConfigurationSection(arcId);
            if (arcSec == null) continue;

            String display = arcSec.getString("display", arcId);
            String description = arcSec.getString("description", "");
            double rewardMoney = arcSec.getDouble("reward-money", 500);
            int rewardXp = arcSec.getInt("reward-xp", 1000);
            String rewardTitle = arcSec.getString("reward-title", "");

            List<ArcStep> steps = new ArrayList<>();
            ConfigurationSection stepsSec = arcSec.getConfigurationSection("steps");
            if (stepsSec != null) {
                for (String stepKey : stepsSec.getKeys(false)) {
                    ConfigurationSection step = stepsSec.getConfigurationSection(stepKey);
                    if (step == null) continue;
                    steps.add(new ArcStep(
                            Integer.parseInt(stepKey),
                            step.getString("type", "GATHER"),
                            step.getString("target", ""),
                            step.getInt("amount", 1),
                            step.getInt("radius", 50),
                            step.getString("world", "world"),
                            step.getString("description", ""),
                            step.getString("hint", "")
                    ));
                }
            }
            steps.sort(Comparator.comparingInt(s -> s.stepNumber));
            arcDefinitions.put(arcId, new ArcDefinition(arcId, display, description,
                    rewardMoney, rewardXp, rewardTitle, steps));
        }
    }

    // ─── API publique ───────────────────────────────────────────

    public Map<String, ArcDefinition> getArcDefinitions() {
        return arcDefinitions;
    }

    public ArcProgress getPlayerArc(UUID uuid) {
        return playerArcs.get(uuid);
    }

    public boolean startArc(UUID uuid, String arcId) {
        ArcProgress current = playerArcs.get(uuid);
        if (current != null && !current.completed) {
            return false; // Déjà un arc actif
        }
        ArcDefinition def = arcDefinitions.get(arcId);
        if (def == null) return false;

        ArcProgress prog = new ArcProgress(arcId, 1, 0, false);
        playerArcs.put(uuid, prog);
        saveArcProgressAsync(uuid, prog);
        return true;
    }

    // ─── GUI Arc Selection ──────────────────────────────────────

    public void openArcSelector(Player player) {
        UUID uuid = player.getUniqueId();
        ArcProgress current = playerArcs.get(uuid);

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §5Arcs d'Exploration §8✦"))
                .rows(4)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        int slot = 10;
        for (ArcDefinition arc : arcDefinitions.values()) {
            boolean isActive = current != null && current.arcId.equals(arc.id) && !current.completed;
            boolean isCompleted = current != null && current.arcId.equals(arc.id) && current.completed;

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7" + arc.description));
            lore.add(Component.text("§7Étapes : §e" + arc.steps.size()));
            lore.add(Component.text("§7Récompense : §6" + (int) arc.rewardMoney + " ✦ §7+ §b" + arc.rewardXp + " XP"));
            if (!arc.rewardTitle.isEmpty()) lore.add(Component.text("§7Titre : " + arc.rewardTitle));
            lore.add(Component.text(""));

            Material icon;
            String prefix;
            if (isActive) {
                icon = Material.WRITABLE_BOOK;
                prefix = "§e▶ ";
                ArcStep currentStep = arc.steps.stream()
                        .filter(s -> s.stepNumber == current.currentStep)
                        .findFirst().orElse(null);
                if (currentStep != null) {
                    lore.add(Component.text("§aÉtape " + current.currentStep + "/" + arc.steps.size()));
                    lore.add(Component.text("§7" + currentStep.description));
                    lore.add(Component.text("§8Indice: " + currentStep.hint));
                }
            } else if (isCompleted) {
                icon = Material.ENCHANTED_BOOK;
                prefix = "§a✓ ";
                lore.add(Component.text("§a§lTerminé !"));
            } else {
                icon = Material.BOOK;
                prefix = "§f";
                lore.add(Component.text("§aClic pour commencer"));
            }

            GuiItem item = ItemBuilder.from(icon)
                    .name(Component.text(prefix + arc.display))
                    .lore(lore)
                    .asGuiItem(event -> {
                        if (!isActive && !isCompleted) {
                            if (startArc(uuid, arc.id)) {
                                player.sendMessage("§5✦ Arc commencé : " + arc.display);
                                openArcSelector(player);
                            } else {
                                player.sendMessage("§cTu as déjà un arc en cours !");
                            }
                        }
                    });
            gui.setItem(slot, item);
            slot += 2;
            if (slot > 16) slot = 28; // Ligne suivante
        }

        gui.open(player);
    }

    // ─── Progression Tracking ───────────────────────────────────

    private void progressArc(UUID uuid, String type, String target, int amount) {
        ArcProgress prog = playerArcs.get(uuid);
        if (prog == null || prog.completed) return;

        ArcDefinition def = arcDefinitions.get(prog.arcId);
        if (def == null) return;

        ArcStep currentStep = def.steps.stream()
                .filter(s -> s.stepNumber == prog.currentStep)
                .findFirst().orElse(null);
        if (currentStep == null) return;

        if (!currentStep.type.equals(type)) return;
        if (!currentStep.target.equalsIgnoreCase(target)) return;

        prog.stepProgress += amount;

        if (prog.stepProgress >= currentStep.amount) {
            // Étape complétée
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage("§5✦ Étape " + prog.currentStep + " complétée : §f" + currentStep.description);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
            }

            if (prog.currentStep >= def.steps.size()) {
                // Arc terminé !
                prog.completed = true;
                completeArc(uuid, def);
            } else {
                prog.currentStep++;
                prog.stepProgress = 0;

                if (player != null) {
                    ArcStep nextStep = def.steps.stream()
                            .filter(s -> s.stepNumber == prog.currentStep)
                            .findFirst().orElse(null);
                    if (nextStep != null) {
                        player.sendMessage("§5✦ Prochaine étape : §f" + nextStep.description);
                        player.sendMessage("§8Indice : " + nextStep.hint);
                    }
                }
            }
        }

        saveArcProgressAsync(uuid, prog);
    }

    private void completeArc(UUID uuid, ArcDefinition def) {
        Player player = Bukkit.getPlayer(uuid);

        // Récompenses
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco != null) eco.deposit(uuid, def.rewardMoney);

        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET general_xp = general_xp + ? WHERE uuid = ?")) {
                ps.setInt(1, def.rewardXp);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur completion arc: " + e.getMessage());
            }
        });

        if (player != null) {
            player.sendMessage("§8§m                                        ");
            player.sendMessage("§5§l🌟 ARC TERMINÉ ! §f" + def.display);
            player.sendMessage("§7Récompense : §6" + (int) def.rewardMoney + " ✦ §7+ §b" + def.rewardXp + " XP");
            if (!def.rewardTitle.isEmpty()) {
                player.sendMessage("§7Titre obtenu : " + def.rewardTitle);
            }
            player.sendMessage("§8§m                                        ");
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

            // Broadcast via AnnouncerModule
            AnnouncerModule announcer = plugin.getModule(AnnouncerModule.class);
            if (announcer != null) {
                announcer.announceArcComplete(player.getName(), def.display);
            }
        }
    }

    // ─── Listeners ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player player = event.getEntity().getKiller();
        String entityType = event.getEntityType().name();
        progressArc(player.getUniqueId(), "KILL_BOSS", entityType, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String mat = event.getBlock().getType().name();
        progressArc(player.getUniqueId(), "GATHER", mat, 1);
    }

    // Items obtenus hors des blocs : pickup au sol
    private static final Set<String> PICKUP_TRACKED_ITEMS = Set.of(
            "ECHO_SHARD", "HEART_OF_THE_SEA", "DRAGON_EGG", "NETHER_STAR",
            "WITHER_SKELETON_SKULL", "TOTEM_OF_UNDYING", "PRISMARINE_SHARD",
            "ENDER_PEARL"
    );
    // Items obtenus par craft
    private static final Set<String> CRAFT_TRACKED_ITEMS = Set.of(
            "BLAZE_POWDER", "MUSHROOM_STEW"
    );

    /** Ramassage d'items au sol (ENDER_PEARL, NETHER_STAR, etc.) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        ArcProgress prog = playerArcs.get(uuid);
        if (prog == null || prog.completed) return;
        ArcDefinition def = arcDefinitions.get(prog.arcId);
        if (def == null) return;
        ArcStep step = def.steps.stream().filter(s -> s.stepNumber == prog.currentStep).findFirst().orElse(null);
        if (step == null || !step.type.equals("GATHER")) return;

        String matName = event.getItem().getItemStack().getType().name();
        if (!matName.equals(step.target)) return;
        if (!PICKUP_TRACKED_ITEMS.contains(matName)) return;

        progressArc(uuid, "GATHER", matName, event.getItem().getItemStack().getAmount());
    }

    /** Prise depuis un coffre/structure vers l'inventaire joueur (shift-clic). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) return;
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory().equals(player.getInventory())) return;

        UUID uuid = player.getUniqueId();
        ArcProgress prog = playerArcs.get(uuid);
        if (prog == null || prog.completed) return;
        ArcDefinition def = arcDefinitions.get(prog.arcId);
        if (def == null) return;
        ArcStep step = def.steps.stream().filter(s -> s.stepNumber == prog.currentStep).findFirst().orElse(null);
        if (step == null || !step.type.equals("GATHER")) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        String matName = clicked.getType().name();
        if (!matName.equals(step.target)) return;
        if (!PICKUP_TRACKED_ITEMS.contains(matName)) return;

        progressArc(uuid, "GATHER", matName, clicked.getAmount());
    }

    /** Craft d'items : BLAZE_POWDER, MUSHROOM_STEW, etc. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String matName = event.getRecipe().getResult().getType().name();
        if (!CRAFT_TRACKED_ITEMS.contains(matName)) return;
        UUID uuid = player.getUniqueId();
        ArcProgress prog = playerArcs.get(uuid);
        if (prog == null || prog.completed) return;
        ArcDefinition def = arcDefinitions.get(prog.arcId);
        if (def == null) return;
        ArcStep step = def.steps.stream().filter(s -> s.stepNumber == prog.currentStep).findFirst().orElse(null);
        if (step == null || !step.type.equals("GATHER") || !step.target.equals(matName)) return;
        progressArc(uuid, "GATHER", matName, event.getRecipe().getResult().getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Throttle : 1 traitement maximum par seconde (mini PC Ace Magician)
        long now = System.currentTimeMillis();
        if (now - lastMoveCheck.getOrDefault(uuid, 0L) < 1000L) return;
        lastMoveCheck.put(uuid, now);
        ArcProgress prog = playerArcs.get(uuid);
        if (prog == null || prog.completed) return;

        ArcDefinition def = arcDefinitions.get(prog.arcId);
        if (def == null) return;
        ArcStep step = def.steps.stream()
                .filter(s -> s.stepNumber == prog.currentStep)
                .findFirst().orElse(null);
        if (step == null) return;

        // EXPLORE_BIOME
        if (step.type.equals("EXPLORE_BIOME")) {
            Biome biome = player.getLocation().getBlock().getBiome();
            if (biome.getKey().getKey().equalsIgnoreCase(step.target)) {
                progressArc(uuid, "EXPLORE_BIOME", step.target, 1);
            }
        }

        // REACH_LOCATION — coordonnées cachées pour perf
        if (step.type.equals("REACH_LOCATION")) {
            double[] coords = parsedLocationCache.computeIfAbsent(step.target, t -> {
                try {
                    String[] parts = t.replace("\u2212", "-").split(",");
                    return new double[]{
                            Double.parseDouble(parts[0].trim()),
                            Double.parseDouble(parts[1].trim()),
                            Double.parseDouble(parts[2].trim())
                    };
                } catch (Exception e) {
                    plugin.getLogger().warning("Arc: impossible de parser les coords '" + t + "'");
                    return null;
                }
            });
            if (coords != null) {
                double dx = player.getLocation().getX() - coords[0];
                double dy = player.getLocation().getY() - coords[1];
                double dz = player.getLocation().getZ() - coords[2];
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= (double) step.radius * step.radius) {
                    progressArc(uuid, "REACH_LOCATION", step.target, 1);
                }
            }
        }
    }

    // ─── Join/Quit ──────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPlayerArc(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ArcProgress prog = playerArcs.remove(uuid);
        if (prog != null) saveArcProgressAsync(uuid, prog);
        lastMoveCheck.remove(uuid);
    }

    // ─── Persistance ────────────────────────────────────────────

    private void loadPlayerArc(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT arc_id, current_step, step_progress, completed FROM arc_progress WHERE uuid = ? AND completed = 0 LIMIT 1")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new ArcProgress(
                            rs.getString("arc_id"),
                            rs.getInt("current_step"),
                            rs.getInt("step_progress"),
                            rs.getInt("completed") == 1
                    );
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement arc: " + e.getMessage());
            }
            return null;
        }).thenAccept(prog -> {
            if (prog != null) playerArcs.put(uuid, prog);
        });
    }

    private void saveArcProgressAsync(UUID uuid, ArcProgress prog) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO arc_progress (uuid, arc_id, current_step, step_progress, completed) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, prog.arcId);
                ps.setInt(3, prog.currentStep);
                ps.setInt(4, prog.stepProgress);
                ps.setInt(5, prog.completed ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde arc: " + e.getMessage());
            }
        });
    }

    private void saveArcProgressSync(UUID uuid, ArcProgress prog) {
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO arc_progress (uuid, arc_id, current_step, step_progress, completed) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, prog.arcId);
                    ps.setInt(3, prog.currentStep);
                    ps.setInt(4, prog.stepProgress);
                    ps.setInt(5, prog.completed ? 1 : 0);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Erreur sauvegarde sync arc: " + e.getMessage());
        }
    }

    // ─── Data ───────────────────────────────────────────────────

    public static class ArcDefinition {
        public final String id, display, description, rewardTitle;
        public final double rewardMoney;
        public final int rewardXp;
        public final List<ArcStep> steps;

        public ArcDefinition(String id, String display, String description,
                             double rewardMoney, int rewardXp, String rewardTitle, List<ArcStep> steps) {
            this.id = id; this.display = display; this.description = description;
            this.rewardMoney = rewardMoney; this.rewardXp = rewardXp;
            this.rewardTitle = rewardTitle; this.steps = steps;
        }
    }

    public static class ArcStep {
        public final int stepNumber;
        public final String type, target, description, hint, world;
        public final int amount, radius;

        public ArcStep(int stepNumber, String type, String target, int amount,
                       int radius, String world, String description, String hint) {
            this.stepNumber = stepNumber; this.type = type; this.target = target;
            this.amount = amount; this.radius = radius; this.world = world;
            this.description = description; this.hint = hint;
        }
    }

    public static class ArcProgress {
        public String arcId;
        public int currentStep;
        public int stepProgress;
        public boolean completed;

        public ArcProgress(String arcId, int currentStep, int stepProgress, boolean completed) {
            this.arcId = arcId; this.currentStep = currentStep;
            this.stepProgress = stepProgress; this.completed = completed;
        }
    }
}
