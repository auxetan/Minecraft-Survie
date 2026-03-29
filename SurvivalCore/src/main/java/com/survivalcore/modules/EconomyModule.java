package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module économie — monnaie custom "Éclats" intégrée avec Vault.
 * Cache en mémoire + persistance SQLite async.
 */
public class EconomyModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();
    private VaultEconomy vaultEconomy;
    private String currencyName;
    private String currencySymbol;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        this.currencyName = plugin.getConfig().getString("currency-name", "Éclat");
        this.currencySymbol = plugin.getConfig().getString("currency-symbol", "✦");

        // Enregistrer l'économie Vault
        vaultEconomy = new VaultEconomy();
        Bukkit.getServicesManager().register(Economy.class, vaultEconomy, plugin, ServicePriority.Highest);

        // Charger les balances des joueurs déjà connectés
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadBalance(p.getUniqueId());
        }

        // Enregistrer listeners et commandes
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("balance").setExecutor(new BalanceCommand());
        plugin.getCommand("pay").setExecutor(new PayCommand());

        plugin.getLogger().info("Economy module enabled.");
    }

    @Override
    public void onDisable() {
        // Sauvegarder toutes les balances
        for (Map.Entry<UUID, Double> entry : balanceCache.entrySet()) {
            saveBalanceSync(entry.getKey(), entry.getValue());
        }
        plugin.getLogger().info("Economy module disabled.");
    }

    @Override
    public String getName() {
        return "Economy";
    }

    // ─── API publique ────────────────────────────────────────────

    public double getBalance(UUID uuid) {
        return balanceCache.getOrDefault(uuid, 0.0);
    }

    public void setBalance(UUID uuid, double amount) {
        balanceCache.put(uuid, amount);
        saveBalanceAsync(uuid, amount);
    }

    public void deposit(UUID uuid, double amount) {
        double newBalance = getBalance(uuid) + amount;
        setBalance(uuid, newBalance);

        // 5% du gain va au fonds commun (spec : tax auto sur chaque gain)
        ShopModule shop = plugin.getModule(ShopModule.class);
        if (shop != null) {
            shop.addToCommonFund(amount);
        }

        // Mettre à jour money_earned en async
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET money_earned = money_earned + ? WHERE uuid = ?")) {
                ps.setDouble(1, amount);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur mise à jour money_earned: " + e.getMessage());
            }
        });
    }

    public boolean withdraw(UUID uuid, double amount) {
        double balance = getBalance(uuid);
        if (balance < amount) return false;
        setBalance(uuid, balance - amount);
        return true;
    }

    public String getCurrencyName() {
        return currencyName;
    }

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public String formatMoney(double amount) {
        return String.format("%.1f %s", amount, currencySymbol);
    }

    // ─── Persistance ────────────────────────────────────────────

    private void loadBalance(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT money FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return rs.getDouble("money");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement balance: " + e.getMessage());
            }
            return 0.0;
        }).thenAccept(balance -> balanceCache.put(uuid, balance));
    }

    private void saveBalanceAsync(UUID uuid, double amount) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET money = ? WHERE uuid = ?")) {
                ps.setDouble(1, amount);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde balance: " + e.getMessage());
            }
        });
    }

    private void saveBalanceSync(UUID uuid, double amount) {
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET money = ? WHERE uuid = ?")) {
                    ps.setDouble(1, amount);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Erreur sauvegarde sync balance: " + e.getMessage());
        }
    }

    /** Crée l'entrée joueur en DB si elle n'existe pas. */
    public void ensurePlayerExists(UUID uuid, String name) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO players (uuid, name) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur création joueur: " + e.getMessage());
            }
        });
    }

    // ─── Events ─────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensurePlayerExists(player.getUniqueId(), player.getName());
        loadBalance(player.getUniqueId());
    }

    // ─── Commandes ──────────────────────────────────────────────

    private class BalanceCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            double balance = getBalance(player.getUniqueId());
            player.sendMessage("§6§l✦ §eSolde : §f" + formatMoney(balance));
            return true;
        }
    }

    private class PayCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage("§cUsage : /pay <joueur> <montant>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                player.sendMessage("§cJoueur introuvable.");
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage("§cMontant invalide.");
                return true;
            }
            if (amount <= 0) {
                player.sendMessage("§cLe montant doit être positif.");
                return true;
            }
            if (!withdraw(player.getUniqueId(), amount)) {
                player.sendMessage("§cFonds insuffisants.");
                return true;
            }
            deposit(target.getUniqueId(), amount);
            player.sendMessage("§a✦ Tu as envoyé §f" + formatMoney(amount) + " §aà §f" + target.getName());
            target.sendMessage("§a✦ Tu as reçu §f" + formatMoney(amount) + " §ade §f" + player.getName());
            return true;
        }
    }

    // ─── Vault Economy Implementation ───────────────────────────

    public class VaultEconomy implements Economy {
        @Override public boolean isEnabled() { return true; }
        @Override public String getName() { return "SurvivalCore"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 1; }
        @Override public String format(double amount) { return formatMoney(amount); }
        @Override public String currencyNamePlural() { return currencyName + "s"; }
        @Override public String currencyNameSingular() { return currencyName; }

        @Override public boolean hasAccount(OfflinePlayer player) { return balanceCache.containsKey(player.getUniqueId()); }
        @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return hasAccount(player); }
        @Override public double getBalance(OfflinePlayer player) { return EconomyModule.this.getBalance(player.getUniqueId()); }
        @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }
        @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
        @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (amount < 0) return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Négatif");
            if (!has(player, amount)) return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Fonds insuffisants");
            EconomyModule.this.withdraw(player.getUniqueId(), amount);
            return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, "");
        }

        @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            if (amount < 0) return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Négatif");
            EconomyModule.this.deposit(player.getUniqueId(), amount);
            return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, "");
        }

        @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            ensurePlayerExists(player.getUniqueId(), player.getName());
            return true;
        }

        @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return createPlayerAccount(player); }

        // Méthodes String deprecated
        @Override public boolean hasAccount(String n) { return hasAccount(Bukkit.getOfflinePlayer(n)); }
        @Override public boolean hasAccount(String n, String w) { return hasAccount(n); }
        @Override public double getBalance(String n) { return getBalance(Bukkit.getOfflinePlayer(n)); }
        @Override public double getBalance(String n, String w) { return getBalance(n); }
        @Override public boolean has(String n, double a) { return has(Bukkit.getOfflinePlayer(n), a); }
        @Override public boolean has(String n, String w, double a) { return has(n, a); }
        @Override public EconomyResponse withdrawPlayer(String n, double a) { return withdrawPlayer(Bukkit.getOfflinePlayer(n), a); }
        @Override public EconomyResponse withdrawPlayer(String n, String w, double a) { return withdrawPlayer(n, a); }
        @Override public EconomyResponse depositPlayer(String n, double a) { return depositPlayer(Bukkit.getOfflinePlayer(n), a); }
        @Override public EconomyResponse depositPlayer(String n, String w, double a) { return depositPlayer(n, a); }
        @Override public boolean createPlayerAccount(String n) { return createPlayerAccount(Bukkit.getOfflinePlayer(n)); }
        @Override public boolean createPlayerAccount(String n, String w) { return createPlayerAccount(n); }

        // Bank — non supporté
        @Override public EconomyResponse createBank(String n, OfflinePlayer p) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse createBank(String n, String p) { return createBank(n, (OfflinePlayer) null); }
        @Override public EconomyResponse deleteBank(String n) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse bankBalance(String n) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse bankHas(String n, double a) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse bankWithdraw(String n, double a) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse bankDeposit(String n, double a) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse isBankOwner(String n, OfflinePlayer p) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse isBankOwner(String n, String p) { return isBankOwner(n, (OfflinePlayer) null); }
        @Override public EconomyResponse isBankMember(String n, OfflinePlayer p) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, ""); }
        @Override public EconomyResponse isBankMember(String n, String p) { return isBankMember(n, (OfflinePlayer) null); }
        @Override public List<String> getBanks() { return Collections.emptyList(); }
    }
}
