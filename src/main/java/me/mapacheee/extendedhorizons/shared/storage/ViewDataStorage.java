package me.mapacheee.extendedhorizons.shared.storage;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/* Database Storage Service - Handles SQLite database operations for player data persistence
 * Manages player view preferences and statistics with connection pooling
 */

@Service
public class ViewDataStorage {

    private final Logger logger;
    private final ConfigService configService;
    private final Map<UUID, PlayerViewData> dataCache;
    private volatile Connection connection;
    private volatile boolean initialized;

    @Inject
    public ViewDataStorage(Logger logger, ConfigService configService) {
        this.logger = logger;
        this.configService = configService;
        this.dataCache = new ConcurrentHashMap<>();
        this.initialized = false;

        initializeDatabase();
    }

    private void initializeDatabase() {
        try {
            String fileName = configService.getDatabaseFileName();
            Path dataFolder = Path.of("plugins", "ExtendedHorizons");
            String dbPath = dataFolder.resolve(fileName + ".db").toString();

            String url = "jdbc:sqlite:" + dbPath;
            this.connection = DriverManager.getConnection(url);

            createTables();
            this.initialized = true;

            logger.info("SQLite Database initialized successfully at {}", dbPath);

        } catch (SQLException e) {
            logger.error("Failed to initialize SQLite database", e);
        }
    }

    private void createTables() throws SQLException {
        String createPlayerDataTable = """
            CREATE TABLE IF NOT EXISTS player_data (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(16) NOT NULL,
                preferred_distance INTEGER NOT NULL DEFAULT 16,
                fake_chunks_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                adaptive_mode_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                total_chunks_sent BIGINT NOT NULL DEFAULT 0,
                total_fake_chunks_sent BIGINT NOT NULL DEFAULT 0,
                total_network_bytes BIGINT NOT NULL DEFAULT 0,
                last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createStatsTable = """
            CREATE TABLE IF NOT EXISTS daily_stats (
                stat_date DATE PRIMARY KEY,
                total_players INTEGER NOT NULL DEFAULT 0,
                chunks_sent BIGINT NOT NULL DEFAULT 0,
                fake_chunks_sent BIGINT NOT NULL DEFAULT 0,
                network_bytes BIGINT NOT NULL DEFAULT 0,
                average_tps DECIMAL(4,2) NOT NULL DEFAULT 20.00,
                peak_players INTEGER NOT NULL DEFAULT 0
            )
            """;

        String createWorldStatsTable = """
            CREATE TABLE IF NOT EXISTS world_stats (
                world_name VARCHAR(64) NOT NULL,
                stat_date DATE NOT NULL,
                chunks_sent BIGINT NOT NULL DEFAULT 0,
                fake_chunks_sent BIGINT NOT NULL DEFAULT 0,
                average_distance DECIMAL(4,2) NOT NULL DEFAULT 16.00,
                PRIMARY KEY (world_name, stat_date)
            )
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createPlayerDataTable);
            stmt.execute(createStatsTable);
            stmt.execute(createWorldStatsTable);
        }

        logger.info("Database tables created/verified successfully");
    }

    public CompletableFuture<PlayerViewData> loadPlayerData(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!initialized) return new PlayerViewData(playerId, "", 16, false, true, 0, 0, 0);

            PlayerViewData cached = dataCache.get(playerId);
            if (cached != null) return cached;

            String sql = "SELECT * FROM player_data WHERE player_uuid = ?";

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        PlayerViewData data = new PlayerViewData(
                            playerId,
                            rs.getString("player_name"),
                            rs.getInt("preferred_distance"),
                            rs.getBoolean("fake_chunks_enabled"),
                            rs.getBoolean("adaptive_mode_enabled"),
                            rs.getLong("total_chunks_sent"),
                            rs.getLong("total_fake_chunks_sent"),
                            rs.getLong("total_network_bytes")
                        );

                        dataCache.put(playerId, data);
                        return data;
                    }
                }

            } catch (SQLException e) {
                logger.error("Error loading player data for {}", playerId, e);
            }

            return new PlayerViewData(playerId, "", 16, false, true, 0, 0, 0);
        });
    }

    public PlayerViewData getPlayerDataSync(UUID playerId) {
        if (!initialized) return new PlayerViewData(playerId, "", 16, false, true, 0, 0, 0);

        PlayerViewData cached = dataCache.get(playerId);
        if (cached != null) return cached;

        String sql = "SELECT * FROM player_data WHERE player_uuid = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    PlayerViewData data = new PlayerViewData(
                        playerId,
                        rs.getString("player_name"),
                        rs.getInt("preferred_distance"),
                        rs.getBoolean("fake_chunks_enabled"),
                        rs.getBoolean("adaptive_mode_enabled"),
                        rs.getLong("total_chunks_sent"),
                        rs.getLong("total_fake_chunks_sent"),
                        rs.getLong("total_network_bytes")
                    );

                    dataCache.put(playerId, data);
                    return data;
                }
            }

        } catch (SQLException e) {
            logger.error("Error loading player data for {}", playerId, e);
        }

        return new PlayerViewData(playerId, "", 16, false, true, 0, 0, 0);
    }

    public void savePlayerData(PlayerView playerView) {
        PlayerViewData data = new PlayerViewData(
                playerView.getPlayerId(),
                playerView.getPlayerName(),
                playerView.getTargetDistance(),
                playerView.areFakeChunksEnabled(),
                playerView.isAdaptiveModeEnabled(),
                playerView.getChunksSent(),
                playerView.getFakeChunksSent(),
                playerView.getNetworkBytesUsed()
        );

        savePlayerData(data).join(); // Wait for the save to complete
    }

    public CompletableFuture<Void> savePlayerData(PlayerViewData data) {
        return CompletableFuture.runAsync(() -> {
            if (!initialized) return;

            String sql = """
                INSERT OR REPLACE INTO player_data (
                    player_uuid, player_name, preferred_distance, fake_chunks_enabled,
                    adaptive_mode_enabled, total_chunks_sent, total_fake_chunks_sent,
                    total_network_bytes, last_login, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, data.playerId().toString());
                stmt.setString(2, data.playerName());
                stmt.setInt(3, data.preferredDistance());
                stmt.setBoolean(4, data.fakeChunksEnabled());
                stmt.setBoolean(5, data.adaptiveModeEnabled());
                stmt.setLong(6, data.totalChunksSent());
                stmt.setLong(7, data.totalFakeChunksSent());
                stmt.setLong(8, data.totalNetworkBytes());

                stmt.executeUpdate();
                dataCache.put(data.playerId(), data);

            } catch (SQLException e) {
                logger.error("Error saving player data for {}", data.playerId(), e);
            }
        });
    }

    public CompletableFuture<Void> saveDailyStats(DailyStats stats) {
        return CompletableFuture.runAsync(() -> {
            if (!initialized) return;

            String sql = """
                INSERT OR REPLACE INTO daily_stats (
                    stat_date, total_players, chunks_sent, fake_chunks_sent,
                    network_bytes, average_tps, peak_players
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setDate(1, new Date(stats.date().getTime()));
                stmt.setInt(2, stats.totalPlayers());
                stmt.setLong(3, stats.chunksSent());
                stmt.setLong(4, stats.fakeChunksSent());
                stmt.setLong(5, stats.networkBytes());
                stmt.setDouble(6, stats.averageTps());
                stmt.setInt(7, stats.peakPlayers());

                stmt.executeUpdate();

            } catch (SQLException e) {
                logger.error("Error saving daily stats", e);
            }
        });
    }

    public void invalidateCache(UUID playerId) {
        dataCache.remove(playerId);
    }

    public void clearCache() {
        dataCache.clear();
        logger.info("Player data cache cleared");
    }

    public int getCacheSize() {
        return dataCache.size();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("Database connection closed");
            }
        } catch (SQLException e) {
            logger.error("Error closing database connection", e);
        }
    }

    public record PlayerViewData(
        UUID playerId,
        String playerName,
        int preferredDistance,
        boolean fakeChunksEnabled,
        boolean adaptiveModeEnabled,
        long totalChunksSent,
        long totalFakeChunksSent,
        long totalNetworkBytes
    ) {}

    public record DailyStats(
        java.util.Date date,
        int totalPlayers,
        long chunksSent,
        long fakeChunksSent,
        long networkBytes,
        double averageTps,
        int peakPlayers
    ) {}
}
