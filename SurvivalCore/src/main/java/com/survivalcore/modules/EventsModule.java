package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventsModule — Starter Kit au premier join + Système de streak quotidien.
 *
 * Starter kit : donné une seule fois au joueur (flag en DB).
 * Streak : chaque jour où un joueur complète au moins 1 quête, son streak +1.
 *   - Streak jour 3 : +100 ✦ bonus
 *   - Streak jour 7 : +350 ✦ + title broadcast
 *   - Streak jour 30 : +1500 ✦ + broadcast serveur
 *   La streak est brisée si le joueur n'a pas complété de quête le jour précédent.
 */
public class EventsModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // Cache streak : UUID → (streakCount, lastQuestDate)
    private final ConcurrentHashMap<UUID, StreakData> streakCache = new ConcurrentHashMap<>();

    // ─── CoreModule ──────────────────────────────────────────────

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("EventsModule enabled — starter kit + streak system actif.");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("EventsModule disabled.");
    }

    @Override
    public String getName() { return "Events"; }

    // ─── Starter Kit ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!player.hasPlayedBefore()) {
            // Différer de 2 secondes pour laisser le temps à l'inventaire de se charger
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) giveStarterKit(player);
            }, 40L);
            return;
        }

        // Vérifier le flag en DB (au cas où hasPlayedBefore n'est pas fiable sur certains serveurs)
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT starter_kit_given FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt("starter_kit_given") == 0;
            } catch (SQLException e) {
                plugin.getLogger().warning("Events: erreur check starter kit: " + e.getMessage());
            }
            return false;
        }).thenAccept(shouldGive -> {
            if (shouldGive) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) giveStarterKit(player);
                });
            } else {
                // Charger le streak en cache
                loadStreak(uuid);
            }
        });
    }

    private void giveStarterKit(Player player) {
        UUID uuid = player.getUniqueId();

        // Items du kit de départ
        ItemStack ironSword = new ItemStack(Material.IRON_SWORD);
        ItemStack ironPickaxe = new ItemStack(Material.IRON_PICKAXE);
        ItemStack ironAxe = new ItemStack(Material.IRON_AXE);
        ItemStack ironShovel = new ItemStack(Material.IRON_SHOVEL);
        ItemStack bread = new ItemStack(Material.BREAD, 32);
        ItemStack torches = new ItemStack(Material.TORCH, 32);
        ItemStack cobblestone = new ItemStack(Material.COBBLESTONE, 64);
        ItemStack craftingTable = new ItemStack(Material.CRAFTING_TABLE, 1);

        // Légère enchantement pour le sentiment d'accueil
        ironSword.addEnchantment(Enchantment.SHARPNESS, 1);
        ironPickaxe.addEnchantment(Enchantment.EFFICIENCY, 1);

        // Noms personnalisés
        setName(ironSword, "§6✦ Épée de Survie §7(Starter Kit)");
        setName(ironPickaxe, "§6✦ Pioche de Survie §7(Starter Kit)");

        var overflow = player.getInventory().addItem(
                ironSword, ironPickaxe, ironAxe, ironShovel,
                bread, torches, cobblestone, craftingTable);
        // Drop au sol si inventaire plein (ne devrait pas arriver au 1er join)
        overflow.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

        // Argent de départ
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco != null) eco.deposit(uuid, 500);

        // Messages et sons
        player.sendMessage("");
        player.sendMessage("§6§l  ✦ Bienvenue sur SurvivalCraft ! ✦");
        player.sendMessage("§7  Voici ton §eKit de Démarrage §7:");
        player.sendMessage("  §a• §fÉpée de Fer §8(Tranchant I)");
        player.sendMessage("  §a• §fPioche de Fer §8(Efficacité I)");
        player.sendMessage("  §a• §fHache & Pelle en Fer");
        player.sendMessage("  §a• §f32 Pains, 32 Torches, 64 Cobblestones");
        player.sendMessage("  §a• §f500 ✦ de départ");
        player.sendMessage("");
        player.sendMessage("§7  Tape §e/menu §7pour découvrir toutes les fonctionnalités !");
        player.sendMessage("§7  Tape §e/commandes §7pour voir toutes les commandes disponibles.");
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 0.9f);

        // Broadcast aux autres joueurs
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.sendMessage("§e✦ §f" + player.getName() + " §7rejoint l'aventure pour la première fois !");
            }
        }

        // Sauvegarder le flag en DB
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET starter_kit_given = 1 WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Events: erreur save starter kit flag: " + e.getMessage());
            }
        });

        // Charger le streak après le kit
        loadStreak(uuid);
    }

    private void setName(ItemStack item, String name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(name));
            item.setItemMeta(meta);
        }
    }

    // ─── Streak System ───────────────────────────────────────────

    /** Appelé par QuestModule à chaque complétion de quête. */
    public void onQuestCompleted(UUID uuid) {
        String today = LocalDate.now().toString();
        StreakData streak = streakCache.get(uuid);
        if (streak == null) {
            // Charger depuis DB puis appliquer
            plugin.getDatabaseManager().executeAsync(conn -> loadStreakFromDb(conn, uuid))
                .thenAccept(loaded -> {
                    streakCache.put(uuid, loaded);
                    applyDailyQuestStreak(uuid, today, loaded);
                });
            return;
        }
        applyDailyQuestStreak(uuid, today, streak);
    }

    private void applyDailyQuestStreak(UUID uuid, String today, StreakData streak) {
        // Déjà compté aujourd'hui
        if (today.equals(streak.lastQuestDate)) return;

        String yesterday = LocalDate.now().minusDays(1).toString();
        boolean continued = yesterday.equals(streak.lastQuestDate);

        streak.lastQuestDate = today;
        streak.streakCount = continued ? streak.streakCount + 1 : 1;

        // Récompenses de streak
        int bonus = 0;
        String title = null;
        if (streak.streakCount == 3) {
            bonus = 100;
            title = "§a3 jours de suite !";
        } else if (streak.streakCount == 7) {
            bonus = 350;
            title = "§6✦ 7 jours de suite ! ✦";
        } else if (streak.streakCount == 14) {
            bonus = 700;
            title = "§b✦ 14 jours de suite ! ✦";
        } else if (streak.streakCount == 30) {
            bonus = 1500;
            title = "§d§l✦✦ 30 JOURS DE SUITE !! ✦✦";
        }

        final int finalBonus = bonus;
        final String finalTitle = title;
        final int finalCount = streak.streakCount;

        // Sauvegarder en DB
        saveStreak(uuid, streak);

        // Notifier le joueur sur le thread principal
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;

            player.sendMessage("§a✦ Streak quête : §e" + finalCount + " jour"
                    + (finalCount > 1 ? "s" : "") + " consécutif" + (finalCount > 1 ? "s" : "") + " !");

            if (finalBonus > 0) {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                if (eco != null) eco.deposit(uuid, finalBonus);
                player.sendMessage("§6§l  + " + finalBonus + " ✦ §6Bonus Streak " + finalTitle);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);

                if (finalCount >= 7) {
                    for (Player other : Bukkit.getOnlinePlayers()) {
                        other.sendMessage("§6✦ §f" + player.getName()
                                + " §7a maintenu une streak de §e" + finalCount + " jours §7de quêtes ! "
                                + finalTitle);
                    }
                }
            }
        });
    }

    private void loadStreak(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> loadStreakFromDb(conn, uuid))
            .thenAccept(data -> streakCache.put(uuid, data));
    }

    private StreakData loadStreakFromDb(java.sql.Connection conn, UUID uuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT quest_streak, last_quest_date FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new StreakData(rs.getInt("quest_streak"),
                        rs.getString("last_quest_date"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Events: erreur load streak: " + e.getMessage());
        }
        return new StreakData(0, null);
    }

    private void saveStreak(UUID uuid, StreakData streak) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET quest_streak = ?, last_quest_date = ? WHERE uuid = ?")) {
                ps.setInt(1, streak.streakCount);
                ps.setString(2, streak.lastQuestDate);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Events: erreur save streak: " + e.getMessage());
            }
        });
    }

    // ─── Data classes ─────────────────────────────────────────────

    private static class StreakData {
        int streakCount;
        String lastQuestDate; // null or "2026-03-31"

        StreakData(int count, String date) {
            this.streakCount = count;
            this.lastQuestDate = date;
        }
    }
}
