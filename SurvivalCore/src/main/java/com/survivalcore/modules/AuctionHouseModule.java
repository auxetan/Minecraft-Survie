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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Module Hôtel des Enchères — système d'enchères joueur-vers-joueur.
 * Toutes les opérations DB sont asynchrones (jamais de blocage du thread principal).
 */
public class AuctionHouseModule implements CoreModule {

    private static final int    MAX_AUCTIONS_PER_PLAYER = 5;
    private static final int    MAX_DURATION_HOURS      = 72;
    private static final int    DEFAULT_DURATION_HOURS  = 24;
    private static final double MIN_START_PRICE         = 1.0;
    private static final double SELLER_TAX              = 0.05;   // 5 % tax on sale
    private static final double MIN_BID_INCREMENT_PCT   = 0.10;   // +10 % minimum raise

    private SurvivalCore plugin;
    private BukkitTask   expiryTask;

    // ─── CoreModule ──────────────────────────────────────────────

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;

        plugin.getCommand("ah").setExecutor(new AhCommand());

        // Check for expired auctions every 60 s
        expiryTask = new BukkitRunnable() {
            @Override public void run() { processExpiredAuctions(); }
        }.runTaskTimerAsynchronously(plugin, 20L * 60, 20L * 60);

        plugin.getLogger().info("AuctionHouse module enabled.");
    }

    @Override
    public void onDisable() {
        if (expiryTask != null && !expiryTask.isCancelled()) expiryTask.cancel();
        plugin.getLogger().info("AuctionHouse module disabled.");
    }

    @Override
    public String getName() { return "AuctionHouse"; }

    // ─── GUI : Browse (main) ─────────────────────────────────────

    public void openAuctionHouse(Player player) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<AuctionEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, item_data, start_price, current_bid, bidder_uuid, " +
                    "duration_hours, created_at FROM auctions " +
                    "WHERE expired = 0 " +
                    "ORDER BY (created_at + duration_hours * 3600000) ASC")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    entries.add(new AuctionEntry(
                            rs.getInt("id"),
                            rs.getString("seller_uuid"),
                            rs.getString("item_data"),
                            rs.getDouble("start_price"),
                            rs.getDouble("current_bid"),
                            rs.getString("bidder_uuid"),
                            rs.getInt("duration_hours"),
                            rs.getLong("created_at")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur chargement enchères: " + e.getMessage());
            }
            return entries;
        }).thenAccept(entries ->
            Bukkit.getScheduler().runTask(plugin, () -> renderBrowseGui(player, entries)));
    }

    private void renderBrowseGui(Player player, List<AuctionEntry> entries) {
        PaginatedGui gui = Gui.paginated()
                .title(Component.text("§8✦ §6Hôtel des Enchères §8✦"))
                .rows(6)
                .pageSize(36)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fillBorder(filler);

        for (AuctionEntry entry : entries) {
            try {
                ItemStack display = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.itemData));
                String sellerName = resolvePlayerName(entry.sellerUuid);
                double currentPrice = entry.currentBid > 0 ? entry.currentBid : entry.startPrice;
                double minBid = entry.currentBid > 0
                        ? entry.currentBid * (1.0 + MIN_BID_INCREMENT_PCT) : entry.startPrice;
                long expiresAt = entry.createdAt + TimeUnit.HOURS.toMillis(entry.durationHours);
                String timeLeft = formatTimeRemaining(expiresAt - System.currentTimeMillis());

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7Vendeur : §e" + sellerName));
                lore.add(Component.text("§7Enchère actuelle : §6" + String.format("%.1f", currentPrice) + " ✦"));
                lore.add(Component.text("§7Mise min. suivante : §6" + String.format("%.1f", minBid) + " ✦"));
                lore.add(Component.text("§7Temps restant : §e" + timeLeft));
                lore.add(Component.text(""));
                lore.add(Component.text("§aClic gauche → Enchérir"));

                gui.addItem(ItemBuilder.from(display)
                        .lore(lore)
                        .asGuiItem(event -> {
                            if (event.isLeftClick()) openBidConfirmation(player, entry, minBid);
                        }));
            } catch (Exception e) {
                plugin.getLogger().fine("AH: skip entrée invalide id=" + entry.id);
            }
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Page précédente")).asGuiItem(e -> gui.previous()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7Page suivante →")).asGuiItem(e -> gui.next()));
        gui.setItem(6, 1, ItemBuilder.from(Material.NETHER_STAR)
                .name(Component.text("§6✦ Mes Enchères"))
                .lore(Component.text("§7Voir tes annonces actives"))
                .asGuiItem(e -> openMyAuctions(player)));
        gui.setItem(6, 9, ItemBuilder.from(Material.CHEST)
                .name(Component.text("§e✦ Collecter"))
                .lore(Component.text("§7Récupérer gains / items expirés"))
                .asGuiItem(e -> openCollectGui(player)));
        gui.setItem(6, 5, ItemBuilder.from(Material.BARRIER)
                .name(Component.text("§c✗ Fermer"))
                .asGuiItem(e -> player.closeInventory()));

        gui.open(player);
    }

    // ─── GUI : Bid Confirmation ──────────────────────────────────

    private void openBidConfirmation(Player player, AuctionEntry entry, double minBid) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        if (entry.sellerUuid.equals(player.getUniqueId().toString())) {
            player.sendMessage("§c✗ Tu ne peux pas enchérir sur ta propre annonce.");
            return;
        }

        Gui confirmGui = Gui.gui()
                .title(Component.text("§8✦ §6Confirmer l'enchère §8✦"))
                .rows(3)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        confirmGui.getFiller().fill(filler);

        try {
            ItemStack display = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.itemData));
            confirmGui.setItem(2, 5, ItemBuilder.from(display)
                    .lore(
                            Component.text("§7Mise minimum : §6" + String.format("%.1f", minBid) + " ✦"),
                            Component.text("§7Ton solde : §6" + eco.formatMoney(eco.getBalance(player.getUniqueId())))
                    ).asGuiItem());
        } catch (Exception ignored) {}

        confirmGui.setItem(2, 3, ItemBuilder.from(Material.LIME_WOOL)
                .name(Component.text("§a✓ Enchérir §6" + String.format("%.1f", minBid) + " ✦"))
                .lore(
                        Component.text("§7La somme est débitée immédiatement."),
                        Component.text("§7Tu seras remboursé si quelqu'un surenchérit.")
                )
                .asGuiItem(e -> {
                    placeBid(player, entry, minBid);
                    player.closeInventory();
                }));

        confirmGui.setItem(2, 7, ItemBuilder.from(Material.RED_WOOL)
                .name(Component.text("§c✗ Annuler"))
                .asGuiItem(e -> openAuctionHouse(player)));

        confirmGui.open(player);
    }

    // ─── GUI : My Auctions ───────────────────────────────────────

    private void openMyAuctions(Player player) {
        String uuid = player.getUniqueId().toString();
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<AuctionEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, item_data, start_price, current_bid, bidder_uuid, " +
                    "duration_hours, created_at FROM auctions " +
                    "WHERE seller_uuid = ? AND expired = 0 ORDER BY created_at DESC")) {
                ps.setString(1, uuid);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    entries.add(new AuctionEntry(
                            rs.getInt("id"),
                            rs.getString("seller_uuid"),
                            rs.getString("item_data"),
                            rs.getDouble("start_price"),
                            rs.getDouble("current_bid"),
                            rs.getString("bidder_uuid"),
                            rs.getInt("duration_hours"),
                            rs.getLong("created_at")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur mes enchères: " + e.getMessage());
            }
            return entries;
        }).thenAccept(entries ->
            Bukkit.getScheduler().runTask(plugin, () -> renderMyAuctionsGui(player, entries)));
    }

    private void renderMyAuctionsGui(Player player, List<AuctionEntry> entries) {
        PaginatedGui gui = Gui.paginated()
                .title(Component.text("§8✦ §6Mes Enchères §8✦"))
                .rows(6)
                .pageSize(36)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fillBorder(filler);

        if (entries.isEmpty()) {
            gui.setItem(3, 5, ItemBuilder.from(Material.BARRIER)
                    .name(Component.text("§cAucune enchère active"))
                    .lore(Component.text("§7Utilise §e/ah sell <prix> §7pour vendre un item."))
                    .asGuiItem());
        }

        for (AuctionEntry entry : entries) {
            try {
                ItemStack display = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.itemData));
                long expiresAt = entry.createdAt + TimeUnit.HOURS.toMillis(entry.durationHours);
                String timeLeft = formatTimeRemaining(expiresAt - System.currentTimeMillis());
                double currentPrice = entry.currentBid > 0 ? entry.currentBid : entry.startPrice;

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7Prix de départ : §6" + String.format("%.1f", entry.startPrice) + " ✦"));
                lore.add(Component.text("§7Enchère actuelle : §6" + String.format("%.1f", currentPrice) + " ✦"));
                lore.add(Component.text("§7Expire dans : §e" + timeLeft));
                if (entry.bidderUuid != null) {
                    lore.add(Component.text("§aOffre en cours !"));
                }

                gui.addItem(ItemBuilder.from(display).lore(lore).asGuiItem());
            } catch (Exception ignored) {}
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Page précédente")).asGuiItem(e -> gui.previous()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7Page suivante →")).asGuiItem(e -> gui.next()));
        gui.setItem(6, 5, ItemBuilder.from(Material.BARRIER)
                .name(Component.text("§c← Retour")).asGuiItem(e -> openAuctionHouse(player)));

        gui.open(player);
    }

    // ─── GUI : Collect ───────────────────────────────────────────

    private void openCollectGui(Player player) {
        String uuid = player.getUniqueId().toString();
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<CollectableEntry> collectables = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, item_data, current_bid, bidder_uuid " +
                    "FROM auctions " +
                    "WHERE expired = 1 AND collected = 0 " +
                    "  AND (seller_uuid = ? OR bidder_uuid = ?) " +
                    "ORDER BY created_at DESC")) {
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    collectables.add(new CollectableEntry(
                            rs.getInt("id"),
                            rs.getString("seller_uuid"),
                            rs.getString("item_data"),
                            rs.getDouble("current_bid"),
                            rs.getString("bidder_uuid")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur collect: " + e.getMessage());
            }
            return collectables;
        }).thenAccept(collectables ->
            Bukkit.getScheduler().runTask(plugin, () -> renderCollectGui(player, collectables)));
    }

    private void renderCollectGui(Player player, List<CollectableEntry> collectables) {
        String uuid = player.getUniqueId().toString();

        PaginatedGui gui = Gui.paginated()
                .title(Component.text("§8✦ §eCollecter §8✦"))
                .rows(6)
                .pageSize(36)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fillBorder(filler);

        if (collectables.isEmpty()) {
            gui.setItem(3, 5, ItemBuilder.from(Material.LIME_DYE)
                    .name(Component.text("§aRien à collecter"))
                    .lore(Component.text("§7Tout est à jour !"))
                    .asGuiItem());
        }

        for (CollectableEntry entry : collectables) {
            try {
                ItemStack display = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.itemData));
                boolean isBuyer = uuid.equals(entry.bidderUuid);

                List<Component> lore = new ArrayList<>();
                if (isBuyer) {
                    lore.add(Component.text("§a✓ Tu as remporté cette enchère !"));
                } else {
                    lore.add(Component.text("§7Enchère expirée sans acheteur."));
                }
                lore.add(Component.text("§aClic pour récupérer."));

                gui.addItem(ItemBuilder.from(display)
                        .lore(lore)
                        .asGuiItem(event -> collectItem(player, entry, gui)));
            } catch (Exception ignored) {}
        }

        gui.setItem(6, 3, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Page précédente")).asGuiItem(e -> gui.previous()));
        gui.setItem(6, 7, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7Page suivante →")).asGuiItem(e -> gui.next()));
        gui.setItem(6, 5, ItemBuilder.from(Material.BARRIER)
                .name(Component.text("§c← Retour")).asGuiItem(e -> openAuctionHouse(player)));

        gui.open(player);
    }

    // ─── Logic : place bid ───────────────────────────────────────

    /**
     * Withdraws the bid amount immediately then updates the DB.
     * Refunds on any error or if the bid was already outbid.
     */
    private void placeBid(Player player, AuctionEntry entry, double bidAmount) {
        EconomyModule eco = plugin.getModule(EconomyModule.class);
        if (eco == null) return;

        // Withdraw immediately to prevent race-condition double-spend
        if (!eco.withdraw(player.getUniqueId(), bidAmount)) {
            player.sendMessage("§c✗ Fonds insuffisants ! Il te faut §6" + eco.formatMoney(bidAmount));
            return;
        }

        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement checkPs = conn.prepareStatement(
                    "SELECT current_bid, bidder_uuid, expired, start_price FROM auctions WHERE id = ?")) {
                checkPs.setInt(1, entry.id);
                ResultSet rs = checkPs.executeQuery();
                if (!rs.next()) return "NOT_FOUND";
                if (rs.getInt("expired") == 1) return "EXPIRED";

                double currentBid = rs.getDouble("current_bid");
                double startPrice = rs.getDouble("start_price");
                double minimumBid = currentBid > 0
                        ? currentBid * (1.0 + MIN_BID_INCREMENT_PCT) : startPrice;

                if (bidAmount < minimumBid) return "TOO_LOW:" + minimumBid;

                String previousBidder = rs.getString("bidder_uuid");
                double previousBid = currentBid;

                try (PreparedStatement updatePs = conn.prepareStatement(
                        "UPDATE auctions SET current_bid = ?, bidder_uuid = ? WHERE id = ?")) {
                    updatePs.setDouble(1, bidAmount);
                    updatePs.setString(2, player.getUniqueId().toString());
                    updatePs.setInt(3, entry.id);
                    updatePs.executeUpdate();
                }

                String prevInfo = (previousBidder != null ? previousBidder : "") + ":" + previousBid;
                return "OK:" + prevInfo;
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur placeBid: " + e.getMessage());
                return "ERROR";
            }
        }).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            EconomyModule ecoRef = plugin.getModule(EconomyModule.class);
            if (ecoRef == null) return;

            if ("NOT_FOUND".equals(result) || "EXPIRED".equals(result)) {
                ecoRef.deposit(player.getUniqueId(), bidAmount);
                player.sendMessage("NOT_FOUND".equals(result)
                        ? "§c✗ Cette enchère n'existe plus."
                        : "§c✗ Cette enchère a expiré.");
            } else if ("ERROR".equals(result)) {
                ecoRef.deposit(player.getUniqueId(), bidAmount);
                player.sendMessage("§c✗ Erreur serveur. Réessaie ou contacte un admin.");
            } else if (result.startsWith("TOO_LOW:")) {
                ecoRef.deposit(player.getUniqueId(), bidAmount);
                double min = Double.parseDouble(result.substring(8));
                player.sendMessage("§c✗ Enchère trop basse ! Minimum : §6" + ecoRef.formatMoney(min));
            } else if (result.startsWith("OK:")) {
                // result = "OK:<prevUuid>:<prevBid>"
                String payload = result.substring(3);
                int sep = payload.lastIndexOf(':');
                String prevUuidStr = payload.substring(0, sep);
                double prevBid = Double.parseDouble(payload.substring(sep + 1));

                player.sendMessage("§a✦ Enchère placée : §6" + ecoRef.formatMoney(bidAmount));
                player.playSound(player.getLocation(),
                        org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);

                // Refund previous bidder
                if (!prevUuidStr.isEmpty() && prevBid > 0) {
                    UUID prevUuid = UUID.fromString(prevUuidStr);
                    ecoRef.deposit(prevUuid, prevBid);
                    Player prevOnline = Bukkit.getPlayer(prevUuid);
                    if (prevOnline != null) {
                        prevOnline.sendMessage("§e✦ Tu as été surenchéri ! §6"
                                + ecoRef.formatMoney(prevBid) + " §eremboursé.");
                    }
                }
            }
        }));
    }

    // ─── Logic : collect item ────────────────────────────────────

    private void collectItem(Player player, CollectableEntry entry, PaginatedGui gui) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auctions SET collected = 1 WHERE id = ? AND collected = 0")) {
                ps.setInt(1, entry.id);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            player.sendMessage("§c✗ Cet item a déjà été collecté."));
                    return;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur collectItem: " + e.getMessage());
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.itemData));
                    var overflow = player.getInventory().addItem(item);
                    if (!overflow.isEmpty()) {
                        overflow.values().forEach(i ->
                                player.getWorld().dropItemNaturally(player.getLocation(), i));
                        player.sendMessage("§7(Item droppé au sol — inventaire plein)");
                    }
                    player.sendMessage("§a✦ Item récupéré !");
                    player.playSound(player.getLocation(),
                            org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                    // Refresh
                    openCollectGui(player);
                } catch (Exception e) {
                    player.sendMessage("§c✗ Erreur lors de la récupération de l'item.");
                    plugin.getLogger().log(Level.WARNING, "AH: erreur deserialize item", e);
                }
            });
        });
    }

    // ─── Logic : list auction ────────────────────────────────────

    private void listAuction(Player player, double startPrice, int durationHours) {
        if (startPrice < MIN_START_PRICE) {
            player.sendMessage("§c✗ Prix minimum : §6" + MIN_START_PRICE + " ✦");
            return;
        }
        if (durationHours < 1 || durationHours > MAX_DURATION_HOURS) {
            player.sendMessage("§c✗ Durée invalide. Entre 1 et " + MAX_DURATION_HOURS + " heures.");
            return;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType() == Material.AIR) {
            player.sendMessage("§c✗ Tu dois tenir un item en main.");
            return;
        }

        // Serialize and take item before going async (prevents duplication exploits)
        String serialized = Base64.getEncoder().encodeToString(inHand.serializeAsBytes());
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        String uuid = player.getUniqueId().toString();
        final int finalDuration = durationHours;
        final double finalPrice = startPrice;

        plugin.getDatabaseManager().executeAsync(conn -> {
            // Enforce per-player limit
            try (PreparedStatement countPs = conn.prepareStatement(
                    "SELECT COUNT(*) FROM auctions WHERE seller_uuid = ? AND expired = 0")) {
                countPs.setString(1, uuid);
                ResultSet rs = countPs.executeQuery();
                if (rs.next() && rs.getInt(1) >= MAX_AUCTIONS_PER_PLAYER) return "MAX_REACHED";
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur count: " + e.getMessage());
                return "ERROR";
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auctions (seller_uuid, item_data, start_price, current_bid, " +
                    "duration_hours, created_at, expired, collected) VALUES (?, ?, ?, 0, ?, ?, 0, 0)")) {
                ps.setString(1, uuid);
                ps.setString(2, serialized);
                ps.setDouble(3, finalPrice);
                ps.setInt(4, finalDuration);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur insert auction: " + e.getMessage());
                return "ERROR";
            }
            return "OK";
        }).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if ("MAX_REACHED".equals(result) || "ERROR".equals(result)) {
                // Return item
                try {
                    ItemStack returned = ItemStack.deserializeBytes(Base64.getDecoder().decode(serialized));
                    var overflow = player.getInventory().addItem(returned);
                    overflow.values().forEach(i ->
                            player.getWorld().dropItemNaturally(player.getLocation(), i));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "AH: impossible de retourner l'item", e);
                }
                if ("MAX_REACHED".equals(result)) {
                    player.sendMessage("§c✗ Tu as déjà " + MAX_AUCTIONS_PER_PLAYER
                            + " enchères actives (maximum atteint).");
                } else {
                    player.sendMessage("§c✗ Erreur serveur lors de la mise en vente.");
                }
            } else {
                EconomyModule eco = plugin.getModule(EconomyModule.class);
                String priceStr = eco != null ? eco.formatMoney(finalPrice) : finalPrice + " ✦";
                player.sendMessage("§e✦ Item mis aux enchères pour §6" + priceStr
                        + " §edurant §f" + finalDuration + "h§e.");
                player.playSound(player.getLocation(),
                        org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
            }
        })).exceptionally(ex -> {
            // DB exception (connection closed etc.) — always return the item
            plugin.getLogger().log(Level.WARNING, "AH: exception lors de la mise en vente, retour item au joueur", ex);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    ItemStack returned = ItemStack.deserializeBytes(Base64.getDecoder().decode(serialized));
                    var overflow = player.getInventory().addItem(returned);
                    overflow.values().forEach(i ->
                            player.getWorld().dropItemNaturally(player.getLocation(), i));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "AH: impossible de retourner l'item après exception", e);
                }
                player.sendMessage("§c✗ Erreur serveur lors de la mise en vente. Item retourné.");
            });
            return null;
        });
    }

    // ─── Logic : process expiries ────────────────────────────────

    /**
     * Runs asynchronously (via BukkitRunnable#runTaskTimerAsynchronously).
     * Finds all auctions whose time has elapsed, marks them expired,
     * pays sellers and notifies players on the main thread.
     */
    private void processExpiredAuctions() {
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<AuctionEntry> expired = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, item_data, start_price, current_bid, bidder_uuid, " +
                    "duration_hours, created_at FROM auctions " +
                    "WHERE expired = 0 AND (created_at + CAST(duration_hours AS INTEGER) * 3600000) <= ?")) {
                ps.setLong(1, now);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    expired.add(new AuctionEntry(
                            rs.getInt("id"),
                            rs.getString("seller_uuid"),
                            rs.getString("item_data"),
                            rs.getDouble("start_price"),
                            rs.getDouble("current_bid"),
                            rs.getString("bidder_uuid"),
                            rs.getInt("duration_hours"),
                            rs.getLong("created_at")));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur chargement expirées: " + e.getMessage());
                return null;
            }

            if (expired.isEmpty()) return expired;

            try (PreparedStatement markPs = conn.prepareStatement(
                    "UPDATE auctions SET expired = 1 WHERE id = ?")) {
                for (AuctionEntry e : expired) {
                    markPs.setInt(1, e.id);
                    markPs.addBatch();
                }
                markPs.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().warning("AH: erreur marquage expirées: " + e.getMessage());
            }

            return expired;
        }).thenAccept(expired -> {
            if (expired == null || expired.isEmpty()) return;

            EconomyModule eco = plugin.getModule(EconomyModule.class);
            if (eco == null) return;

            for (AuctionEntry entry : expired) {
                UUID sellerUuid = UUID.fromString(entry.sellerUuid);

                if (entry.bidderUuid != null && entry.currentBid > 0) {
                    // Sold — credit seller minus tax
                    double tax     = entry.currentBid * SELLER_TAX;
                    double gain    = entry.currentBid - tax;
                    eco.depositFromEarning(sellerUuid, gain);
                    plugin.getLogger().fine("AH: enchère #" + entry.id + " vendue, " + gain + " ✦ → vendeur.");

                    UUID buyerUuid = UUID.fromString(entry.bidderUuid);
                    EconomyModule ecoFinal = eco;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player seller = Bukkit.getPlayer(sellerUuid);
                        if (seller != null) {
                            seller.sendMessage("§a✦ Enchère #" + entry.id + " terminée ! §6"
                                    + ecoFinal.formatMoney(gain) + " §acrédités §7(taxe "
                                    + (int)(SELLER_TAX * 100) + "% déduite).");
                        }
                        Player buyer = Bukkit.getPlayer(buyerUuid);
                        if (buyer != null) {
                            buyer.sendMessage("§e✦ Tu as remporté une enchère ! Utilise §a/ah collect §epour récupérer ton item.");
                        }
                    });
                } else {
                    // No bid — return to seller (collected via /ah collect)
                    plugin.getLogger().fine("AH: enchère #" + entry.id + " expirée sans offre.");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player seller = Bukkit.getPlayer(sellerUuid);
                        if (seller != null) {
                            seller.sendMessage("§7✦ Ton enchère #" + entry.id + " a expiré sans offre. "
                                    + "Utilise §e/ah collect §7pour récupérer ton item.");
                        }
                    });
                }
            }
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private String resolvePlayerName(String uuid) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
            return name != null ? name : "Inconnu";
        } catch (Exception e) {
            return "Inconnu";
        }
    }

    private String formatTimeRemaining(long millis) {
        if (millis <= 0) return "§cExpirée";
        long seconds = millis / 1000;
        long hours   = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs    = seconds % 60;
        if (hours   > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }

    // ─── Command ─────────────────────────────────────────────────

    private class AhCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            if (!player.hasPermission("survival.auction")) {
                player.sendMessage("§cTu n'as pas la permission d'utiliser l'hôtel des enchères.");
                return true;
            }

            if (args.length == 0) {
                openAuctionHouse(player);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "sell" -> {
                    if (args.length < 2) {
                        player.sendMessage("§cUsage : /ah sell <prix> [durée_heures]");
                        return true;
                    }
                    double price;
                    try { price = Double.parseDouble(args[1]); }
                    catch (NumberFormatException e) { player.sendMessage("§cPrix invalide."); return true; }
                    int hours = DEFAULT_DURATION_HOURS;
                    if (args.length >= 3) {
                        try { hours = Integer.parseInt(args[2]); }
                        catch (NumberFormatException e) { player.sendMessage("§cDurée invalide."); return true; }
                    }
                    listAuction(player, price, hours);
                }
                case "collect" -> openCollectGui(player);
                default -> player.sendMessage("§cUsage : /ah [sell <prix> [durée]] | /ah collect");
            }
            return true;
        }
    }

    // ─── Data records ────────────────────────────────────────────

    public record AuctionEntry(
            int id,
            String sellerUuid,
            String itemData,
            double startPrice,
            double currentBid,
            String bidderUuid,
            int durationHours,
            long createdAt) {}

    public record CollectableEntry(
            int id,
            String sellerUuid,
            String itemData,
            double currentBid,
            String bidderUuid) {}
}
