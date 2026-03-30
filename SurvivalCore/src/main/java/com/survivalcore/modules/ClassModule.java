package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module Classes — Guerrier, Archer, Mage.
 * Inspiré MMOCore : architecture de capacités actives + cooldown.
 */
public class ClassModule implements CoreModule, Listener {

    private SurvivalCore plugin;
    private NamespacedKey grimoireKey;

    // Cache joueur → données de classe
    private final Map<UUID, ClassData> playerClasses = new ConcurrentHashMap<>();
    // Cooldowns : UUID → (ability_index → expiry timestamp)
    private final Map<UUID, Map<Integer, Long>> cooldowns = new ConcurrentHashMap<>();
    // Joueurs avec Eagle Eye actif
    private final Set<UUID> eagleEyeActive = ConcurrentHashMap.newKeySet();

    private YamlConfiguration classesConfig;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        this.grimoireKey = new NamespacedKey(plugin, "grimoire");

        loadClassesConfig();

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerClass(p.getUniqueId());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("classe").setExecutor(new ClassCommand());

        plugin.getLogger().info("Class module enabled.");
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, ClassData> entry : playerClasses.entrySet()) {
            savePlayerClassSync(entry.getKey(), entry.getValue());
        }
        plugin.getLogger().info("Class module disabled.");
    }

    @Override
    public String getName() {
        return "Class";
    }

    // ─── Config ─────────────────────────────────────────────────

    private void loadClassesConfig() {
        File f = new File(plugin.getDataFolder(), "data/classes.yml");
        if (!f.exists()) plugin.saveResource("data/classes.yml", false);
        classesConfig = YamlConfiguration.loadConfiguration(f);
    }

    public List<String> getClassIds() {
        List<String> ids = new ArrayList<>();
        for (String key : classesConfig.getKeys(false)) {
            ids.add(key);
        }
        return ids;
    }

    public String getClassDisplayName(String classId) {
        return classesConfig.getString(classId + ".display-name", classId);
    }

    public String getPlayerClassId(UUID uuid) {
        ClassData data = playerClasses.get(uuid);
        return data != null ? data.classId : "NONE";
    }

    // ─── Grimoire Item ──────────────────────────────────────────

    public ItemStack createGrimoire() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("§d§lGrimoire de Classe"));
        meta.lore(List.of(
                Component.text("§7Clic droit pour activer tes capacités"),
                Component.text("§8Nécessite une classe active")
        ));
        meta.getPersistentDataContainer().set(grimoireKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ─── Capacités Passives ─────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Guerrier : +20% dégâts corps à corps
        if (event.getDamager() instanceof Player player) {
            String classId = getPlayerClassId(player.getUniqueId());
            if (classId.equals("WARRIOR")) {
                // Vérifier que c'est du corps à corps (pas un projectile)
                event.setDamage(event.getDamage() * 1.20);
            }
        }

        // Archer : 15% chance de ralentir avec les flèches
        if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player player) {
            String classId = getPlayerClassId(player.getUniqueId());
            if (classId.equals("ARCHER") && event.getEntity() instanceof LivingEntity target) {
                if (Math.random() < 0.15) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1)); // 3s Slowness II
                }
            }
        }

        // Archer Eagle Eye : dégâts x2
        if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player player) {
            if (eagleEyeActive.contains(player.getUniqueId())) {
                event.setDamage(event.getDamage() * 2.0);
            }
        }
    }

    // Mage passif (rayon potion +50%) — nécessite un listener sur PotionSplashEvent
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPotionSplash(org.bukkit.event.entity.PotionSplashEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            String classId = getPlayerClassId(player.getUniqueId());
            if (classId.equals("MAGE")) {
                // Augmenter l'intensité pour les entités à la limite du rayon
                for (LivingEntity affected : event.getAffectedEntities()) {
                    double intensity = event.getIntensity(affected);
                    // Boost les entités qui auraient reçu un effet réduit
                    event.setIntensity(affected, Math.min(1.0, intensity * 1.5));
                }
            }
        }
    }

    // ─── Capacités Actives (via Grimoire clic droit) ────────────

    @EventHandler
    public void onGrimoireUse(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(grimoireKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String classId = getPlayerClassId(uuid);

        if (classId.equals("NONE")) {
            player.sendMessage("§cTu n'as pas de classe ! Utilise /classe choose");
            return;
        }

        // Ouvrir le menu des capacités (GUI simple)
        openAbilityMenu(player, classId);
    }

    // ─── Class Selector GUI ─────────────────────────────────────

    public void openClassSelector(Player player) {
        UUID uuid = player.getUniqueId();
        ClassData currentData = playerClasses.getOrDefault(uuid, new ClassData("NONE", 0));
        long now = System.currentTimeMillis();
        long sevenDays = 7L * 24 * 60 * 60 * 1000;

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §dChoisir ta Classe §8✦"))
                .rows(5)
                .disableAllInteractions()
                .create();

        // Black glass border (all slots initially)
        GuiItem border = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(border);

        // Row 1 Slot 4 (slot 4): Info item
        gui.setItem(4, ItemBuilder.from(Material.ENCHANTED_BOOK)
                .name(Component.text("§d§lChoisis ta Classe"))
                .lore(
                        Component.text("§7Chaque classe a des capacités uniques."),
                        Component.text("§7Tu peux changer de classe tous les §e7 jours§7.")
                )
                .asGuiItem());

        // Row 2-3: Class cards (11, 13, 15)
        List<String> classes = getClassIds();

        if (classes.contains("WARRIOR")) {
            addClassCard(gui, player, uuid, currentData, now, sevenDays, "WARRIOR", 11);
        }
        if (classes.contains("ARCHER")) {
            addClassCard(gui, player, uuid, currentData, now, sevenDays, "ARCHER", 13);
        }
        if (classes.contains("MAGE")) {
            addClassCard(gui, player, uuid, currentData, now, sevenDays, "MAGE", 15);
        }

        // Row 4: Current class info bar (slots 10-16)
        String currentClassDisplay;
        String cooldownInfo;
        if (currentData.classId.equals("NONE")) {
            currentClassDisplay = "§7Aucune classe";
            cooldownInfo = "§aPas de classe actuellement";
        } else {
            currentClassDisplay = "§6" + getClassDisplayName(currentData.classId);
            long elapsed = now - currentData.lastChange;
            if (elapsed < sevenDays) {
                long remaining = (sevenDays - elapsed) / 1000 / 60 / 60 / 24;
                cooldownInfo = "§cProchaine classe dans : §e" + remaining + " jours";
            } else {
                cooldownInfo = "§aPrêt à changer";
            }
        }

        gui.setItem(19, ItemBuilder.from(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.text("§f§lClasse Actuelle"))
                .lore(
                        Component.text(currentClassDisplay),
                        Component.text(cooldownInfo)
                )
                .asGuiItem());

        // Row 5: Grimoire button at slot 40 (only if has a class)
        if (!currentData.classId.equals("NONE")) {
            gui.setItem(40, ItemBuilder.from(Material.BOOK)
                    .name(Component.text("§d§lGrimoire"))
                    .lore(Component.text("§7Clic pour accéder à tes capacités"))
                    .asGuiItem(event -> {
                        player.closeInventory();
                        openAbilityMenu(player, currentData.classId);
                    }));
        }

        // Play sound
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
        gui.open(player);
    }

    private void addClassCard(Gui gui, Player player, UUID uuid, ClassData currentData,
                               long now, long sevenDays, String classId, int slot) {
        Material icon = switch (classId) {
            case "WARRIOR" -> Material.IRON_SWORD;
            case "ARCHER" -> Material.BOW;
            case "MAGE" -> Material.BLAZE_ROD;
            default -> Material.BOOK;
        };

        List<Component> lore = new ArrayList<>();

        // Add passive ability
        String passiveName = classesConfig.getString(classId + ".passive-name", "Passive");
        String passiveDesc = classesConfig.getString(classId + ".passive-description", "");
        lore.add(Component.text("§6Passif : §f" + passiveName));
        lore.add(Component.text("§7" + passiveDesc));
        lore.add(Component.text(""));

        // Add active abilities
        String ability1Name = classesConfig.getString(classId + ".ability1.name", "Ability 1");
        String ability2Name = classesConfig.getString(classId + ".ability2.name", "Ability 2");
        lore.add(Component.text("§bCapacité 1 : §f" + ability1Name));
        lore.add(Component.text("§bCapacité 2 : §f" + ability2Name));

        // Determine state
        boolean isSelected = currentData.classId.equals(classId);
        boolean canChange = currentData.classId.equals("NONE") || (now - currentData.lastChange) >= sevenDays;
        long elapsed = now - currentData.lastChange;
        boolean isOnCooldown = !currentData.classId.equals("NONE") && !currentData.classId.equals(classId) && (elapsed < sevenDays);

        if (isSelected) {
            lore.add(Component.text(""));
            lore.add(Component.text("§a✓ Ta classe actuelle"));
        } else if (isOnCooldown) {
            long remaining = (sevenDays - elapsed) / 1000 / 60 / 60 / 24;
            lore.add(Component.text(""));
            lore.add(Component.text("§cEn cooldown : §e" + remaining + " jours restants"));
        } else {
            lore.add(Component.text(""));
            lore.add(Component.text("§a▶ Clic pour sélectionner"));
        }

        // Border/highlight logic
        Material displayMat = icon;
        if (isSelected) {
            displayMat = Material.LIME_STAINED_GLASS_PANE;
        } else if (isOnCooldown) {
            displayMat = Material.RED_STAINED_GLASS_PANE;
        }

        gui.setItem(slot, ItemBuilder.from(displayMat)
                .name(Component.text((isSelected ? "§a" : "§f") + "§l" + getClassDisplayName(classId)))
                .lore(lore)
                .asGuiItem(event -> {
                    if (isSelected) {
                        player.sendMessage("§7Tu as déjà cette classe !");
                        return;
                    }
                    if (isOnCooldown) {
                        long remaining = (sevenDays - elapsed) / 1000 / 60 / 60 / 24;
                        player.sendMessage("§cTu dois attendre §e" + remaining + " jours §cavant de changer de classe.");
                        return;
                    }

                    // Select the class
                    if (setPlayerClass(uuid, classId)) {
                        player.sendMessage("§a✦ Tu es maintenant §f" + getClassDisplayName(classId) + " §a!");
                        player.getInventory().addItem(createGrimoire());
                        player.sendMessage("§7Tu as reçu un §dGrimoire de Classe §7!");
                        player.closeInventory();
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                    } else {
                        player.sendMessage("§cErreur lors de la sélection de la classe.");
                    }
                }));
    }

    private void openAbilityMenu(Player player, String classId) {
        UUID uuid = player.getUniqueId();

        Gui gui = Gui.gui()
                .title(Component.text("§8✦ §bCapacités — " + getClassDisplayName(classId) + " §8✦"))
                .rows(3)
                .disableAllInteractions()
                .create();

        // Fond noir
        GuiItem filler = ItemBuilder.from(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.text(" ")).asGuiItem();
        gui.getFiller().fill(filler);

        // Icône de classe au centre
        Material classIcon = switch (classId) {
            case "WARRIOR" -> Material.IRON_SWORD;
            case "ARCHER" -> Material.BOW;
            case "MAGE" -> Material.BLAZE_ROD;
            default -> Material.BOOK;
        };
        gui.setItem(13, ItemBuilder.from(classIcon)
                .name(Component.text("§b§l" + getClassDisplayName(classId)))
                .lore(Component.text("§7Sélectionne une capacité"))
                .asGuiItem());

        // Ability 1 — slot 11
        String name1 = classesConfig.getString(classId + ".ability1.name", "Capacité 1");
        String desc1 = classesConfig.getString(classId + ".ability1.description", "");
        int cd1 = classesConfig.getInt(classId + ".ability1.cooldown", 30);
        long remaining1 = getRemainingCooldown(uuid, 1);

        Material mat1 = remaining1 > 0 ? Material.GRAY_DYE : Material.LIME_DYE;
        List<Component> lore1 = new java.util.ArrayList<>();
        lore1.add(Component.text("§7" + desc1));
        lore1.add(Component.text(""));
        if (remaining1 > 0) {
            lore1.add(Component.text("§c⏳ Cooldown: " + remaining1 + "s"));
        } else {
            lore1.add(Component.text("§eCooldown: " + cd1 + "s"));
            lore1.add(Component.text("§a▶ Clic pour activer"));
        }

        gui.setItem(11, ItemBuilder.from(mat1)
                .name(Component.text((remaining1 > 0 ? "§7" : "§a") + name1))
                .lore(lore1)
                .asGuiItem(event -> {
                    if (remaining1 <= 0) {
                        activateAbility(player, classId, 1);
                        player.closeInventory();
                    } else {
                        player.sendMessage("§cCapacité en cooldown ! " + remaining1 + "s restantes.");
                    }
                }));

        // Ability 2 — slot 15
        String name2 = classesConfig.getString(classId + ".ability2.name", "Capacité 2");
        String desc2 = classesConfig.getString(classId + ".ability2.description", "");
        int cd2 = classesConfig.getInt(classId + ".ability2.cooldown", 60);
        long remaining2 = getRemainingCooldown(uuid, 2);

        Material mat2 = remaining2 > 0 ? Material.GRAY_DYE : Material.LIME_DYE;
        List<Component> lore2 = new java.util.ArrayList<>();
        lore2.add(Component.text("§7" + desc2));
        lore2.add(Component.text(""));
        if (remaining2 > 0) {
            lore2.add(Component.text("§c⏳ Cooldown: " + remaining2 + "s"));
        } else {
            lore2.add(Component.text("§eCooldown: " + cd2 + "s"));
            lore2.add(Component.text("§a▶ Clic pour activer"));
        }

        gui.setItem(15, ItemBuilder.from(mat2)
                .name(Component.text((remaining2 > 0 ? "§7" : "§a") + name2))
                .lore(lore2)
                .asGuiItem(event -> {
                    if (remaining2 <= 0) {
                        activateAbility(player, classId, 2);
                        player.closeInventory();
                    } else {
                        player.sendMessage("§cCapacité en cooldown ! " + remaining2 + "s restantes.");
                    }
                }));

        gui.open(player);
    }

    private void activateAbility(Player player, String classId, int abilityIndex) {
        UUID uuid = player.getUniqueId();
        long remaining = getRemainingCooldown(uuid, abilityIndex);
        if (remaining > 0) {
            player.sendMessage("§cCapacité en cooldown ! " + remaining + "s restantes.");
            return;
        }

        String abilityKey = "ability" + abilityIndex;
        int cooldownSec = classesConfig.getInt(classId + "." + abilityKey + ".cooldown", 30);

        // Appliquer le cooldown
        cooldowns.computeIfAbsent(uuid, k -> new HashMap<>())
                .put(abilityIndex, System.currentTimeMillis() + (cooldownSec * 1000L));

        // Exécuter la capacité
        switch (classId) {
            case "WARRIOR" -> {
                if (abilityIndex == 1) warriorCharge(player);
                else warriorWarCry(player);
            }
            case "ARCHER" -> {
                if (abilityIndex == 1) archerArrowRain(player);
                else archerEagleEye(player);
            }
            case "MAGE" -> {
                if (abilityIndex == 1) mageFireball(player);
                else mageTeleport(player);
            }
        }
    }

    private long getRemainingCooldown(UUID uuid, int abilityIndex) {
        Map<Integer, Long> cd = cooldowns.get(uuid);
        if (cd == null) return 0;
        Long expiry = cd.get(abilityIndex);
        if (expiry == null) return 0;
        long remaining = (expiry - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    // ─── Guerrier ───────────────────────────────────────────────

    private void warriorCharge(Player player) {
        // Dash 5 blocs vers l'entité visée + knockback
        RayTraceResult ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getLocation().getDirection(), 10,
                e -> e instanceof LivingEntity && e != player
        );

        if (ray != null && ray.getHitEntity() instanceof LivingEntity target) {
            // Téléporter vers l'entité (5 blocs devant elle)
            Location targetLoc = target.getLocation();
            Vector dir = player.getLocation().toVector().subtract(targetLoc.toVector()).normalize();
            Location chargeTarget = targetLoc.add(dir.multiply(1.5));
            chargeTarget.setY(targetLoc.getY());
            player.teleport(chargeTarget);

            // Knockback
            Vector knockback = target.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(1.5).setY(0.4);
            target.setVelocity(knockback);
            target.damage(4.0, player);

            player.getWorld().spawnParticle(Particle.EXPLOSION, target.getLocation(), 5);
            player.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.8f);
        } else {
            // Dash vers l'avant si pas de cible
            Vector dir = player.getLocation().getDirection().normalize().multiply(5);
            Location dest = player.getLocation().add(dir);
            dest.setY(player.getWorld().getHighestBlockYAt(dest) + 1);
            player.teleport(dest);
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 10);
        }
        player.sendMessage("§c⚔ Charge !");
    }

    private void warriorWarCry(Player player) {
        // Strength II + Resistance I pendant 10s dans un rayon de 10 blocs
        Location loc = player.getLocation();
        player.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        player.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);

        for (Player nearby : Bukkit.getOnlinePlayers()) {
            if (nearby.getWorld().equals(player.getWorld()) && nearby.getLocation().distance(loc) <= 10) {
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1)); // 10s
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0)); // 10s
                nearby.sendMessage("§c§l⚔ " + player.getName() + " pousse un Cri de Guerre !");
            }
        }
    }

    // ─── Archer ─────────────────────────────────────────────────

    private void archerArrowRain(Player player) {
        // 5 flèches en éventail
        Location eyeLoc = player.getEyeLocation();
        Vector baseDir = eyeLoc.getDirection().normalize();

        for (int i = -2; i <= 2; i++) {
            Vector dir = baseDir.clone().rotateAroundY(Math.toRadians(i * 10));
            Arrow arrow = player.getWorld().spawn(eyeLoc.clone().add(dir), Arrow.class);
            arrow.setVelocity(dir.multiply(2.5));
            arrow.setShooter(player);
            arrow.setDamage(6.0);
        }

        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.8f);
        player.sendMessage("§a🏹 Pluie de Flèches !");
    }

    private void archerEagleEye(Player player) {
        UUID uuid = player.getUniqueId();
        eagleEyeActive.add(uuid);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 160, 1)); // Ralenti pendant l'effet
        player.sendMessage("§a§l👁 Oeil d'Aigle activé ! §7Dégâts x2 pendant 8 secondes.");
        player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 2.0f);

        // Désactiver après 8 secondes
        new BukkitRunnable() {
            @Override
            public void run() {
                eagleEyeActive.remove(uuid);
                if (player.isOnline()) {
                    player.sendMessage("§7👁 Oeil d'Aigle terminé.");
                }
            }
        }.runTaskLater(plugin, 160L); // 8s
    }

    // ─── Mage ───────────────────────────────────────────────────

    private void mageFireball(Player player) {
        Location eyeLoc = player.getEyeLocation();
        Vector dir = eyeLoc.getDirection().normalize();

        Fireball fireball = player.getWorld().spawn(eyeLoc.add(dir.multiply(2)), Fireball.class);
        fireball.setDirection(dir.multiply(0.5));
        fireball.setShooter(player);
        fireball.setYield(0); // Pas de destruction de terrain
        fireball.setIsIncendiary(true);

        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f);
        player.sendMessage("§d🔥 Boule de Feu !");
    }

    private void mageTeleport(Player player) {
        // Téléportation là où le joueur regarde (max 30 blocs)
        RayTraceResult ray = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(), player.getLocation().getDirection(), 30,
                FluidCollisionMode.NEVER, true
        );

        Location dest;
        if (ray != null && ray.getHitBlock() != null) {
            dest = ray.getHitBlock().getLocation().add(0.5, 1, 0.5);
        } else {
            // Max distance
            dest = player.getEyeLocation().add(player.getLocation().getDirection().multiply(30));
        }

        // Particules de départ
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation(), 50, 0.5, 1, 0.5);

        // Téléporter
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());
        player.teleport(dest);

        // Particules d'arrivée
        player.getWorld().spawnParticle(Particle.PORTAL, dest, 50, 0.5, 1, 0.5);
        player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.sendMessage("§d✨ Téléportation !");
    }

    // ─── Listeners join/quit ────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        loadPlayerClass(uuid);

        // Vérifier après chargement si le joueur manque son grimoire
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            ClassData data = playerClasses.get(uuid);
            if (data == null || data.classId.equals("NONE")) return;

            // Chercher un grimoire dans tout l'inventaire
            boolean hasGrimoire = false;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.hasItemMeta() &&
                        item.getItemMeta().getPersistentDataContainer().has(grimoireKey, PersistentDataType.BYTE)) {
                    hasGrimoire = true;
                    break;
                }
            }

            if (!hasGrimoire) {
                player.getInventory().addItem(createGrimoire());
                player.sendMessage("§d✦ Ton §lGrimoire de Classe §r§d a été restauré dans ton inventaire.");
            }
        }, 80L); // 4s après le join pour laisser le temps à loadPlayerClass
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ClassData data = playerClasses.remove(uuid);
        if (data != null) savePlayerClassAsync(uuid, data);
        cooldowns.remove(uuid);
        eagleEyeActive.remove(uuid);
    }

    // ─── Persistance ────────────────────────────────────────────

    private void loadPlayerClass(UUID uuid) {
        plugin.getDatabaseManager().executeAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT class, last_class_change FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new ClassData(rs.getString("class"), rs.getLong("last_class_change"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur chargement class: " + e.getMessage());
            }
            return new ClassData("NONE", 0);
        }).thenAccept(data -> playerClasses.put(uuid, data));
    }

    private void savePlayerClassAsync(UUID uuid, ClassData data) {
        plugin.getDatabaseManager().runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET class = ?, last_class_change = ? WHERE uuid = ?")) {
                ps.setString(1, data.classId);
                ps.setLong(2, data.lastChange);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Erreur sauvegarde class: " + e.getMessage());
            }
        });
    }

    private void savePlayerClassSync(UUID uuid, ClassData data) {
        try {
            var conn = plugin.getDatabaseManager().getConnection();
            if (conn != null && !conn.isClosed()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET class = ?, last_class_change = ? WHERE uuid = ?")) {
                    ps.setString(1, data.classId);
                    ps.setLong(2, data.lastChange);
                    ps.setString(3, uuid.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Erreur sauvegarde sync class: " + e.getMessage());
        }
    }

    public boolean setPlayerClass(UUID uuid, String classId) {
        ClassData data = playerClasses.computeIfAbsent(uuid, k -> new ClassData("NONE", 0));
        long now = System.currentTimeMillis();
        long sevenDays = 7L * 24 * 60 * 60 * 1000;

        if (!data.classId.equals("NONE") && (now - data.lastChange) < sevenDays) {
            return false;
        }

        data.classId = classId;
        data.lastChange = now;
        playerClasses.put(uuid, data);
        savePlayerClassAsync(uuid, data);
        return true;
    }

    // ─── Commande /classe ───────────────────────────────────────

    private class ClassCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCommande joueur uniquement.");
                return true;
            }
            UUID uuid = player.getUniqueId();

            if (args.length == 0) {
                // Open class selector GUI
                openClassSelector(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("choose") && args.length >= 2) {
                String classId = args[1].toUpperCase();
                if (!getClassIds().contains(classId)) {
                    player.sendMessage("§cClasse invalide. Disponibles : §f" + String.join(", ", getClassIds()));
                    return true;
                }
                if (!setPlayerClass(uuid, classId)) {
                    player.sendMessage("§cTu dois attendre 7 jours avant de changer de classe.");
                    return true;
                }
                player.sendMessage("§a✦ Tu es maintenant §f" + getClassDisplayName(classId) + " §a!");
                // Donner le grimoire
                player.getInventory().addItem(createGrimoire());
                player.sendMessage("§7Tu as reçu un §dGrimoire de Classe §7!");
                return true;
            }

            if (args[0].equalsIgnoreCase("grimoire")) {
                player.getInventory().addItem(createGrimoire());
                player.sendMessage("§7Tu as reçu un §dGrimoire de Classe §7!");
                return true;
            }

            player.sendMessage("§cUsage : /classe [choose <classe>|grimoire]");
            return true;
        }
    }

    // ─── Data ───────────────────────────────────────────────────

    public static class ClassData {
        public String classId;
        public long lastChange;

        public ClassData(String classId, long lastChange) {
            this.classId = classId;
            this.lastChange = lastChange;
        }
    }
}
