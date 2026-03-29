package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Claims — Protection de terrain par chunks.
 * Débloqué via les paliers de métier (Niv.10 = 1 claim, Niv.20 = 2, etc.)
 * Chaque claim = 1 chunk (16x16 blocs).
 * Le propriétaire peut ajouter des membres (amis) qui ont les droits.
 *
 * Commandes :
 *   /claim           — claim le chunk actuel
 *   /claim info       — info du chunk actuel
 *   /claim list       — GUI de tes claims
 *   /claim add <nom>  — ajouter un membre
 *   /claim remove <nom> — retirer un membre
 *   /claim unclaim    — abandonner le chunk actuel
 */
public class ClaimModule implements CoreModule, Listener {

    private SurvivalCore plugin;

    // Cache : "world:chunkX:chunkZ" → ClaimData
    private final Map<String, ClaimData> claimCache = new ConcurrentHashMap<>();
    // Cache : UUID → nombre de claims possédés
    private final Map<UUID, Integer> playerClaimCount = new ConcurrentHashMap<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;

        // Charger tous les claims depuis la DB
        loadAllClaims();

        plugin.getCommand("claim").setExecutor(new ClaimCommand());
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("Claim module enabled.");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("Claim module disabled.");
    }

    @Override
    public String getName() {
        return "Claim";
    }

    // ─── API publique ────────────────────────────────────────────

    /**
     * Nombre max de claims qu'un joueur peut avoir, basé sur les paliers de métier.
     * Niv < 10 : 0 claims
     * Niv 10-19 : 1 claim
     * Niv 20-29 : 3 claims (1+2)
     * Niv 30-49 : 6 claims (1+2+3)
     * Niv 50+   : 11 claims (1+2+3+5)
     */
    public int getMaxClaims(UUID uuid) {
        JobModule jobModule = plugin.getModule(JobModule.class);
        if (jobModule == null) return 0;
        int level = jobModule.getPlayerJobLevel(uuid);

        if (level >= 50) return 11;
        if (level >= 30) return 6;
        if (level >= 20) return 3;
        if (level >= 10) return 1;
        return 0;
    }

    public int getClaimCount(UUID uuid) {
        return playerClaimCount.getOrDefault(uuid, 0);
    }

    public ClaimData getClaimAt(Chunk chunk) {
        return claimCache.get(chunkKey(chunk));
    }

    public ClaimData getClaimAt(String world, int chunkX, int chunkZ) {
        return claimCache.get(world + ":" + chunkX + ":" + chunkZ);
    }

    public boolean isProtected(Block block) {
        return claimCache.containsKey(chunkKey(block.getChunk()));
    }

    public boolean canBuild(Player player, Block block) {
        ClaimData claim = claimCache.get(chunkKey(block.getChunk()));
        if (claim == null) return true; // Pas protégé
        if (claim.ownerUuid.equals(player.getUniqueId())) return true;
        return claim.members.contains(player.getUniqueId());
    }

    // ─── Claim / Unclaim ────────────────────────────────────────

    public boolean claimChunk(Player player) {
        UUID uuid = player.getUniqueId();
        Chunk chunk = player.getLocation().getChunk();
        String key = chunkKey(chunk);

        // Vérifier si déjà claim
        if (claimCache.containsKey(key)) {
            player.sendMessage("§cCe chunk est déjà protégé !");
            return false;
        }

        // Vérifier les permissions
        int max = getMaxClaims(uuid);
        int current = getClaimCount(uuid);
        if (max <= 0) {
            player.sendMessage("§cTu dois atteindre le §eNiveau 10 §cde ton métier pour débloquer les claims !");
            player.sendMessage("§7Utilise §e/job §7pour voir ton niveau.");
            return false;
        }
        if (current >= max) {
            player.sendMessage("§cTu as atteint ta limite de claims ! (" + current + "/" + max + ")");
            player.sendMessage("§7Monte de niveau dans ton métier pour en débloquer plus.");
            return false;
        }

        // Créer le claim
        ClaimData claim = new ClaimData(uuid, chunk.getWorld().getName(),
                chunk.getX(), chunk.getZ(), new HashSet<>());
        claimCache.put(key, claim);
        playerClaimCount.merge(uuid, 1, Integer::sum);

        // Persister en DB
        saveClaimAsync(claim);

        // Effet visuel : particules aux bordures du chunk
        showClaimBorder(player, chunk);

        player.sendMessage("§a✦ Chunk protégé ! §7(" + (current + 1) + "/" + max + " claims)");
        player.sendMessage("§7Position : §bChunk [" + chunk.getX() + ", " + chunk.getZ() + "] §7dans §f" + chunk.getWorld().getName());
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
        return true;
    }

    public boolean unclaimChunk(Player player) {
        UUID uuid = player.getUniqueId();
        Chunk chunk = player.getLocation().getChunk();
        String key = chunkKey(chunk);

        ClaimData claim = claimCache.get(key);
        if (claim == null) {
            player.sendMessage("§cCe chunk n'est pas protégé.");
            return false;
        }
        if (!claim.ownerUuid.equals(uuid) && !player.hasPermission("survival.admin")) {
            player.sendMessage("§cTu ne peux pas supprimer la protection d'un autre joueur.");
            return false;
        }

        claimCache.remove(key);
        playerClaimCount.merge(claim.ownerUuid, -1, Integer::sum);

        // Supprimer de la DB
        deleteClaimAsync(claim);

        player.sendMessage("§e✦ Protection du chunk retirée.");
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.8f);
        return true;
    }

    // ─── Protection Listeners ───────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c✦ Ce terrain est protégé par un claim !");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c✦ Ce terrain est protégé par un claim !");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        Material type = block.getType();

        // Protéger coffres, portes, leviers etc.
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST
                || type == Material.FURNACE || type == Material.BLAST_FURNACE
                || type == Material.SMOKER || type == Material.BARREL
                || type == Material.HOPPER || type == Material.DROPPER
                || type == Material.DISPENSER || type == Material.BREWING_STAND
                || type.name().contains("DOOR") || type.name().contains("GATE")
                || type.name().contains("BUTTON") || type == Material.LEVER) {

            if (!canBuild(event.getPlayer(), block)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§c✦ Ce terrain est protégé par un claim !");
            }
        }
    }

    // ─── GUI Liste de claims ────────────────────────────────────

    public void openClaimList(Player player) {
        UUID uuid = player.getUniqueId();
        int max = getMaxClaims(uuid);
        int current = getClaimCount(uuid);

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §aMes Claims §8(" + current + "/" + max + ") §8✦"))
                .rows(5)
                .disableAllInteractions()
                .create();

        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Row 1: Gradient border + info
        gui.setItem(0, glassFill(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(1, glassFill(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(2, glassFill(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(3, glassFill(Material.LIGHT_GRAY_STAINED_GLASS_PANE));

        // Info header
        JobModule jobModule = plugin.getModule(JobModule.class);
        String jobInfo = jobModule != null ? jobModule.getJobDisplayName(jobModule.getPlayerJobId(uuid))
                + " Niv." + jobModule.getPlayerJobLevel(uuid) : "Aucun métier";

        gui.setItem(4, ItemBuilder.from(Material.GOLDEN_SHOVEL)
                .name(Component.text("§6§l✦ Système de Claims"))
                .lore(
                        Component.text("§7Métier : §e" + jobInfo),
                        Component.text("§7Claims : §a" + current + "§7/§a" + max),
                        Component.text(""),
                        Component.text("§7Paliers de déblocage :"),
                        Component.text("§8  Niv.10 → §a1 claim"),
                        Component.text("§8  Niv.20 → §a3 claims"),
                        Component.text("§8  Niv.30 → §a6 claims"),
                        Component.text("§8  Niv.50 → §a11 claims"),
                        Component.text(""),
                        Component.text("§eTape §b/claim §epour protéger ton chunk")
                )
                .asGuiItem());

        gui.setItem(5, glassFill(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(6, glassFill(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        gui.setItem(7, glassFill(Material.GRAY_STAINED_GLASS_PANE));
        gui.setItem(8, glassFill(Material.GRAY_STAINED_GLASS_PANE));

        // Row 2-4: Claims du joueur
        int slot = 10;
        for (Map.Entry<String, ClaimData> entry : claimCache.entrySet()) {
            ClaimData claim = entry.getValue();
            if (!claim.ownerUuid.equals(uuid)) continue;
            if (slot > 34) break;

            String worldName = claim.world;
            int cx = claim.chunkX;
            int cz = claim.chunkZ;
            int blockX = cx * 16;
            int blockZ = cz * 16;

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Monde : §f" + worldName));
            lore.add(Component.text("§7Zone : §bX:" + blockX + "→" + (blockX + 15) + " §7| §bZ:" + blockZ + "→" + (blockZ + 15)));
            lore.add(Component.text(""));
            lore.add(Component.text("§7Membres : §a" + claim.members.size()));
            if (!claim.members.isEmpty()) {
                for (UUID member : claim.members) {
                    String name = Bukkit.getOfflinePlayer(member).getName();
                    lore.add(Component.text("§8  - §f" + (name != null ? name : member.toString().substring(0, 8))));
                }
            }
            lore.add(Component.text(""));
            lore.add(Component.text("§c§oClic = téléporter en §b/claim unclaim"));

            Material icon = worldName.contains("nether") ? Material.NETHERRACK
                    : worldName.contains("end") ? Material.END_STONE
                    : Material.GRASS_BLOCK;

            gui.setItem(slot, ItemBuilder.from(icon)
                    .name(Component.text("§a✦ Claim [" + cx + ", " + cz + "]"))
                    .lore(lore)
                    .asGuiItem());

            slot++;
            if (slot == 17) slot = 19;
            if (slot == 26) slot = 28;
        }

        // Empty slots for available claims
        while (slot <= 34 && current < max) {
            gui.setItem(slot, ItemBuilder.from(Material.LIME_STAINED_GLASS_PANE)
                    .name(Component.text("§7§o+ Claim disponible"))
                    .lore(Component.text("§7Tape §b/claim §7dans un chunk libre"))
                    .asGuiItem());
            slot++;
            if (slot == 17) slot = 19;
            if (slot == 26) slot = 28;
            current++;
        }

        // Row 5: Bottom border
        for (int i = 36; i <= 44; i++) {
            gui.setItem(i, glassFill(i < 38 || i > 42 ? Material.GRAY_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
        }

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        gui.open(player);
    }

    private GuiItem glassFill(Material mat) {
        return ItemBuilder.from(mat).name(Component.text(" ")).asGuiItem();
    }

    // ─── Effets Visuels ─────────────────────────────────────────

    private void showClaimBorder(Player player, Chunk chunk) {
        World world = chunk.getWorld();
        int baseX = chunk.getX() * 16;
        int baseZ = chunk.getZ() * 16;
        int y = player.getLocation().getBlockY();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Spawner les particules sur le thread principal via des petits batches
            for (int i = 0; i < 16; i++) {
                final int fi = i;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Bordures Nord et Sud
                    world.spawnParticle(Particle.HAPPY_VILLAGER, baseX + fi + 0.5, y + 1, baseZ + 0.5, 1);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, baseX + fi + 0.5, y + 1, baseZ + 15.5, 1);
                    // Bordures Est et Ouest
                    world.spawnParticle(Particle.HAPPY_VILLAGER, baseX + 0.5, y + 1, baseZ + fi + 0.5, 1);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, baseX + 15.5, y + 1, baseZ + fi + 0.5, 1);
                });
            }
        });
    }

    // ─── Persistance ────────────────────────────────────────────

    private void loadAllClaims() {
        plugin.getDatabaseManager().executeAsync(conn -> {
            List<ClaimData> claims = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT owner_uuid, world, chunk_x, chunk_z, members FROM claims")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                    String membersStr = rs.getString("members");
                    Set<UUID> members = new HashSet<>();
                    if (membersStr != null && !membersStr.isEmpty()) {
                        for (String s : membersStr.split(",")) {
                            try { members.add(UUID.fromString(s.trim())); } catch (Exception ignored) {}
                        }
                    }
                    claims.add(new ClaimData(owner, rs.getString("world"),
                            rs.getInt("chunk_x"), rs.getInt("chunk_z"), members));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement claims: " + e.getMessage());
            }
            return claims;
        }).thenAccept(claims -> {
            Map<UUID, Integer> counts = new HashMap<>();
            for (ClaimData claim : claims) {
                String key = claim.world + ":" + claim.chunkX + ":" + claim.chunkZ;
                claimCache.put(key, claim);
                counts.merge(claim.ownerUuid, 1, Integer::sum);
            }
            playerClaimCount.putAll(counts);
            plugin.getLogger().info("Claims chargés : " + claims.size());
        });
    }

    private void saveClaimAsync(ClaimData claim) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO claims (owner_uuid, world, chunk_x, chunk_z, members) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, claim.ownerUuid.toString());
                ps.setString(2, claim.world);
                ps.setInt(3, claim.chunkX);
                ps.setInt(4, claim.chunkZ);
                ps.setString(5, serializeMembers(claim.members));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde claim: " + e.getMessage());
            }
        });
    }

    private void deleteClaimAsync(ClaimData claim) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                ps.setString(1, claim.world);
                ps.setInt(2, claim.chunkX);
                ps.setInt(3, claim.chunkZ);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur suppression claim: " + e.getMessage());
            }
        });
    }

    private String serializeMembers(Set<UUID> members) {
        if (members.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (UUID uuid : members) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(uuid.toString());
        }
        return sb.toString();
    }

    // ─── Utilitaires ────────────────────────────────────────────

    private String chunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    // ─── Commande /claim ────────────────────────────────────────

    private class ClaimCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            UUID uuid = player.getUniqueId();

            if (args.length == 0) {
                // Claim le chunk actuel
                claimChunk(player);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "info" -> {
                    Chunk chunk = player.getLocation().getChunk();
                    ClaimData claim = getClaimAt(chunk);
                    if (claim == null) {
                        player.sendMessage("§7Ce chunk n'est pas protégé.");
                    } else {
                        String ownerName = Bukkit.getOfflinePlayer(claim.ownerUuid).getName();
                        player.sendMessage("§6§l✦ Claim Info");
                        player.sendMessage("§7Propriétaire : §f" + (ownerName != null ? ownerName : "Inconnu"));
                        player.sendMessage("§7Chunk : §b[" + claim.chunkX + ", " + claim.chunkZ + "]");
                        player.sendMessage("§7Membres : §a" + claim.members.size());
                        for (UUID member : claim.members) {
                            String name = Bukkit.getOfflinePlayer(member).getName();
                            player.sendMessage("§8  - §f" + (name != null ? name : member.toString().substring(0, 8)));
                        }
                    }
                }
                case "list" -> openClaimList(player);
                case "unclaim" -> unclaimChunk(player);
                case "add" -> {
                    if (args.length < 2) {
                        player.sendMessage("§cUsage : /claim add <joueur>");
                        return true;
                    }
                    Chunk chunk = player.getLocation().getChunk();
                    ClaimData claim = getClaimAt(chunk);
                    if (claim == null || !claim.ownerUuid.equals(uuid)) {
                        player.sendMessage("§cTu n'es pas propriétaire de ce claim.");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        player.sendMessage("§cJoueur introuvable.");
                        return true;
                    }
                    claim.members.add(target.getUniqueId());
                    saveClaimAsync(claim);
                    player.sendMessage("§a✦ " + target.getName() + " ajouté à ton claim !");
                    target.sendMessage("§a✦ " + player.getName() + " t'a ajouté à son claim en [" + chunk.getX() + ", " + chunk.getZ() + "]");
                }
                case "remove" -> {
                    if (args.length < 2) {
                        player.sendMessage("§cUsage : /claim remove <joueur>");
                        return true;
                    }
                    Chunk chunk = player.getLocation().getChunk();
                    ClaimData claim = getClaimAt(chunk);
                    if (claim == null || !claim.ownerUuid.equals(uuid)) {
                        player.sendMessage("§cTu n'es pas propriétaire de ce claim.");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        player.sendMessage("§cJoueur introuvable.");
                        return true;
                    }
                    claim.members.remove(target.getUniqueId());
                    saveClaimAsync(claim);
                    player.sendMessage("§e✦ " + target.getName() + " retiré de ton claim.");
                }
                default -> {
                    player.sendMessage("§cUsage : /claim [info|list|unclaim|add <joueur>|remove <joueur>]");
                }
            }
            return true;
        }
    }

    // ─── Data ───────────────────────────────────────────────────

    public static class ClaimData {
        public final UUID ownerUuid;
        public final String world;
        public final int chunkX, chunkZ;
        public final Set<UUID> members;

        public ClaimData(UUID ownerUuid, String world, int chunkX, int chunkZ, Set<UUID> members) {
            this.ownerUuid = ownerUuid;
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.members = members;
        }
    }
}
