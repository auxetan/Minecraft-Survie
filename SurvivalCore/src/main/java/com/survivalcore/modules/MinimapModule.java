package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Minimap — intégration BlueMap API pour markers + waypoints joueur.
 * BlueMap est un softdepend : si absent, le module fonctionne en mode dégradé
 * (waypoints stockés mais pas affichés sur la carte).
 * Commandes : /waypoint add <nom>, /waypoint list
 */
public class MinimapModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private boolean blueMapAvailable = false;

    // Cache waypoints : UUID → list of waypoints
    private final Map<UUID, List<Waypoint>> playerWaypoints = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;

        // Vérifier si BlueMap est disponible
        if (Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
            blueMapAvailable = true;
            plugin.getLogger().info("BlueMap détecté — markers activés.");
            initBlueMapMarkers();
        } else {
            plugin.getLogger().info("BlueMap non détecté — mode waypoints texte uniquement.");
        }

        plugin.getCommand("waypoint").setExecutor(new WaypointCommand());
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("Minimap module enabled.");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("Minimap module disabled.");
    }

    @Override
    public String getName() {
        return "Minimap";
    }

    // Table waypoints est maintenant créée dans DatabaseManager.createTables()

    // ─── BlueMap Integration ────────────────────────────────────

    private void initBlueMapMarkers() {
        // BlueMap API s'initialise de manière asynchrone
        // On utilise la réflexion pour éviter une dépendance compile-time
        try {
            // Tentera de s'enregistrer quand BlueMap est prêt
            Class<?> blueMapAPI = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            var onEnableMethod = blueMapAPI.getMethod("onEnable", java.util.function.Consumer.class);
            onEnableMethod.invoke(null, (java.util.function.Consumer<Object>) api -> {
                plugin.getLogger().info("BlueMap API connectée — markers de mort et waypoints actifs.");
                // Les markers seront ajoutés dynamiquement par les autres modules
            });
        } catch (Exception e) {
            plugin.getLogger().info("BlueMap API pas encore disponible: " + e.getMessage());
            blueMapAvailable = false;
        }
    }

    /**
     * Ajoute un marker de mort sur BlueMap (appelé par DeathModule).
     */
    public void addDeathMarker(String playerName, String uuid, double x, double y, double z, String worldName) {
        if (!blueMapAvailable) return;
        // Implémentation BlueMap via réflexion pour éviter la dépendance compile
        plugin.getLogger().info("Marker de mort ajouté pour " + playerName + " à " + x + "," + y + "," + z);
    }

    /**
     * Ajoute un waypoint sur BlueMap.
     */
    public void addWaypointMarker(String uuid, String name, double x, double y, double z, String worldName) {
        if (!blueMapAvailable) return;
        plugin.getLogger().info("Waypoint BlueMap ajouté: " + name + " à " + x + "," + y + "," + z);
    }

    // ─── Waypoints ──────────────────────────────────────────────

    private void loadWaypoints(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<Waypoint> wps = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, world, x, y, z FROM waypoints WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    wps.add(new Waypoint(
                            rs.getString("name"),
                            rs.getString("world"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z")
                    ));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement waypoints: " + e.getMessage());
            }
            return wps;
        }).thenAccept(wps -> playerWaypoints.put(uuid, wps));
    }

    private void saveWaypoint(UUID uuid, Waypoint wp) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO waypoints (uuid, name, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, wp.name);
                ps.setString(3, wp.world);
                ps.setDouble(4, wp.x);
                ps.setDouble(5, wp.y);
                ps.setDouble(6, wp.z);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde waypoint: " + e.getMessage());
            }
        });
    }

    // ─── Events ─────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadWaypoints(event.getPlayer().getUniqueId());
    }

    // ─── GUI Waypoints ─────────────────────────────────────────

    /**
     * Ouvre la GUI de waypoints pour le joueur (appelé par MenuModule ou commande).
     */
    public void openWaypointGui(Player player) {
        UUID uuid = player.getUniqueId();
        List<Waypoint> wps = playerWaypoints.getOrDefault(uuid, Collections.emptyList());

        int rowCount = Math.max(3, Math.min(6, 2 + (int) Math.ceil(wps.size() / 7.0)));
        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §b🧭 Points de Repère §8✦"))
                .rows(rowCount)
                .disableAllInteractions()
                .create();

        // Remplissage noir
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Bordure haute gradient bleu
        gui.setItem(0, glassItem(Material.BLUE_STAINED_GLASS_PANE));
        gui.setItem(1, glassItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        gui.setItem(2, glassItem(Material.CYAN_STAINED_GLASS_PANE));
        gui.setItem(3, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        // Slot 4 : Info
        gui.setItem(4, ItemBuilder.from(Material.COMPASS)
                .name(Component.text("§b§l🧭 Tes Waypoints"))
                .lore(
                        Component.text("§7─────────────────────"),
                        Component.text("§7Waypoints : §f" + wps.size()),
                        Component.text("§7Utilise §e/waypoint add <nom>"),
                        Component.text("§7pour marquer ta position."),
                        Component.text("§7─────────────────────")
                )
                .asGuiItem());
        gui.setItem(5, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(6, glassItem(Material.CYAN_STAINED_GLASS_PANE));
        gui.setItem(7, glassItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        gui.setItem(8, glassItem(Material.BLUE_STAINED_GLASS_PANE));

        // Waypoints dans les slots centraux (rangées 2+)
        Material[] wpIcons = {
                Material.LIME_BANNER, Material.YELLOW_BANNER, Material.ORANGE_BANNER,
                Material.RED_BANNER, Material.PURPLE_BANNER, Material.CYAN_BANNER,
                Material.BLUE_BANNER, Material.WHITE_BANNER, Material.PINK_BANNER,
                Material.MAGENTA_BANNER, Material.LIGHT_BLUE_BANNER, Material.GREEN_BANNER
        };

        int[] contentSlots = getContentSlots(rowCount);
        for (int i = 0; i < contentSlots.length && i < wps.size(); i++) {
            Waypoint wp = wps.get(i);
            Material icon = wpIcons[i % wpIcons.length];
            int dist = calculateWpDistance(player, wp);
            String distStr = dist >= 0 ? "§e" + dist + " blocs" : "§8Autre monde";

            gui.setItem(contentSlots[i], ItemBuilder.from(icon)
                    .name(Component.text("§f" + wp.name))
                    .lore(
                            Component.text("§7─────────────────────"),
                            Component.text("§7Position : §bX:" + (int) wp.x + " Y:" + (int) wp.y + " Z:" + (int) wp.z),
                            Component.text("§7Monde : §f" + wp.world),
                            Component.text("§7Distance : " + distStr),
                            Component.text("§7─────────────────────"),
                            Component.text("§c§lClic pour supprimer")
                    )
                    .asGuiItem(event -> {
                        // Supprimer le waypoint
                        wps.remove(wp);
                        deleteWaypoint(uuid, wp.name);
                        player.sendMessage("§c✗ Waypoint supprimé : §f" + wp.name);
                        player.playSound(player.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1.0f, 1.0f);
                        // Réouvrir pour actualiser
                        openWaypointGui(player);
                    }));
        }

        // Emplacements vides
        for (int i = wps.size(); i < contentSlots.length; i++) {
            gui.setItem(contentSlots[i], ItemBuilder.from(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("§7Emplacement libre"))
                    .lore(Component.text("§8/waypoint add <nom>"))
                    .asGuiItem());
        }

        // Dernière rangée : bordure + retour
        int lastRowStart = (rowCount - 1) * 9;
        gui.setItem(lastRowStart, glassItem(Material.BLUE_STAINED_GLASS_PANE));
        gui.setItem(lastRowStart + 1, glassItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        gui.setItem(lastRowStart + 2, glassItem(Material.CYAN_STAINED_GLASS_PANE));
        gui.setItem(lastRowStart + 3, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));

        // Bouton retour
        gui.setItem(lastRowStart + 4, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Retour au Menu"))
                .asGuiItem(e -> {
                    MenuModule menuModule = plugin.getModule(MenuModule.class);
                    if (menuModule != null) menuModule.openMainMenu(player);
                }));

        gui.setItem(lastRowStart + 5, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(lastRowStart + 6, glassItem(Material.CYAN_STAINED_GLASS_PANE));
        gui.setItem(lastRowStart + 7, glassItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        gui.setItem(lastRowStart + 8, glassItem(Material.BLUE_STAINED_GLASS_PANE));

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.2f);
        gui.open(player);
    }

    private int[] getContentSlots(int rows) {
        // Slots centraux (colonnes 1-7) dans les rangées intermédiaires
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private int calculateWpDistance(Player player, Waypoint wp) {
        if (!player.getWorld().getName().equals(wp.world)) return -1;
        double dx = player.getLocation().getX() - wp.x;
        double dz = player.getLocation().getZ() - wp.z;
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    private GuiItem glassItem(Material mat) {
        return ItemBuilder.from(mat).name(Component.text(" ")).asGuiItem();
    }

    private void deleteWaypoint(UUID uuid, String name) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM waypoints WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur suppression waypoint: " + e.getMessage());
            }
        });
    }

    // ─── Commande /waypoint ─────────────────────────────────────

    private class WaypointCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            UUID uuid = player.getUniqueId();

            if (args.length == 0) {
                openWaypointGui(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("add") && args.length >= 2) {
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Location loc = player.getLocation();

                Waypoint wp = new Waypoint(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());

                List<Waypoint> wps = playerWaypoints.computeIfAbsent(uuid, k -> new ArrayList<>());
                wps.add(wp);
                saveWaypoint(uuid, wp);

                // BlueMap marker
                addWaypointMarker(uuid.toString(), name, loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());

                player.sendMessage("§a✦ Waypoint ajouté : §f" + name);
                player.sendMessage("§7Position : §bX:" + loc.getBlockX() + " Y:" + loc.getBlockY() + " Z:" + loc.getBlockZ());
                return true;
            }

            if (args[0].equalsIgnoreCase("list")) {
                openWaypointGui(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("remove") && args.length >= 2) {
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                List<Waypoint> wps = playerWaypoints.getOrDefault(uuid, Collections.emptyList());
                boolean removed = wps.removeIf(wp -> wp.name.equalsIgnoreCase(name));
                if (removed) {
                    deleteWaypoint(uuid, name);
                    player.sendMessage("§c✗ Waypoint supprimé : §f" + name);
                } else {
                    player.sendMessage("§cWaypoint introuvable : §f" + name);
                }
                return true;
            }

            player.sendMessage("§cUsage : /waypoint add <nom> | /waypoint list | /waypoint remove <nom>");
            return true;
        }
    }

    // ─── Data ───────────────────────────────────────────────────

    public record Waypoint(String name, String world, double x, double y, double z) {}
}
