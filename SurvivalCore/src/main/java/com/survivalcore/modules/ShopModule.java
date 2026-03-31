package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import com.survivalcore.ui.GuiBackground;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
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
    // Coffre commun partagé (inventaire réel Bukkit)
    private Inventory commonChestInventory;
    // Dynamic pricing — ventes quotidiennes par item : material_name → volume
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> dailySellVolume = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        loadShopConfig();

        // Charger le fonds commun depuis la DB
        loadCommonFund();

        // Créer et charger le coffre commun partagé
        commonChestInventory = Bukkit.createInventory(null, 27, "§8✦ §aCoffre Commun §8✦");
        loadCommonChestContents();

        // Commandes
        plugin.getCommand("shop").setExecutor(new ShopCommand());
        plugin.getCommand("vendre").setExecutor(new SellCommand());
        plugin.getCommand("marche").setExecutor(new MarketCommand());
        plugin.getCommand("coffre-commun").setExecutor(new CommonChestCommand());

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Reset stock légendaires chaque lundi
        scheduleLegendaryReset();

        // Reset prix dynamiques chaque minuit
        scheduleDailyVolumeReset();

        plugin.getLogger().info("Shop module enabled — " + categories.size() + " catégories.");
    }

    @Override
    public void onDisable() {
        // Persister le fonds commun dans config.yml
        plugin.getConfig().set("common-fund-balance", commonFund);
        plugin.saveConfig();
        // Persister le coffre commun en DB
        saveCommonChestContentsSync();
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
                .title(GuiBackground.SHOP.title("§8✦ §6Shop §8✦"))
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
                .title(GuiBackground.SHOP.title("§8✦ §6" + category.displayName + " §8✦"))
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
            if (shopItem.sellPrice > 0) {
                double effectiveSell = getEffectiveSellPrice(shopItem);
                if (effectiveSell < shopItem.sellPrice) {
                    lore.add(Component.text("§7Vendre : §e" + String.format("%.2f", effectiveSell)
                            + " ✦ §8(base " + shopItem.sellPrice + ")"));
                } else {
                    lore.add(Component.text("§7Vendre : §e" + shopItem.sellPrice + " ✦"));
                }
            }
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

        // Prix dynamique : chaque vente réduit le prix de 2%, plancher à 50%
        double effectivePrice = getEffectiveSellPrice(item);

        // Incrémenter le volume quotidien
        dailySellVolume.computeIfAbsent(item.material.name(),
                k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();

        // Retirer 1 item
        player.getInventory().removeItem(new ItemStack(item.material, 1));
        eco.depositFromEarning(player.getUniqueId(), effectivePrice);

        String msg = "§e✦ Vendu §f" + formatItemName(item.material.name()) + " §epour §6" + eco.formatMoney(effectivePrice);
        if (effectivePrice < item.sellPrice) {
            msg += " §8(prix réduit — marché saturé)";
        }
        player.sendMessage(msg);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f);
    }

    /**
     * Calcule le prix de vente effectif en tenant compte du volume quotidien.
     * Formule : basePrice * max(0.5, 1.0 - volume * 0.02)
     */
    private double getEffectiveSellPrice(ShopItem item) {
        int volume = dailySellVolume.getOrDefault(item.material.name(),
                new java.util.concurrent.atomic.AtomicInteger(0)).get();
        double factor = Math.max(0.5, 1.0 - volume * 0.02);
        return Math.round(item.sellPrice * factor * 100.0) / 100.0;
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
                ItemStack displayItem = ItemStack.deserializeBytes(Base64.getDecoder().decode(listing.itemSerialized));
                String sellerName = Bukkit.getOfflinePlayer(UUID.fromString(listing.sellerUuid)).getName();
                if (sellerName == null) sellerName = "Inconnu";

                String finalSellerName = sellerName;
                ItemStack finalDisplayItem = displayItem;
                GuiItem item = ItemBuilder.from(displayItem)
                        .lore(
                                Component.text("§7Vendeur : §e" + finalSellerName),
                                Component.text("§7Prix : §6" + listing.price + " ✦"),
                                Component.text("§7Taxe : §c" + (int)(marketTax * 100) + "%"),
                                Component.text(""),
                                Component.text("§aClic pour acheter")
                        )
                        .asGuiItem(event -> {
                            buyFromMarket(player, listing, finalDisplayItem);
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

    private void buyFromMarket(Player player, MarketListing listing, ItemStack itemToGive) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        if (!eco.withdraw(player.getUniqueId(), listing.price)) {
            player.sendMessage("§cFonds insuffisants !");
            return;
        }

        // Donner l'item avec tous ses enchantements et données NBT
        ItemStack toGive = itemToGive.clone();
        toGive.setAmount(1);
        player.getInventory().addItem(toGive);

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
        player.openInventory(commonChestInventory);
    }

    private boolean canWithdrawFromChest(UUID uuid) {
        Long lastContrib = lastContribution.get(uuid);
        return lastContrib != null && (System.currentTimeMillis() - lastContrib) < (24L * 60 * 60 * 1000);
    }

    @EventHandler
    public void onCommonChestClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isCommonChestInventory(event.getInventory()) && !isCommonChestInventory(event.getClickedInventory())) return;

        UUID uuid = player.getUniqueId();

        // Dépôt toujours autorisé (click dans l'inventaire du joueur vers le coffre)
        // Retrait bloqué si pas contribué dans les 24h
        boolean clickInChest = isCommonChestInventory(event.getClickedInventory());
        boolean isShiftFromPlayer = event.isShiftClick() && !clickInChest;

        if (clickInChest || isShiftFromPlayer) {
            // Déterminer si c'est un retrait ou un dépôt
            boolean isWithdraw;
            if (isShiftFromPlayer) {
                // Shift-click depuis l'inventaire joueur = dépôt → toujours autorisé
                isWithdraw = false;
            } else {
                // Click dans le coffre : si le joueur prend un item (cursor vide ou pickup), c'est un retrait
                org.bukkit.event.inventory.ClickType clickType = event.getClick();
                isWithdraw = event.getCurrentItem() != null
                        && event.getCurrentItem().getType() != Material.AIR
                        && (clickType == org.bukkit.event.inventory.ClickType.LEFT
                                || clickType == org.bukkit.event.inventory.ClickType.RIGHT
                                || clickType == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                                || clickType == org.bukkit.event.inventory.ClickType.DROP
                                || clickType == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                                || clickType == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT);
            }

            if (isWithdraw && !canWithdrawFromChest(uuid)) {
                event.setCancelled(true);
                player.sendMessage("§c✗ Retrait bloqué ! Tu dois avoir contribué (job/quête) dans les 24h.");
            }
        }

        // Sauvegarder le contenu après chaque interaction avec le coffre
        if (!event.isCancelled()) {
            Bukkit.getScheduler().runTask(plugin, this::saveCommonChestContentsAsync);
        }
    }

    @EventHandler
    public void onCommonChestClose(InventoryCloseEvent event) {
        if (isCommonChestInventory(event.getInventory())) {
            saveCommonChestContentsAsync();
        }
    }

    private boolean isCommonChestInventory(Inventory inv) {
        return inv != null && inv.equals(commonChestInventory);
    }

    private void loadCommonChestContents() {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT contents FROM common_chest WHERE id = 1")) {
                java.sql.ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String encoded = rs.getString("contents");
                    if (encoded != null && !encoded.isEmpty()) {
                        return encoded;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur chargement coffre commun: " + e.getMessage());
            }
            return null;
        }).thenAccept(encoded -> {
            if (encoded == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    String[] parts = encoded.split(";", -1);
                    for (int i = 0; i < parts.length && i < commonChestInventory.getSize(); i++) {
                        if (!parts[i].isEmpty()) {
                            byte[] bytes = Base64.getDecoder().decode(parts[i]);
                            commonChestInventory.setItem(i, ItemStack.deserializeBytes(bytes));
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Erreur désérialisation coffre commun: " + e.getMessage());
                }
            });
        });
    }

    private String serializeChestContents() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonChestInventory.getSize(); i++) {
            if (i > 0) sb.append(";");
            ItemStack item = commonChestInventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                sb.append(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
        }
        return sb.toString();
    }

    private void saveCommonChestContentsAsync() {
        String encoded = serializeChestContents();
        plugin.getDatabaseManager().runAsync(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO common_chest (id, contents) VALUES (1, ?)")) {
                ps.setString(1, encoded);
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur sauvegarde coffre commun: " + e.getMessage());
            }
        });
    }

    private void saveCommonChestContentsSync() {
        String encoded = serializeChestContents();
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO common_chest (id, contents) VALUES (1, ?)")) {
                    ps.setString(1, encoded);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur sauvegarde sync coffre commun: " + e.getMessage());
        }
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

    // ─── Dynamic pricing reset ──────────────────────────────────

    private void scheduleDailyVolumeReset() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT);
        long seconds = ChronoUnit.SECONDS.between(now, midnight);

        new BukkitRunnable() {
            @Override
            public void run() {
                dailySellVolume.clear();
                plugin.getLogger().fine("Shop: volumes de vente quotidiens réinitialisés.");
                scheduleDailyVolumeReset();
            }
        }.runTaskLater(plugin, Math.max(20L, seconds * 20L));
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

            // Sérialiser l'item complet (enchantements, nom, NBT) en Base64
            String serialized = Base64.getEncoder().encodeToString(itemInHand.serializeAsBytes());

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
