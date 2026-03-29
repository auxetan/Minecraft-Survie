package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Shop — Shop admin (GUI catégories), marché joueur, coffre commun.
 * Utilise TriumphGUI pour tous les menus.
 */
public class ShopModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private YamlConfiguration shopConfig;

    // Catégories du shop admin
    private final List<ShopCategory> categories = new ArrayList<>();
    // Stock légendaires : item_name → remaining stock
    private final Map<String, Integer> legendaryStock = new ConcurrentHashMap<>();
    // Fonds commun
    private double commonFund = 0.0;
    // Track des contributions joueur (pour restriction retrait coffre)
    private final Map<UUID, Long> lastContribution = new ConcurrentHashMap<>();
    // Market tax
    private double marketTax;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        loadShopConfig();

        // Charger le fonds commun depuis la DB
        loadCommonFund();

        // Commandes
        plugin.getCommand("shop").setExecutor(new ShopCommand());
        plugin.getCommand("vendre").setExecutor(new SellCommand());
        plugin.getCommand("marche").setExecutor(new MarketCommand());
        plugin.getCommand("coffre-commun").setExecutor(new CommonChestCommand());

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Reset stock légendaires chaque lundi
        scheduleLegendaryReset();

        plugin.getLogger().info("Shop module enabled — " + categories.size() + " catégories.");
    }

    @Override
    public void onDisable() {
        // Persister le fonds commun dans config.yml
        plugin.getConfig().set("common-fund-balance", commonFund);
        plugin.saveConfig();
        plugin.getLogger().info("Shop module disabled. Fonds commun sauvegardé : " + commonFund);
    }

    @Override
    public String getName() {
        return "Shop";
    }

    // ─── Config ─────────────────────────────────────────────────

    private void loadShopConfig() {
        File f = new File(plugin.getDataFolder(), "data/shop.yml");
        if (!f.exists()) plugin.saveResource("data/shop.yml", false);
        shopConfig = YamlConfiguration.loadConfiguration(f);

        marketTax = shopConfig.getDouble("market-tax", 0.05);

        ConfigurationSection catSec = shopConfig.getConfigurationSection("categories");
        if (catSec == null) return;

        for (String catKey : catSec.getKeys(false)) {
            ConfigurationSection cat = catSec.getConfigurationSection(catKey);
            if (cat == null) continue;

            String displayName = cat.getString("display-name", catKey);
            Material icon = Material.valueOf(cat.getString("icon", "STONE"));
            int slot = cat.getInt("slot", 0);
            boolean isLegendary = cat.contains("weekly-stock");

            List<ShopItem> items = new ArrayList<>();
            ConfigurationSection itemsSec = cat.getConfigurationSection("items");
            if (itemsSec != null) {
                for (String itemKey : itemsSec.getKeys(false)) {
                    ConfigurationSection item = itemsSec.getConfigurationSection(itemKey);
                    if (item == null) continue;
                    Material mat = Material.valueOf(itemKey);
                    double buyPrice = item.getDouble("buy", 0);
                    double sellPrice = item.getDouble("sell", 0);
                    int stock = item.getInt("stock", -1); // -1 = illimité
                    items.add(new ShopItem(mat, buyPrice, sellPrice, stock));

                    if (stock > 0 && isLegendary) {
                        legendaryStock.put(itemKey, stock);
                    }
                }
            }

            categories.add(new ShopCategory(catKey, displayName, icon, slot, items, isLegendary));
        }
    }

    public double getCommonFund() {
        return commonFund;
    }

    /** Appelé par EconomyModule à chaque gain d'argent pour alimenter le fonds commun. */
    public void addToCommonFund(double amount) {
        double taxRate = plugin.getConfig().getDouble("common-fund-rate", 0.05);
        double contribution = amount * taxRate;
        commonFund += contribution;
    }

    /** Enregistre une contribution pour débloquer le retrait du coffre. */
    public void recordContribution(UUID uuid) {
        lastContribution.put(uuid, System.currentTimeMillis());
    }

    // ─── Shop Admin GUI ─────────────────────────────────────────

    public void openShopMain(Player player) {
        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §6Shop §8✦"))
                .rows(4)
                .disableAllInteractions()
                .create();

        // Fond en verre noir
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" "))
                .asGuiItem();
        gui.getFiller().fill(filler);

        // Ajouter chaque catégorie
        for (ShopCategory cat : categories) {
            GuiItem catItem = ItemBuilder.from(cat.icon)
                    .name(Component.text(cat.displayName))
                    .lore(Component.text("§7Clic pour ouvrir"))
                    .asGuiItem(event -> {
                        openCategoryShop(player, cat);
                    });
            gui.setItem(cat.slot, catItem);
        }

        gui.open(player);
    }

    private void openCategoryShop(Player player, ShopCategory category) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        PaginatedGui gui = Gui.paginated()
                .title(Component.text("§8✦ §6" + category.displayName + " §8✦"))
                .rows(6)
                .pageSize(36)
                .disableAllInteractions()
                .create();

        // Fond
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" "))
                .asGuiItem();
        gui.getFiller().fillBorder(filler);

        for (ShopItem shopItem : category.items) {
            // Vérifier stock légendaire
            if (category.isLegendary) {
                int remaining = legendaryStock.getOrDefault(shopItem.material.name(), 0);
                if (remaining <= 0) continue;
            }

            List<Component> lore = new ArrayList<>();
            if (shopItem.buyPrice > 0) lore.add(Component.text("§7Acheter : §6" + shopItem.buyPrice + " ✦"));
            if (shopItem.sellPrice > 0) lore.add(Component.text("§7Vendre : §e" + shopItem.sellPrice + " ✦"));
            if (shopItem.stock > 0 && category.isLegendary) {
                int remaining = legendaryStock.getOrDefault(shopItem.material.name(), 0);
                lore.add(Component.text("§cStock : " + remaining + " restant(s)"));
            }
            lore.add(Component.text(""));
            lore.add(Component.text("§aClic gauche → Acheter"));
            if (shopItem.sellPrice > 0) lore.add(Component.text("§eClic droit → Vendre"));

            GuiItem guiItem = ItemBuilder.from(shopItem.material)
                    .name(Component.text("§f" + formatItemName(shopItem.material.name())))
                    .lore(lore)
                    .asGuiItem(event -> {
                        if (event.isLeftClick()) {
                            buyItem(player, shopItem, category);
                        } else if (event.isRightClick() && shopItem.sellPrice > 0) {
                            sellItem(player, shopItem);
                        }
                    });
            gui.addItem(guiItem);
        }

        // Navigation
        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Page précédente"))
                .asGuiItem(event -> gui.previous()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7Page suivante →"))
                .asGuiItem(event -> gui.next()));
        gui.setItem(6, 5, ItemBuilder.from(Material.BARRIER)
                .name(Component.text("§c← Retour"))
                .asGuiItem(event -> openShopMain(player)));

        gui.open(player);
    }

    private void buyItem(Player player, ShopItem item, ShopCategory category) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        if (item.buyPrice <= 0) {
            player.sendMessage("§cCet item n'est pas à vendre.");
            return;
        }

        // Stock légendaire
        if (category.isLegendary) {
            int remaining = legendaryStock.getOrDefault(item.material.name(), 0);
            if (remaining <= 0) {
                player.sendMessage("§cStock épuisé pour cet item !");
                return;
            }
        }

        // Compétence Marchand : -10% sur le prix d'achat
        double finalPrice = item.buyPrice;
        SkillModule skill = plugin.getModule(SkillModule.class);
        if (skill != null && skill.hasSkill(player.getUniqueId(), "merchant")) {
            finalPrice *= 0.90;
        }

        if (!eco.withdraw(player.getUniqueId(), finalPrice)) {
            player.sendMessage("§cFonds insuffisants ! Il te faut §6" + eco.formatMoney(finalPrice));
            return;
        }

        // Donner l'item
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(item.material));
        if (!overflow.isEmpty()) {
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            player.sendMessage("§7(Item droppé au sol — inventaire plein)");
        }

        // Décrémenter stock légendaire
        if (category.isLegendary) {
            legendaryStock.merge(item.material.name(), -1, Integer::sum);
        }

        String msg = "§a✦ Acheté §f" + formatItemName(item.material.name()) + " §apour §6" + eco.formatMoney(finalPrice);
        if (finalPrice < item.buyPrice) msg += " §a(§e-10% Marchand§a)";
        player.sendMessage(msg);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
    }

    private void sellItem(Player player, ShopItem item) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        if (item.sellPrice <= 0) {
            player.sendMessage("§cCet item ne peut pas être vendu ici.");
            return;
        }

        // Vérifier que le joueur a l'item
        if (!player.getInventory().containsAtLeast(new ItemStack(item.material), 1)) {
            player.sendMessage("§cTu n'as pas cet item dans ton inventaire.");
            return;
        }

        // Retirer 1 item
        player.getInventory().removeItem(new ItemStack(item.material, 1));
        eco.deposit(player.getUniqueId(), item.sellPrice);

        player.sendMessage("§e✦ Vendu §f" + formatItemName(item.material.name()) + " §epour §6" + eco.formatMoney(item.sellPrice));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f);
    }

    // ─── Marché Joueur ──────────────────────────────────────────

    public void openMarket(Player player) {
        // Charger les listings depuis la DB
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<MarketListing> listings = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, item_serialized, price FROM market ORDER BY listed_at DESC")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    listings.add(new MarketListing(
                            rs.getInt("id"),
                            rs.getString("seller_uuid"),
                            rs.getString("item_serialized"),
                            rs.getDouble("price")
                    ));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement marché: " + e.getMessage());
            }
            return listings;
        }).thenAccept(listings -> {
            Bukkit.getScheduler().runTask(plugin, () -> openMarketGui(player, listings));
        });
    }

    private void openMarketGui(Player player, List<MarketListing> listings) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);

        PaginatedGui gui = Gui.paginated()
                .title(Component.text("§8✦ §eMarché Joueur §8✦"))
                .rows(6)
                .pageSize(36)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fillBorder(filler);

        for (MarketListing listing : listings) {
            try {
                Material mat = Material.valueOf(listing.itemSerialized);
                String sellerName = Bukkit.getOfflinePlayer(UUID.fromString(listing.sellerUuid)).getName();
                if (sellerName == null) sellerName = "Inconnu";

                String finalSellerName = sellerName;
                GuiItem item = ItemBuilder.from(mat)
                        .name(Component.text("§f" + formatItemName(mat.name())))
                        .lore(
                                Component.text("§7Vendeur : §e" + finalSellerName),
                                Component.text("§7Prix : §6" + listing.price + " ✦"),
                                Component.text("§7Taxe : §c" + (int)(marketTax * 100) + "%"),
                                Component.text(""),
                                Component.text("§aClic pour acheter")
                        )
                        .asGuiItem(event -> {
                            buyFromMarket(player, listing, mat);
                        });
                gui.addItem(item);
            } catch (Exception ignored) {
                // Skip invalid entries
            }
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Page précédente")).asGuiItem(e -> gui.previous()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7Page suivante →")).asGuiItem(e -> gui.next()));

        gui.open(player);
    }

    private void buyFromMarket(Player player, MarketListing listing, Material mat) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        if (!eco.withdraw(player.getUniqueId(), listing.price)) {
            player.sendMessage("§cFonds insuffisants !");
            return;
        }

        // Donner l'item
        player.getInventory().addItem(new ItemStack(mat));

        // Taxe : 5% va au fonds commun, le reste au vendeur
        double tax = listing.price * marketTax;
        double sellerAmount = listing.price - tax;
        commonFund += tax;

        UUID sellerUuid = UUID.fromString(listing.sellerUuid);
        eco.deposit(sellerUuid, sellerAmount);

        // Supprimer le listing
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM market WHERE id = ?")) {
                ps.setInt(1, listing.id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur suppression listing: " + e.getMessage());
            }
        });

        player.sendMessage("§a✦ Acheté sur le marché pour §6" + eco.formatMoney(listing.price));
        player.closeInventory();

        // Notifier le vendeur si en ligne
        Player seller = Bukkit.getPlayer(sellerUuid);
        if (seller != null) {
            seller.sendMessage("§e✦ §f" + player.getName() + " §ea acheté ton item pour §6" + eco.formatMoney(sellerAmount) + " §e(après taxe)");
        }
    }

    // ─── Coffre Commun ──────────────────────────────────────────

    public void openCommonChest(Player player) {
        UUID uuid = player.getUniqueId();

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §aCoffre Commun §8✦ §7Fonds: §6" + String.format("%.0f", commonFund) + " ✦"))
                .rows(4)
                .create();

        // Note: le coffre commun est un inventaire partagé simple
        // On ne bloque que le retrait si le joueur n'a pas contribué dans les 24h
        // Pour simplifier, on utilise un GUI TriumphGUI avec des restrictions

        GuiItem info = ItemBuilder.from(Material.GOLD_BLOCK)
                .name(Component.text("§6§l✦ Fonds Commun"))
                .lore(
                        Component.text("§7Total : §6" + String.format("%.0f", commonFund) + " ✦"),
                        Component.text("§7Alimenté par 5% de chaque gain"),
                        Component.text(""),
                        Component.text("§eRègle : tu dois avoir contribué"),
                        Component.text("§edans les 24h pour retirer des items")
                )
                .asGuiItem();
        gui.setItem(4, info);

        // Vérification contribution 24h
        Long lastContrib = lastContribution.get(uuid);
        boolean canWithdraw = lastContrib != null
                && (System.currentTimeMillis() - lastContrib) < (24 * 60 * 60 * 1000);

        GuiItem statusItem;
        if (canWithdraw) {
            statusItem = ItemBuilder.from(Material.LIME_DYE)
                    .name(Component.text("§a✓ Tu peux déposer et retirer"))
                    .asGuiItem();
        } else {
            statusItem = ItemBuilder.from(Material.RED_DYE)
                    .name(Component.text("§c✗ Retrait bloqué"))
                    .lore(Component.text("§7Complète un job ou une quête pour débloquer"))
                    .asGuiItem();
        }
        gui.setItem(22, statusItem);

        gui.open(player);
    }

    // ─── Reset Légendaire hebdo ─────────────────────────────────

    private void scheduleLegendaryReset() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMonday = now.toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atTime(LocalTime.MIDNIGHT);
        long seconds = ChronoUnit.SECONDS.between(now, nextMonday);

        new BukkitRunnable() {
            @Override
            public void run() {
                resetLegendaryStock();
                scheduleLegendaryReset();
            }
        }.runTaskLater(plugin, Math.max(20L, seconds * 20L));
    }

    private void resetLegendaryStock() {
        // Recharger le stock depuis la config
        for (ShopCategory cat : categories) {
            if (!cat.isLegendary) continue;
            for (ShopItem item : cat.items) {
                if (item.stock > 0) {
                    legendaryStock.put(item.material.name(), item.stock);
                }
            }
        }
        plugin.getLogger().info("Stock légendaire réinitialisé.");
    }

    // ─── Common Fund persistence ────────────────────────────────

    private void loadCommonFund() {
        // On stocke le fonds commun dans la config pour simplicité
        commonFund = plugin.getConfig().getDouble("common-fund-balance", 0.0);
    }

    // ─── Utilitaires ────────────────────────────────────────────

    private String formatItemName(String materialName) {
        String[] parts = materialName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ─── Commandes ──────────────────────────────────────────────

    private class ShopCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            openShopMain(player);
            return true;
        }
    }

    private class SellCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            if (args.length < 1) {
                player.sendMessage("§cUsage : /vendre <prix>");
                return true;
            }
            double price;
            try {
                price = Double.parseDouble(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage("§cPrix invalide.");
                return true;
            }
            if (price <= 0) {
                player.sendMessage("§cLe prix doit être positif.");
                return true;
            }

            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand.getType() == Material.AIR) {
                player.sendMessage("§cTu dois tenir un item en main.");
                return true;
            }

            // Sérialiser comme nom du matériau (simplifié)
            String serialized = itemInHand.getType().name();

            // Retirer 1 item de la main
            if (itemInHand.getAmount() > 1) {
                itemInHand.setAmount(itemInHand.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            // Insérer dans le marché
            plugin.getDatabaseManager().runAsync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO market (seller_uuid, item_serialized, price, listed_at) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, player.getUniqueId().toString());
                    ps.setString(2, serialized);
                    ps.setDouble(3, price);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("Erreur listing marché: " + e.getMessage());
                }
            });

            player.sendMessage("§e✦ Item mis en vente pour §6" + price + " ✦ §e(taxe " + (int)(marketTax * 100) + "% à la vente)");
            return true;
        }
    }

    private class MarketCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            openMarket(player);
            return true;
        }
    }

    private class CommonChestCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            openCommonChest(player);
            return true;
        }
    }

    // ─── Data classes ───────────────────────────────────────────

    public record ShopCategory(String id, String displayName, Material icon, int slot,
                                List<ShopItem> items, boolean isLegendary) {}
    public record ShopItem(Material material, double buyPrice, double sellPrice, int stock) {}
    public record MarketListing(int id, String sellerUuid, String itemSerialized, double price) {}
}
