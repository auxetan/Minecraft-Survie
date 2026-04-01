package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Mort Custom — Sac de mort + beacon particules + marker.
 * Inspiré de DeadChest : drop inventaire dans un item custom, restriction UUID.
 */
public class DeathModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private NamespacedKey ownerKey;
    private NamespacedKey deathBagKey;
    private NamespacedKey inventoryKey;

    // Cache des positions de mort pour l'action bar post-respawn
    private final Map<UUID, Location> pendingDeathLocations = new ConcurrentHashMap<>();
    // Tâches de particules actives (pour cleanup)
    private final Map<UUID, BukkitTask> activeBeacons = new ConcurrentHashMap<>();

    private int beaconDuration; // secondes

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "owner_uuid");
        this.deathBagKey = new NamespacedKey(plugin, "death_bag");
        this.inventoryKey = new NamespacedKey(plugin, "death_inventory");

        this.beaconDuration = plugin.getConfig().getInt("death-beacon-duration", 300);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("deaths").setExecutor(new DeathsCommand());

        plugin.getLogger().info("Death module enabled.");
    }

    @Override
    public void onDisable() {
        // Annuler tous les beacons actifs
        for (BukkitTask task : activeBeacons.values()) {
            task.cancel();
        }
        activeBeacons.clear();
        plugin.getLogger().info("Death module disabled.");
    }

    @Override
    public String getName() {
        return "Death";
    }

    // ─── Death Event ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        Location deathLoc = player.getLocation().clone();

        // 1. Capturer l'inventaire avant le drop vanilla
        ItemStack[] rawContents = player.getInventory().getContents().clone();
        ItemStack[] armor = player.getInventory().getArmorContents().clone();

        // Exclure le Grimoire de Classe du sac de mort (il sera redonné au respawn)
        ItemStack[] contents = new ItemStack[rawContents.length];
        for (int i = 0; i < rawContents.length; i++) {
            if (ClassModule.isGrimoire(rawContents[i])) {
                contents[i] = null; // ne pas mettre dans le sac
            } else {
                contents[i] = rawContents[i];
            }
        }

        // Empêcher le drop vanilla
        event.getDrops().clear();
        event.setKeepInventory(false);

        // 2. Créer le sac de mort
        ItemStack deathBag = createDeathBag(uuid, player.getName(), contents, armor);

        // 3. Drop le sac à la position de mort
        deathLoc.getWorld().dropItemNaturally(deathLoc, deathBag);

        // 4. Beacon de particules
        startDeathBeacon(uuid, deathLoc);

        // 5. ArmorStand tête de mort (5 min)
        spawnDeathMarker(deathLoc);

        // 6. Enregistrer en SQLite async
        saveDeathLocation(uuid, deathLoc);

        // 7. Incrémenter le compteur de morts + broadcast via AnnouncerModule
        // Paper 1.21.x: deathMessage() retourne un Component
        net.kyori.adventure.text.Component deathMsg = event.deathMessage();
        String deathCause = deathMsg != null
                ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(deathMsg)
                : "Inconnu";
        String playerName = player.getName();
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET deaths = deaths + 1 WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur incrémentation deaths: " + e.getMessage());
            }
            // Lire le compteur mis à jour
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT deaths FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt("deaths");
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur lecture deaths: " + e.getMessage());
            }
            return 0;
        }).thenAccept(deathCount -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                AnnouncerModule announcer = plugin.getModule(AnnouncerModule.class);
                if (announcer != null) {
                    announcer.announcePlayerDeath(playerName, deathCause,
                            deathLoc.getBlockX(), deathLoc.getBlockY(), deathLoc.getBlockZ(), deathCount);
                }
            });
        });

        // 8. Message dans le chat avec coordonnées
        String coords = "§bX:" + deathLoc.getBlockX() + " Y:" + deathLoc.getBlockY() + " Z:" + deathLoc.getBlockZ();
        player.sendMessage("§c§l☠ Tu es mort ! §7Ton sac est en " + coords);

        // 11. Drop tête du joueur si tué par un autre joueur (PvP)
        if (player.getKiller() != null) {
            dropPlayerHead(player, deathLoc);
        }

        // 9. Stocker la position pour l'action bar post-respawn
        pendingDeathLocations.put(uuid, deathLoc);

        // 10. BlueMap death marker via MinimapModule
        MinimapModule minimap = plugin.getModule(MinimapModule.class);
        if (minimap != null) {
            minimap.addDeathMarker(player.getName(), uuid.toString(),
                    deathLoc.getX(), deathLoc.getY(), deathLoc.getZ(),
                    deathLoc.getWorld().getName());
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location deathLoc = pendingDeathLocations.remove(uuid);

        if (deathLoc != null) {
            // Action bar pendant 10 secondes après respawn
            new BukkitRunnable() {
                int ticks = 0;
                @Override
                public void run() {
                    if (ticks >= 200 || !player.isOnline()) { // 200 ticks = 10s
                        cancel();
                        return;
                    }
                    player.sendActionBar(net.kyori.adventure.text.Component.text(
                            "§c☠ Ton sac est en X:" + deathLoc.getBlockX() +
                            " Y:" + deathLoc.getBlockY() +
                            " Z:" + deathLoc.getBlockZ()
                    ));
                    ticks += 20;
                }
            }.runTaskTimer(plugin, 20L, 20L); // Start after 1s, repeat every 1s
        }
    }

    // ─── Tête du Joueur (PvP) ───────────────────────────────────

    private void dropPlayerHead(Player victim, Location loc) {
        Player killer = victim.getKiller();
        if (killer == null) return;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return;
        meta.setOwningPlayer(victim);
        meta.displayName(net.kyori.adventure.text.Component.text(
                "§6☠ Tête de §e" + victim.getName()));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("§8Trophée de PvP"),
                net.kyori.adventure.text.Component.text("§7Tué par §c" + killer.getName())
        ));
        head.setItemMeta(meta);
        loc.getWorld().dropItemNaturally(loc, head);
        killer.sendMessage("§6☠ La tête de §e" + victim.getName() + " §6est tombée !");
    }

    // ─── Sac de Mort ────────────────────────────────────────────

    private ItemStack createDeathBag(UUID ownerUuid, String playerName, ItemStack[] contents, ItemStack[] armor) {
        ItemStack bag = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = bag.getItemMeta();

        meta.displayName(net.kyori.adventure.text.Component.text("§c§l☠ Sac de Mort de " + playerName));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.text("§7Clic droit pour récupérer ton inventaire"));
        lore.add(net.kyori.adventure.text.Component.text("§8Seul §c" + playerName + " §8peut l'ouvrir"));
        meta.lore(lore);

        // PersistentDataContainer — stocker l'UUID propriétaire
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(ownerKey, PersistentDataType.STRING, ownerUuid.toString());
        pdc.set(deathBagKey, PersistentDataType.BYTE, (byte) 1);

        // Sérialiser l'inventaire comme bytes
        byte[] serialized = serializeInventory(contents, armor);
        pdc.set(inventoryKey, PersistentDataType.BYTE_ARRAY, serialized);

        bag.setItemMeta(meta);
        return bag;
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (!item.hasItemMeta()) return;

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(deathBagKey, PersistentDataType.BYTE)) return;

        // C'est un sac de mort — vérifier le propriétaire
        String ownerStr = pdc.get(ownerKey, PersistentDataType.STRING);
        if (ownerStr == null || !ownerStr.equals(player.getUniqueId().toString())) {
            event.setCancelled(true);
            player.sendMessage("§cCe sac ne t'appartient pas !");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(deathBagKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Vérifier propriétaire
        String ownerStr = pdc.get(ownerKey, PersistentDataType.STRING);
        if (ownerStr == null || !ownerStr.equals(uuid.toString())) {
            player.sendMessage("§cCe sac ne t'appartient pas !");
            return;
        }

        // Récupérer l'inventaire
        byte[] serialized = pdc.get(inventoryKey, PersistentDataType.BYTE_ARRAY);
        if (serialized != null) {
            ItemStack[][] inv = deserializeInventory(serialized);
            ItemStack[] contents = inv[0];
            ItemStack[] armor = inv[1];

            // Restaurer le contenu
            for (ItemStack stack : contents) {
                if (stack != null && stack.getType() != Material.AIR) {
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                    // Drop les items qui ne rentrent pas
                    for (ItemStack leftover : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
            }

            // Restaurer l'armure
            if (armor != null) {
                ItemStack[] currentArmor = player.getInventory().getArmorContents();
                for (int i = 0; i < armor.length && i < currentArmor.length; i++) {
                    if (armor[i] != null && armor[i].getType() != Material.AIR) {
                        if (currentArmor[i] == null || currentArmor[i].getType() == Material.AIR) {
                            currentArmor[i] = armor[i];
                        } else {
                            player.getInventory().addItem(armor[i]);
                        }
                    }
                }
                player.getInventory().setArmorContents(currentArmor);
            }
        }

        // Retirer le sac de la main
        player.getInventory().setItemInMainHand(null);
        player.sendMessage("§a✦ Inventaire récupéré !");
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

        // Annuler le beacon si actif
        BukkitTask beacon = activeBeacons.remove(uuid);
        if (beacon != null) {
            beacon.cancel();
        }

        // Effacer la mort en DB
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET last_death_x = NULL, last_death_y = NULL, last_death_z = NULL, last_death_world = NULL WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur effacement mort: " + e.getMessage());
            }
        });
    }

    // ─── Sérialisation inventaire ───────────────────────────────

    private byte[] serializeInventory(ItemStack[] contents, ItemStack[] armor) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            org.bukkit.util.io.BukkitObjectOutputStream boos = new org.bukkit.util.io.BukkitObjectOutputStream(baos);

            // Écrire le contenu principal
            boos.writeInt(contents.length);
            for (ItemStack item : contents) {
                boos.writeObject(item);
            }

            // Écrire l'armure
            boos.writeInt(armor.length);
            for (ItemStack item : armor) {
                boos.writeObject(item);
            }

            boos.close();
            return baos.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur sérialisation inventaire: " + e.getMessage());
            return new byte[0];
        }
    }

    private ItemStack[][] deserializeInventory(byte[] data) {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
            org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(bais);

            int contentsLen = bois.readInt();
            ItemStack[] contents = new ItemStack[contentsLen];
            for (int i = 0; i < contentsLen; i++) {
                contents[i] = (ItemStack) bois.readObject();
            }

            int armorLen = bois.readInt();
            ItemStack[] armor = new ItemStack[armorLen];
            for (int i = 0; i < armorLen; i++) {
                armor[i] = (ItemStack) bois.readObject();
            }

            bois.close();
            return new ItemStack[][] { contents, armor };
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur désérialisation inventaire: " + e.getMessage());
            return new ItemStack[][] { new ItemStack[0], new ItemStack[0] };
        }
    }

    // ─── Beacon de Particules ───────────────────────────────────

    private void startDeathBeacon(UUID uuid, Location loc) {
        // Annuler un ancien beacon si présent
        BukkitTask old = activeBeacons.remove(uuid);
        if (old != null) old.cancel();

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;
            @Override
            public void run() {
                if (elapsed >= beaconDuration) {
                    cancel();
                    activeBeacons.remove(uuid);
                    return;
                }
                World world = loc.getWorld();
                if (world == null) {
                    cancel();
                    return;
                }
                // Colonne de particules SOUL_FIRE_FLAME montant de Y à Y+30
                for (int y = 0; y < 30; y++) {
                    Location particleLoc = loc.clone().add(0.5, y, 0.5);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc, 1, 0.1, 0.1, 0.1, 0);
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 20L); // Chaque seconde

        activeBeacons.put(uuid, task);
    }

    // ─── ArmorStand marker ──────────────────────────────────────

    private void spawnDeathMarker(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        ArmorStand stand = world.spawn(loc.clone().add(0, 1.5, 0), ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setSmall(true);
            as.setMarker(true);
            as.customName(net.kyori.adventure.text.Component.text("§c§l☠"));
            as.setCustomNameVisible(true);
            as.setInvulnerable(true);
        });

        // Supprimer après 5 minutes
        new BukkitRunnable() {
            @Override
            public void run() {
                if (stand.isValid()) {
                    stand.remove();
                }
            }
        }.runTaskLater(plugin, 20L * 300); // 300 secondes = 5 min
    }

    // ─── SQLite ─────────────────────────────────────────────────

    private void saveDeathLocation(UUID uuid, Location loc) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET last_death_x = ?, last_death_y = ?, last_death_z = ?, last_death_world = ? WHERE uuid = ?")) {
                ps.setDouble(1, loc.getX());
                ps.setDouble(2, loc.getY());
                ps.setDouble(3, loc.getZ());
                ps.setString(4, loc.getWorld().getName());
                ps.setString(5, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde death location: " + e.getMessage());
            }
        });
    }

    // ─── GUI /deaths ──────────────────────────────────────────────

    /**
     * Ouvre la GUI de mort pour le joueur (appelé par MenuModule ou commande).
     */
    public void openDeathGui(Player player) {
        UUID uuid = player.getUniqueId();

        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT last_death_x, last_death_y, last_death_z, last_death_world, deaths FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getString("last_death_world") != null) {
                    return new DeathInfo(
                            rs.getDouble("last_death_x"),
                            rs.getDouble("last_death_y"),
                            rs.getDouble("last_death_z"),
                            rs.getString("last_death_world"),
                            rs.getInt("deaths")
                    );
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur lecture death: " + e.getMessage());
            }
            return null;
        }).thenAccept(info -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                buildDeathGui(player, info);
            });
        });
    }

    private void buildDeathGui(Player player, DeathInfo info) {
        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §c☠ Historique des Morts §8✦"))
                .rows(4)
                .disableAllInteractions()
                .create();

        // Remplissage noir
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Bordure haute gradient rouge
        gui.setItem(0, glassItem(Material.RED_STAINED_GLASS_PANE));
        gui.setItem(1, glassItem(Material.ORANGE_STAINED_GLASS_PANE));
        gui.setItem(2, glassItem(Material.YELLOW_STAINED_GLASS_PANE));
        gui.setItem(3, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(4, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(5, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(6, glassItem(Material.YELLOW_STAINED_GLASS_PANE));
        gui.setItem(7, glassItem(Material.ORANGE_STAINED_GLASS_PANE));
        gui.setItem(8, glassItem(Material.RED_STAINED_GLASS_PANE));

        if (info != null) {
            // Slot 13 : Dernière mort
            int dx = (int) info.x, dy = (int) info.y, dz = (int) info.z;
            gui.setItem(13, ItemBuilder.from(Material.SKELETON_SKULL)
                    .name(Component.text("§c§l☠ Dernière Mort"))
                    .lore(
                            Component.text("§7─────────────────────"),
                            Component.text("§7Position : §bX:" + dx + " Y:" + dy + " Z:" + dz),
                            Component.text("§7Monde : §f" + info.world),
                            Component.text("§7Distance : §e" + calculateDistance(player, info) + " blocs"),
                            Component.text("§7─────────────────────"),
                            Component.text("§8Récupère ton sac de mort"),
                            Component.text("§8à ces coordonnées !")
                    )
                    .asGuiItem());

            // Slot 15 : Statistiques
            gui.setItem(15, ItemBuilder.from(Material.PAPER)
                    .name(Component.text("§e📊 Statistiques"))
                    .lore(
                            Component.text("§7─────────────────────"),
                            Component.text("§7Morts totales : §c" + info.totalDeaths),
                            Component.text("§7Rang de survie : §f" + getSurvivalRank(info.totalDeaths)),
                            Component.text("§7─────────────────────")
                    )
                    .asGuiItem());

            // Slot 22 : Boussole vers la mort
            boolean sameWorld = player.getWorld().getName().equals(info.world);
            if (sameWorld) {
                gui.setItem(22, ItemBuilder.from(Material.RECOVERY_COMPASS)
                        .name(Component.text("§b🧭 Direction"))
                        .lore(
                                Component.text("§7Ta mort est à §e" + calculateDistance(player, info) + " blocs"),
                                Component.text("§7Direction : §f" + getDirection(player, info))
                        )
                        .asGuiItem());
            } else {
                gui.setItem(22, ItemBuilder.from(Material.BARRIER)
                        .name(Component.text("§7Autre dimension"))
                        .lore(Component.text("§8Ton sac est dans : §f" + info.world))
                        .asGuiItem());
            }
        } else {
            // Pas de mort enregistrée
            gui.setItem(13, ItemBuilder.from(Material.LIME_DYE)
                    .name(Component.text("§a✓ Aucune mort !"))
                    .lore(
                            Component.text("§7Tu n'as aucune mort enregistrée."),
                            Component.text("§7Continue comme ça !")
                    )
                    .asGuiItem());
        }

        // Bordure basse gradient rouge
        gui.setItem(27, glassItem(Material.RED_STAINED_GLASS_PANE));
        gui.setItem(28, glassItem(Material.ORANGE_STAINED_GLASS_PANE));
        gui.setItem(29, glassItem(Material.YELLOW_STAINED_GLASS_PANE));
        gui.setItem(30, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));

        // Slot 31 : Retour au menu
        gui.setItem(31, ItemBuilder.from(Material.ARROW)
                .name(Component.text("§7← Retour au Menu"))
                .asGuiItem(e -> {
                    MenuModule menuModule = plugin.getModule(MenuModule.class);
                    if (menuModule != null) menuModule.openMainMenu(player);
                }));

        gui.setItem(32, glassItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(33, glassItem(Material.YELLOW_STAINED_GLASS_PANE));
        gui.setItem(34, glassItem(Material.ORANGE_STAINED_GLASS_PANE));
        gui.setItem(35, glassItem(Material.RED_STAINED_GLASS_PANE));

        player.playSound(player.getLocation(), Sound.ENTITY_SKELETON_AMBIENT, 0.5f, 0.8f);
        gui.open(player);
    }

    private GuiItem glassItem(Material mat) {
        return ItemBuilder.from(mat).name(Component.text(" ")).asGuiItem();
    }

    private int calculateDistance(Player player, DeathInfo info) {
        if (!player.getWorld().getName().equals(info.world)) return -1;
        double dx = player.getLocation().getX() - info.x;
        double dz = player.getLocation().getZ() - info.z;
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    private String getDirection(Player player, DeathInfo info) {
        double dx = info.x - player.getLocation().getX();
        double dz = info.z - player.getLocation().getZ();
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) angle += 360;
        if (angle < 22.5 || angle >= 337.5) return "Sud";
        if (angle < 67.5) return "Sud-Ouest";
        if (angle < 112.5) return "Ouest";
        if (angle < 157.5) return "Nord-Ouest";
        if (angle < 202.5) return "Nord";
        if (angle < 247.5) return "Nord-Est";
        if (angle < 292.5) return "Est";
        return "Sud-Est";
    }

    private String getSurvivalRank(int deaths) {
        if (deaths == 0) return "§a§lImmortel";
        if (deaths <= 3) return "§2Survivant";
        if (deaths <= 10) return "§eAventurier";
        if (deaths <= 25) return "§6Téméraire";
        if (deaths <= 50) return "§cImprudent";
        return "§4§lTouriste";
    }

    // ─── Commande /deaths ───────────────────────────────────────

    private class DeathsCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            openDeathGui(player);
            return true;
        }
    }

    private record DeathInfo(double x, double y, double z, String world, int totalDeaths) {}
}
