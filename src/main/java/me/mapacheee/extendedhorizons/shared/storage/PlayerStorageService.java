package me.mapacheee.extendedhorizons.shared.storage;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.io.File;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/*
 * Manages the persistence of player-specific data using an SQL database.
 * This service handles loading, saving, and updating player view distance
 * settings from the database.
 */
@Service
public class PlayerStorageService {

    private final ConfigService configService;
    private final Logger logger;
    private final Plugin plugin;
    private String databaseUrl;

    @Inject
    public PlayerStorageService(ConfigService configService, Logger logger) {
        this.configService = configService;
        this.logger = logger;
        this.plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    }

    @OnEnable
    public void initialize() {
        if (!configService.get().database().enabled()) {
            logger.info("Database is disabled. Player data will not be persisted.");
            return;
        }

        File dbFile = new File(plugin.getDataFolder(), configService.get().database().fileName() + ".db");
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }
        this.databaseUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try (Connection conn = DriverManager.getConnection(databaseUrl);
             Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS player_data (" +
                    "uuid TEXT PRIMARY KEY," +
                    "view_distance INTEGER NOT NULL" +
                    ");";
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.error("Failed to initialize player data database.", e);
        }
    }

    /**
     * Retrieves a players data from the database asynchronously.
     * @param uuid The UUID of the player to retrieve.
     * @return A CompletableFuture containing an Optional of PlayerData.
     */
    public CompletableFuture<Optional<PlayerData>> getPlayerData(UUID uuid) {
        if (!configService.get().database().enabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = DriverManager.getConnection(databaseUrl);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT view_distance FROM player_data WHERE uuid = ?")) {
                pstmt.setString(1, uuid.toString());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(new PlayerData(uuid, rs.getInt("view_distance")));
                }
            } catch (SQLException e) {
                logger.error("Failed to retrieve player data for " + uuid, e);
            }
            return Optional.empty();
        });
    }

    /**
     * Saves or updates a players data in the database asynchronously.
     * @param playerData The PlayerData object to save.
     * @return A CompletableFuture that completes when the operation is finished.
     */
    public CompletableFuture<Void> savePlayerData(PlayerData playerData) {
        if (!configService.get().database().enabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_data (uuid, view_distance) VALUES(?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET view_distance = excluded.view_distance;";
            try (Connection conn = DriverManager.getConnection(databaseUrl);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerData.getUuid().toString());
                pstmt.setInt(2, playerData.getViewDistance());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save player data for " + playerData.getUuid(), e);
            }
        });
    }
}

