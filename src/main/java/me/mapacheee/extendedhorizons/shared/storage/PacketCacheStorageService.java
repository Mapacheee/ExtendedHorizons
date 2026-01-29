package me.mapacheee.extendedhorizons.shared.storage;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/*
 * Manages persistent storage of processed chunk packets using SQLite.
 */
@Service
public class PacketCacheStorageService {

    private final ConfigService configService;
    private final Logger logger;
    private final Plugin plugin;
    private String databaseUrl;

    @Inject
    public PacketCacheStorageService(ConfigService configService, Logger logger) {
        this.configService = configService;
        this.logger = logger;
        this.plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    }

    @OnEnable
    public void initialize() {
        try {
            if (!configService.get().database().enabled() ||
                    !configService.get().performance().fakeChunks().diskCache()) {
                return;
            }

            File dbFile = new File(plugin.getDataFolder(), configService.get().database().fileName() + ".db");
            if (!dbFile.getParentFile().exists()) {
                dbFile.getParentFile().mkdirs();
            }
            this.databaseUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            try (Connection conn = DriverManager.getConnection(databaseUrl);
                    Statement stmt = conn.createStatement()) {
                String sql = "CREATE TABLE IF NOT EXISTS packet_cache (" +
                        "world_uid VARCHAR(36) NOT NULL," +
                        "chunk_x INTEGER NOT NULL," +
                        "chunk_z INTEGER NOT NULL," +
                        "packet_data BLOB NOT NULL," +
                        "created_at INTEGER NOT NULL," +
                        "PRIMARY KEY (world_uid, chunk_x, chunk_z)" +
                        ");";
                stmt.execute(sql);

                logger.info("Packet cache (disk) initialized successfully.");
            } catch (SQLException e) {
                logger.error("Failed to initialize packet cache database.", e);
                this.databaseUrl = null;
            }
        } catch (Exception e) {
            logger.error("Critical error during PacketCacheStorageService initialization.", e);
            this.databaseUrl = null;
        }
    }

    public boolean isEnabled() {
        return databaseUrl != null && configService.get().performance().fakeChunks().diskCache();
    }

    /**
     * Retrieves cached packet data from disk.
     */
    public CompletableFuture<byte[]> getCachedPacket(UUID worldId, int x, int z) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = DriverManager.getConnection(databaseUrl);
                    PreparedStatement pstmt = conn
                            .prepareStatement(
                                    "SELECT packet_data FROM packet_cache WHERE world_uid = ? AND chunk_x = ? AND chunk_z = ?")) {
                pstmt.setString(1, worldId.toString());
                pstmt.setInt(2, x);
                pstmt.setInt(3, z);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    byte[] raw = rs.getBytes("packet_data");
                    if (raw == null || raw.length == 0) {
                        return null;
                    }
                    if (isGzip(raw)) {
                        try {
                            return decompress(raw);
                        } catch (Exception ignored) {
                            return null;
                        }
                    }
                    return raw;
                }
            } catch (SQLException e) {
            }
            return null;
        });
    }

    /**
     * Saves packet data to disk.
     */
    public void saveCachedPacket(UUID worldId, int x, int z, byte[] data) {
        if (!isEnabled() || data == null || data.length == 0) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO packet_cache (world_uid, chunk_x, chunk_z, packet_data, created_at) VALUES(?, ?, ?, ?, ?);";
            try (Connection conn = DriverManager.getConnection(databaseUrl);
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, worldId.toString());
                pstmt.setInt(2, x);
                pstmt.setInt(3, z);
                byte[] toStore = data;
                try {
                    if (configService.get().performance().fakeChunks().useCompression()) {
                        toStore = compress(data);
                    }
                } catch (Exception ignored) {
                }
                pstmt.setBytes(4, toStore);
                pstmt.setLong(5, System.currentTimeMillis());
            } catch (SQLException e) {
                logger.error("Failed to save packet cache for chunk " + x + "," + z, e);
            }
        });
    }

    private boolean isGzip(byte[] data) {
        return data.length > 2 && (data[0] == (byte) 0x1f) && (data[1] == (byte) 0x8b);
    }

    private byte[] compress(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gos = new GZIPOutputStream(baos);
        gos.write(input);
        gos.close();
        return baos.toByteArray();
    }

    private byte[] decompress(byte[] input) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(input);
        GZIPInputStream gis = new GZIPInputStream(bais);
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int read;
        while ((read = gis.read(buffer)) > 0) {
            baos.write(buffer, 0, read);
        }
        gis.close();
        return baos.toByteArray();
    }

    /**
     * Invalidates (deletes) a cached packet from disk.
     */
    public void invalidate(UUID worldId, int x, int z) {
        if (!isEnabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM packet_cache WHERE world_uid = ? AND chunk_x = ? AND chunk_z = ?;";
            try (Connection conn = DriverManager.getConnection(databaseUrl);
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, worldId.toString());
                pstmt.setInt(2, x);
                pstmt.setInt(3, z);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to invalidate packet cache for chunk " + x + "," + z, e);
            }
        });
    }
}
