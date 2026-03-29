package com.survivalcore.modules;

import com.survivalcore.SurvivalCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Module Annonceur Dramatique — broadcasts stylisés + sons.
 * Tous les autres modules appellent ce module pour les annonces.
 */
public class AnnouncerModule implements CoreModule {

    private SurvivalCore plugin;

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;
        plugin.getLogger().info("Announcer module enabled.");
    }

    @Override
    public void onDisable() {
        plugin.getLogger().info("Announcer module disabled.");
    }

    @Override
    public String getName() {
        return "Announcer";
    }

    // ─── API d'annonce ──────────────────────────────────────────

    /**
     * Annonce standard : message dans le chat + son.
     */
    public void broadcast(String[] lines, Sound sound, float volume, float pitch) {
        String separator = "§8§m                                        ";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(separator);
            for (String line : lines) {
                p.sendMessage(line);
            }
            p.sendMessage(separator);
            if (sound != null) {
                p.playSound(p.getLocation(), sound, volume, pitch);
            }
        }
    }

    /**
     * Annonce avec titre au centre de l'écran.
     */
    public void broadcastTitle(String title, String subtitle, Sound sound) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500));
        Title t = Title.title(
                Component.text(title),
                Component.text(subtitle),
                times
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(t);
            if (sound != null) {
                p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            }
        }
    }

    // ─── Annonces prédéfinies ───────────────────────────────────

    /** Mort d'un joueur. */
    public void announcePlayerDeath(String playerName, String cause, int x, int y, int z, int deathCount) {
        broadcast(new String[]{
                "§4§l☠ §c" + playerName + " vient de mordre la poussière ! §4§l☠",
                "§7Cause : §e" + cause,
                "§7Position : §bX:" + x + " Y:" + y + " Z:" + z,
                "§7Morts totales : §c" + deathCount
        }, Sound.ENTITY_WITHER_DEATH, 0.5f, 0.8f);
    }

    /** Kill d'un mob rare / boss. */
    public void announceBossKill(String playerName, String mobName) {
        broadcast(new String[]{
                "§6§l⚔ VICTOIRE ! §e" + playerName + " a terrassé " + mobName + " !",
                "§7Butin légendaire obtenu !"
        }, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);

        broadcastTitle("§6⚔ VICTOIRE !", "§e" + playerName + " a terrassé " + mobName,
                Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    /** Quête journalière complétée. */
    public void announceQuestComplete(String playerName, String questName, int money, int xp) {
        broadcast(new String[]{
                "§a§l✦ QUÊTE ACCOMPLIE ! §7" + playerName + " a complété : §e" + questName,
                "§7Récompense : §6+" + money + " Éclats §7| §b+" + xp + " XP"
        }, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    /** Mission hebdo complétée par un joueur. */
    public void announceWeeklyProgress(String playerName, int completed, int total) {
        broadcast(new String[]{
                "§d§l★ MISSION HEBDO §7" + playerName + " a rempli sa part de la mission !",
                "§7" + completed + "/" + total + " joueurs ont contribué cette semaine."
        }, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
    }

    /** Mission hebdo complétée par tout le monde. */
    public void announceWeeklyAllComplete() {
        broadcast(new String[]{
                "§6§l✦✦✦ VICTOIRE D'ÉQUIPE ! ✦✦✦",
                "§eLa mission de la semaine est accomplie par toute l'équipe !",
                "§6Bonus x2 activé pour tous !"
        }, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        broadcastTitle("§6✦ VICTOIRE D'ÉQUIPE ✦", "§eBonus x2 activé !",
                Sound.UI_TOAST_CHALLENGE_COMPLETE);
    }

    /** Joueur atteint un palier de job. */
    public void announceJobMilestone(String playerName, String jobName, int level, String title) {
        broadcast(new String[]{
                "§b§l🔨 PALIER ! §7" + playerName + " atteint §e" + title + " §7en " + jobName + " (Niv.§b" + level + "§7) !"
        }, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);

        broadcastTitle("§b🔨 " + title, "§7" + playerName + " — " + jobName + " Niv." + level,
                Sound.ENTITY_PLAYER_LEVELUP);
    }

    /** Exploit légendaire. */
    public void announceExploit(String playerName, String exploit) {
        broadcast(new String[]{
                "§5§l🌟 EXPLOIT LÉGENDAIRE ! §7" + playerName + " a " + exploit + " en premier !"
        }, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f);

        broadcastTitle("§5🌟 EXPLOIT !", "§7" + playerName + " — " + exploit,
                Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    /** Arc terminé. */
    public void announceArcComplete(String playerName, String arcName) {
        broadcast(new String[]{
                "§5§l🌟 ARC TERMINÉ ! §7" + playerName + " a complété " + arcName + " !",
                "§7Un exploit légendaire !"
        }, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }
}
