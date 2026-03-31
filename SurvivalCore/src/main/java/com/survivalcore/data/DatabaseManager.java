package com.survivalcore.data;

import com.survivalcore.SurvivalCore;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Gestion de la base de données SQLite.
 * Toutes les opérations sont exécutées de manière asynchrone
 * via un ExecutorService dédié pour ne jamais bloquer le thread principal.
 */
public class DatabaseManager {

    private final SurvivalCore plugin;
    private Connection connection;
    private final ExecutorService executor;

    public DatabaseManager(SurvivalCore plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SurvivalCore-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialise la connexion et crée les tables.
     * Retourne un CompletableFuture qui se complète quand le schéma est prêt.
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                File dbFile = new File(plugin.getDataFolder(), "database.db");
                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                connection = DriverManager.getConnection(url);
                connection.setAutoCommit(true);

                // Activer WAL pour de meilleures performances en lecture concurrente
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    stmt.execute("PRAGMA foreign_keys=ON;");
                }

                createTables();
                plugin.getLogger().info("Base de données SQLite initialisée avec succès.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible d'initialiser la base de données !", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * Crée toutes les tables du schéma si elles n'existent pas.
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    name TEXT,
                    money REAL DEFAULT 0,
                    class TEXT DEFAULT 'NONE',
                    job TEXT DEFAULT 'NONE',
                    job_xp INTEGER DEFAULT 0,
                    job_level INTEGER DEFAULT 1,
                    skill_points INTEGER DEFAULT 0,
                    general_xp INTEGER DEFAULT 0,
                    kills_mobs INTEGER DEFAULT 0,
                    kills_players INTEGER DEFAULT 0,
                    deaths INTEGER DEFAULT 0,
                    blocks_mined INTEGER DEFAULT 0,
                    money_earned REAL DEFAULT 0,
                    quests_done INTEGER DEFAULT 0,
                    weekly_done INTEGER DEFAULT 0,
                    last_death_x REAL,
                    last_death_y REAL,
                    last_death_z REAL,
                    last_death_world TEXT,
                    last_class_change BIGINT,
                    last_job_change BIGINT,
                    starter_kit_given INTEGER DEFAULT 0,
                    quest_streak INTEGER DEFAULT 0,
                    last_quest_date TEXT
                );
                """);

            // Migration : ajouter colonnes manquantes sur les instances existantes
            try { stmt.execute("ALTER TABLE players ADD COLUMN starter_kit_given INTEGER DEFAULT 0"); }
            catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE players ADD COLUMN quest_streak INTEGER DEFAULT 0"); }
            catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE players ADD COLUMN last_quest_date TEXT"); }
            catch (SQLException ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS skills (
                    uuid TEXT,
                    skill_id TEXT,
                    PRIMARY KEY (uuid, skill_id)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS quests (
                    uuid TEXT,
                    quest_id TEXT,
                    progress INTEGER DEFAULT 0,
                    completed INTEGER DEFAULT 0,
                    date TEXT,
                    PRIMARY KEY (uuid, quest_id, date)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS weekly_mission (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    week TEXT,
                    mission_type TEXT,
                    mission_data TEXT,
                    completed INTEGER DEFAULT 0
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS weekly_participation (
                    uuid TEXT,
                    week TEXT,
                    progress INTEGER DEFAULT 0,
                    completed INTEGER DEFAULT 0,
                    PRIMARY KEY (uuid, week)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS market (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    seller_uuid TEXT,
                    item_serialized TEXT,
                    price REAL,
                    listed_at BIGINT
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS arc_progress (
                    uuid TEXT,
                    arc_id TEXT,
                    current_step INTEGER DEFAULT 1,
                    step_progress INTEGER DEFAULT 0,
                    completed INTEGER DEFAULT 0,
                    PRIMARY KEY (uuid, arc_id)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS waypoints (
                    uuid TEXT,
                    name TEXT,
                    world TEXT,
                    x REAL,
                    y REAL,
                    z REAL,
                    PRIMARY KEY (uuid, name)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS claims (
                    owner_uuid TEXT,
                    world TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    members TEXT,
                    PRIMARY KEY (world, chunk_x, chunk_z)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS job_milestones (
                    uuid TEXT,
                    job_id TEXT,
                    milestone INTEGER,
                    PRIMARY KEY (uuid, job_id, milestone)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS common_chest (
                    id INTEGER PRIMARY KEY DEFAULT 1,
                    contents TEXT
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS auctions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    seller_uuid TEXT NOT NULL,
                    item_data TEXT NOT NULL,
                    start_price REAL NOT NULL,
                    current_bid REAL DEFAULT 0,
                    bidder_uuid TEXT,
                    duration_hours INTEGER DEFAULT 24,
                    created_at BIGINT NOT NULL,
                    expired INTEGER DEFAULT 0,
                    collected INTEGER DEFAULT 0
                );
                """);
        }
    }

    /**
     * Exécute une opération sur la connexion de manière asynchrone.
     * Utilisation :
     * <pre>
     *   dbManager.executeAsync(conn -> {
     *       PreparedStatement ps = conn.prepareStatement("...");
     *       // ...
     *       return result;
     *   });
     * </pre>
     */
    public <T> CompletableFuture<T> executeAsync(Function<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    throw new SQLException("La connexion à la base de données est fermée.");
                }
                return operation.apply(connection);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur lors d'une opération DB async", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * Exécute une opération void de manière asynchrone.
     */
    public CompletableFuture<Void> runAsync(java.util.function.Consumer<Connection> operation) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    throw new SQLException("La connexion à la base de données est fermée.");
                }
                operation.accept(connection);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur lors d'une opération DB async", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * Retourne la connexion brute (à utiliser uniquement dans le contexte de l'executor).
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * Ferme proprement la connexion et l'executor.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("Connexion SQLite fermée.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Erreur lors de la fermeture de la connexion SQLite", e);
        }
    }
}
