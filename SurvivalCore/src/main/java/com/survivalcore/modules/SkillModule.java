package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Arbre de Compétences.
 * XP générale → points de compétence (1pt / 500 XP).
 * Nœuds achetables avec prérequis, effets appliqués immédiatement.
 * Inspiré AuraSkills : système de stats passives.
 */
public class SkillModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // Définition des nœuds de l'arbre
    private final List<SkillNode> skillNodes = new ArrayList<>();

    // Cache : UUID → set de skill_id acquis
    private final Map<UUID, Set<String>> playerSkills = new ConcurrentHashMap<>();
    // Cache : UUID → points disponibles (calculés depuis general_xp)
    private final Map<UUID, Integer> availablePoints = new ConcurrentHashMap<>();
    // Cache : UUID → general_xp
    private final Map<UUID, Integer> playerGeneralXp = new ConcurrentHashMap<>();

    // XP par point de compétence
    private static final int XP_PER_POINT = 500;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        registerSkillNodes();

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerSkills(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("Skill module enabled — " + skillNodes.size() + " nœuds.");
    }

    @Override
    public void onDisable() {
        // Retirer les effets permanents
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeAllEffects(p);
        }
        plugin.getLogger().info("Skill module disabled.");
    }

    @Override
    public String getName() {
        return "Skill";
    }

    // ─── Définition des nœuds ───────────────────────────────────

    private void registerSkillNodes() {
        // Defense tree (left side)
        skillNodes.add(new SkillNode("resistance_1", "Résistance I",    1, 10, Material.IRON_CHESTPLATE,  null,
                "§7-10% dégâts reçus", SkillEffect.DAMAGE_REDUCTION_10));
        skillNodes.add(new SkillNode("resistance_2", "Résistance II",   3, 19, Material.DIAMOND_CHESTPLATE, "resistance_1",
                "§7-25% dégâts reçus", SkillEffect.DAMAGE_REDUCTION_25));
        skillNodes.add(new SkillNode("vitality",     "Vitalité",        2, 12, Material.GOLDEN_APPLE,      null,
                "§7+2 cœurs max permanents", SkillEffect.EXTRA_HEARTS));
        skillNodes.add(new SkillNode("swim",         "Nage",            1, 14, Material.HEART_OF_THE_SEA,  null,
                "§7Respiration prolongée x2", SkillEffect.WATER_BREATHING));
        // Utility tree (right side)
        skillNodes.add(new SkillNode("speed",        "Vitesse",         1, 16, Material.SUGAR,             null,
                "§7Speed I permanent", SkillEffect.SPEED));
        skillNodes.add(new SkillNode("luck",         "Chance",          2, 25, Material.RABBIT_FOOT,       null,
                "§7+10% loot sur les mobs", SkillEffect.LOOT_BONUS));
        // Economy tree (bottom)
        skillNodes.add(new SkillNode("smith",        "Forgeron",        2, 29, Material.ANVIL,             null,
                "§7-20% coût de réparation", SkillEffect.REPAIR_DISCOUNT));
        skillNodes.add(new SkillNode("merchant",     "Marchand",        3, 31, Material.EMERALD,           null,
                "§7-10% prix shop", SkillEffect.SHOP_DISCOUNT));
        skillNodes.add(new SkillNode("explorer",     "Explorateur",     2, 33, Material.COMPASS,           null,
                "§7+15% XP quêtes", SkillEffect.QUEST_XP_BONUS));
        skillNodes.add(new SkillNode("collector",    "Collecteur",      3, 35, Material.GOLD_NUGGET,       null,
                "§7+20% argent gagné via job", SkillEffect.JOB_MONEY_BONUS));
    }

    public SkillNode getNode(String id) {
        for (SkillNode node : skillNodes) {
            if (node.id.equals(id)) return node;
        }
        return null;
    }

    public boolean hasSkill(UUID uuid, String skillId) {
        Set<String> skills = playerSkills.get(uuid);
        return skills != null && skills.contains(skillId);
    }

    public int getAvailablePoints(UUID uuid) {
        return availablePoints.getOrDefault(uuid, 0);
    }

    // ─── GUI Arbre de Compétences ───────────────────────────────

    public void openSkillTree(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> owned = playerSkills.getOrDefault(uuid, new HashSet<>());
        int points = getAvailablePoints(uuid);
        int generalXp = playerGeneralXp.getOrDefault(uuid, 0);
        int unlockedCount = owned.size();

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §bArbre de Compétences §8✦"))
                .rows(6)
                .disableAllInteractions()
                .create();

        // Fond sombre
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Row 1: Header border (light blue glass) + info item at slot 4
        GuiItem headerBorder = ItemBuilder.from(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        for (int i = 0; i < 9; i++) {
            if (i != 3) {
                gui.setItem(i, headerBorder);
            }
        }

        // Info item at slot 4 (TriumphGUI uses 1-indexed, so slot 4 is index 3)
        ItemStack infoItem = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.displayName(Component.text("§6Arbre de Compétences"));
        infoMeta.lore(List.of(
                Component.text("§7Points disponibles: §a" + points),
                Component.text("§7XP Générale: §b" + generalXp),
                Component.text("§7Compétences débloquées: §e" + unlockedCount + "§7/10")
        ));
        infoItem.setItemMeta(infoMeta);
        gui.setItem(3, new GuiItem(infoItem));

        // Add chain connector between resistance_1 (slot 10) and resistance_2 (slot 19)
        gui.setItem(18, ItemBuilder.from(Material.CHAIN)
                .name(Component.text(" ")).asGuiItem());

        // Place skill nodes
        for (SkillNode node : skillNodes) {
            GuiItem item;

            if (owned.contains(node.id)) {
                // Owned: Gold block background, green name with checkmark, glow effect
                ItemStack ownedItem = new ItemStack(Material.GOLD_BLOCK);
                ItemMeta ownedMeta = ownedItem.getItemMeta();
                ownedMeta.displayName(Component.text("§6✓ " + node.displayName));
                ownedMeta.lore(List.of(
                        Component.text(node.description),
                        Component.text("§a§lDébloqué")
                ));
                ownedMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
                ownedMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                ownedItem.setItemMeta(ownedMeta);
                item = new GuiItem(ownedItem);
            } else if (node.requires != null && !owned.contains(node.requires)) {
                // Locked: Barrier, red name, red lore saying what's required
                SkillNode req = getNode(node.requires);
                String reqName = req != null ? req.displayName : node.requires;
                item = ItemBuilder.from(Material.BARRIER)
                        .name(Component.text("§c✗ " + node.displayName))
                        .lore(
                                Component.text(node.description),
                                Component.text("§cRequiert : " + reqName),
                                Component.text("§7Coût : §e" + node.cost + " pts")
                        )
                        .asGuiItem();
            } else if (points < node.cost) {
                // Unavailable (not enough points): Gray glass, gray name, shows cost
                item = ItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE)
                        .name(Component.text("§7" + node.displayName))
                        .lore(
                                Component.text(node.description),
                                Component.text("§7Coût : §e" + node.cost + " pts"),
                                Component.text("§cPas assez de points")
                        )
                        .asGuiItem();
            } else {
                // Available: Actual node icon with enchant glow, green name, "Click to buy" lore
                ItemStack availItem = new ItemStack(node.icon);
                ItemMeta availMeta = availItem.getItemMeta();
                availMeta.displayName(Component.text("§a▸ " + node.displayName));
                availMeta.lore(List.of(
                        Component.text(node.description),
                        Component.text("§7Coût : §e" + node.cost + " pts"),
                        Component.text(""),
                        Component.text("§aClic pour acheter")
                ));
                availMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
                availMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                availItem.setItemMeta(availMeta);

                item = new GuiItem(availItem, event -> {
                    purchaseSkill(player, node);
                });
            }

            gui.setItem(node.slot, item);
        }

        // Row 6: Bottom border
        GuiItem bottomBorder = ItemBuilder.from(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        for (int i = 45; i < 54; i++) {
            gui.setItem(i, bottomBorder);
        }

        gui.open(player);
    }

    private void purchaseSkill(Player player, SkillNode node) {
        UUID uuid = player.getUniqueId();
        Set<String> owned = playerSkills.computeIfAbsent(uuid, k -> new HashSet<>());
        int points = getAvailablePoints(uuid);

        if (owned.contains(node.id)) {
            player.sendMessage("§cCompétence déjà débloquée.");
            return;
        }
        if (node.requires != null && !owned.contains(node.requires)) {
            player.sendMessage("§cPrérequis non rempli !");
            return;
        }
        if (points < node.cost) {
            player.sendMessage("§cPas assez de points ! (§e" + points + "§c/§e" + node.cost + "§c)");
            return;
        }

        // Acheter
        owned.add(node.id);
        availablePoints.put(uuid, points - node.cost);

        // Persister en DB async
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO skills (uuid, skill_id) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, node.id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur achat compétence: " + e.getMessage());
            }
            // Mettre à jour skill_points consommés
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET skill_points = skill_points - ? WHERE uuid = ?")) {
                ps.setInt(1, node.cost);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur MAJ skill_points: " + e.getMessage());
            }
        });

        // Appliquer l'effet immédiatement
        applyEffect(player, node.effect);

        player.sendMessage("§a✦ Compétence débloquée : §f" + node.displayName);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);

        // Rafraîchir le GUI
        openSkillTree(player);
    }

    // ─── Application des effets ─────────────────────────────────

    public void applyAllEffects(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> owned = playerSkills.getOrDefault(uuid, new HashSet<>());

        for (SkillNode node : skillNodes) {
            if (owned.contains(node.id)) {
                applyEffect(player, node.effect);
            }
        }
    }

    private void applyEffect(Player player, SkillEffect effect) {
        switch (effect) {
            case SPEED -> player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, Integer.MAX_VALUE, 0, true, false, true));
            case WATER_BREATHING -> player.addPotionEffect(new PotionEffect(
                    PotionEffectType.WATER_BREATHING, Integer.MAX_VALUE, 0, true, false, true));
            case EXTRA_HEARTS -> {
                AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
                if (attr != null) {
                    NamespacedKey vitalityKey = new NamespacedKey(plugin, "vitality");
                    // Vérifier si déjà appliqué
                    boolean alreadyApplied = attr.getModifiers().stream()
                            .anyMatch(m -> m.getKey().equals(vitalityKey));
                    if (!alreadyApplied) {
                        // Paper 1.21.4 — constructeur 4 args avec EquipmentSlotGroup
                        attr.addModifier(new AttributeModifier(
                                vitalityKey,
                                4.0, // +2 cœurs = +4 HP
                                AttributeModifier.Operation.ADD_NUMBER,
                                org.bukkit.inventory.EquipmentSlotGroup.ANY
                        ));
                    }
                }
            }
            case LOOT_BONUS -> player.addPotionEffect(new PotionEffect(
                    PotionEffectType.LUCK, Integer.MAX_VALUE, 0, true, false, true));
            // Les effets suivants sont gérés via des checks dans les autres modules
            case DAMAGE_REDUCTION_10, DAMAGE_REDUCTION_25,
                 REPAIR_DISCOUNT, SHOP_DISCOUNT, QUEST_XP_BONUS, JOB_MONEY_BONUS -> {
                // Appliqués via listeners/checks dans les modules respectifs
            }
        }
    }

    private void removeAllEffects(Player player) {
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.WATER_BREATHING);
        player.removePotionEffect(PotionEffectType.LUCK);

        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            NamespacedKey vitalityKey = new NamespacedKey(plugin, "vitality");
            // Copier dans une liste pour éviter ConcurrentModificationException
            attr.getModifiers().stream()
                    .filter(m -> m.getKey().equals(vitalityKey))
                    .collect(java.util.stream.Collectors.toList())
                    .forEach(attr::removeModifier);
        }
    }

    // ─── Damage Reduction Listener ──────────────────────────────

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        if (hasSkill(uuid, "resistance_2")) {
            event.setDamage(event.getDamage() * 0.75); // -25%
        } else if (hasSkill(uuid, "resistance_1")) {
            event.setDamage(event.getDamage() * 0.90); // -10%
        }
    }

    // ─── Repair Discount Listener ─────────────────────────────

    @EventHandler
    public void onPrepareAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!hasSkill(player.getUniqueId(), "smith")) return;
        if (event.getResult() == null) return;

        // Réduire le coût de réparation de 20%
        int originalCost = event.getView().getRepairCost();
        if (originalCost > 0) {
            int discountedCost = Math.max(1, (int) (originalCost * 0.80));
            event.getView().setRepairCost(discountedCost);
        }
    }

    // ─── Join/Quit ──────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loadPlayerSkills(uuid);
        // Appliquer les effets après un court délai (laisser le temps au chargement)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                applyAllEffects(event.getPlayer());
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        removeAllEffects(event.getPlayer());
        playerSkills.remove(uuid);
        availablePoints.remove(uuid);
        playerGeneralXp.remove(uuid);
    }

    // ─── Persistance ────────────────────────────────────────────

    private void loadPlayerSkills(UUID uuid) {
        // Charger les compétences
        plugin.getDatabaseManager().executeAsync(conn -> {
            Set<String> skills = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT skill_id FROM skills WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    skills.add(rs.getString("skill_id"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement skills: " + e.getMessage());
            }
            return skills;
        }).thenAccept(skills -> playerSkills.put(uuid, skills));

        // Charger l'XP et calculer les points
        refreshGeneralXp(uuid);
    }

    /** Called by other modules when general_xp changes. */
    public void refreshPlayerPoints(UUID uuid) {
        refreshGeneralXp(uuid);
    }

    private void refreshGeneralXp(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT general_xp, skill_points FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    int generalXp = rs.getInt("general_xp");
                    int spentPoints = getTotalSpentPoints(uuid);
                    int totalPoints = generalXp / XP_PER_POINT;
                    int available = totalPoints - spentPoints;
                    return Math.max(0, available);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur refresh XP: " + e.getMessage());
            }
            return 0;
        }).thenAccept(points -> availablePoints.put(uuid, points));
    }

    private int getTotalSpentPoints(UUID uuid) {
        Set<String> owned = playerSkills.getOrDefault(uuid, new HashSet<>());
        int total = 0;
        for (SkillNode node : skillNodes) {
            if (owned.contains(node.id)) {
                total += node.cost;
            }
        }
        return total;
    }

    // ─── Data ───────────────────────────────────────────────────

    public enum SkillEffect {
        DAMAGE_REDUCTION_10, DAMAGE_REDUCTION_25, EXTRA_HEARTS, LOOT_BONUS,
        SPEED, WATER_BREATHING, REPAIR_DISCOUNT, SHOP_DISCOUNT,
        QUEST_XP_BONUS, JOB_MONEY_BONUS
    }

    public static class SkillNode {
        public final String id, displayName, description;
        public final int cost, slot;
        public final Material icon;
        public final String requires; // null = pas de prérequis
        public final SkillEffect effect;

        public SkillNode(String id, String displayName, int cost, int slot, Material icon,
                         String requires, String description, SkillEffect effect) {
            this.id = id; this.displayName = displayName; this.cost = cost;
            this.slot = slot; this.icon = icon; this.requires = requires;
            this.description = description; this.effect = effect;
        }
    }
}
