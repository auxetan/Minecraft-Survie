package com.survivalcore.modules;

import com.sun.net.httpserver.HttpServer;
import com.survivalcore.SurvivalCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Module ModPage — envoie aux nouveaux joueurs un lien cliquable vers la page
 * de téléchargement des mods recommandés. Héberge également la page HTML
 * via un serveur HTTP intégré (JDK HttpServer) sur le port configuré.
 */
public class ModPageModule implements CoreModule, Listener {

    private static final String DEFAULT_HTML_RESOURCE = "mods-page.html";
    private static final String HTML_FILE_NAME = "mods-page.html";

    private SurvivalCore plugin;
    private HttpServer httpServer;
    private int httpPort;
    private String serverIp;

    /** UUIDs des joueurs qui ont déjà reçu le message de bienvenue. */
    private final Set<UUID> welcomed = new HashSet<>();

    @Override
    public void onEnable(SurvivalCore plugin) {
        this.plugin = plugin;

        // Lire la config (valeurs par défaut si absentes)
        httpPort = plugin.getConfig().getInt("modpage.port", 8080);
        serverIp = plugin.getConfig().getString("modpage.server-ip", "localhost");

        // Sauvegarder le HTML par défaut si absent
        saveDefaultHtml();

        // Démarrer le serveur HTTP
        startHttpServer();

        // Enregistrer les listeners et commandes
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("mods").setExecutor(new ModsCommand());

        plugin.getLogger().info("ModPage HTTP server started on port " + httpPort);
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        welcomed.clear();
        plugin.getLogger().info("ModPage module disabled.");
    }

    @Override
    public String getName() {
        return "ModPage";
    }

    // ─── HTTP Server ─────────────────────────────────────────────

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.createContext("/mods", exchange -> {
                try {
                    byte[] html = loadModsPageHtml();
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, html.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(html);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Erreur lors du service de la page mods", e);
                    exchange.sendResponseHeaders(500, 0);
                    exchange.getResponseBody().close();
                }
            });
            // Endpoint /resourcepack — sert faithless.zip pour les clients Minecraft
            httpServer.createContext("/resourcepack", exchange -> {
                File zipFile = new File(plugin.getDataFolder().getParentFile().getParentFile(), "faithless.zip");
                if (!zipFile.exists()) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.getResponseBody().close();
                    return;
                }
                try {
                    byte[] data = Files.readAllBytes(zipFile.toPath());
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"faithless.zip\"");
                    exchange.sendResponseHeaders(200, data.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(data);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Erreur lors du service du pack de textures", e);
                    exchange.sendResponseHeaders(500, 0);
                    exchange.getResponseBody().close();
                }
            });
            // Redirection racine vers /mods
            httpServer.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Location", "/mods");
                exchange.sendResponseHeaders(301, -1);
                exchange.getResponseBody().close();
            });
            httpServer.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SurvivalCore-ModPage-HTTP");
                t.setDaemon(true);
                return t;
            }));
            httpServer.start();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de démarrer le serveur HTTP ModPage sur le port " + httpPort, e);
        }
    }

    private byte[] loadModsPageHtml() throws IOException {
        File htmlFile = new File(plugin.getDataFolder(), HTML_FILE_NAME);
        if (htmlFile.exists()) {
            return Files.readAllBytes(htmlFile.toPath());
        }
        // Fallback : ressource interne
        try (InputStream is = plugin.getResource(DEFAULT_HTML_RESOURCE)) {
            if (is != null) {
                return is.readAllBytes();
            }
        }
        return "<html><body><h1>Page non trouvée</h1></body></html>"
                .getBytes(StandardCharsets.UTF_8);
    }

    private void saveDefaultHtml() {
        File htmlFile = new File(plugin.getDataFolder(), HTML_FILE_NAME);
        if (htmlFile.exists()) return;
        plugin.getDataFolder().mkdirs();
        try (InputStream is = plugin.getResource(DEFAULT_HTML_RESOURCE)) {
            if (is == null) return;
            Files.copy(is, htmlFile.toPath());
            plugin.getLogger().info("mods-page.html sauvegardé dans " + htmlFile.getPath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible de sauvegarder mods-page.html", e);
        }
    }

    // ─── Listener ────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Envoyer le message seulement à la première connexion (session en cours)
        if (welcomed.contains(player.getUniqueId())) return;
        // Considérer "premier join" comme: le joueur n'a pas encore de stats
        // Pour simplifier, on utilise !hasPlayedBefore() OU la Set en mémoire
        // afin d'éviter d'envoyer le message à chaque redémarrage du serveur.
        if (!player.hasPlayedBefore()) {
            welcomed.add(player.getUniqueId());
            scheduleWelcomeMessage(player);
        }
    }

    private void scheduleWelcomeMessage(Player player) {
        // Délai de 3 secondes (60 ticks) avant d'envoyer le message
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendModsLink(player);
        }, 60L);
    }

    // ─── Envoi du message ─────────────────────────────────────────

    /**
     * Envoie le message avec lien cliquable vers la page des mods.
     */
    public void sendModsLink(Player player) {
        String url = "http://" + serverIp + ":" + httpPort + "/mods";

        Component separator = Component.text("§8§m                                        ");
        Component header = Component.text("§6§l✦ BIENVENUE SUR SURVIVALCRAFT ! ✦");
        Component subtitle = Component.text("§7Pour la meilleure expérience, installe les mods recommandés :");

        Component link = Component.text("[Clique ici pour télécharger les mods]")
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("§eOuvrir la page des mods")));

        Component note = Component.text("§7(Mods optionnels — le serveur fonctionne sans)");

        player.sendMessage(separator);
        player.sendMessage(header);
        player.sendMessage(subtitle);
        player.sendMessage(link);
        player.sendMessage(note);
        player.sendMessage(separator);
    }

    // ─── Commande /mods ──────────────────────────────────────────

    private class ModsCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCette commande est réservée aux joueurs.");
                return true;
            }
            sendModsLink(player);
            return true;
        }
    }
}
