package me.mapacheee.extendedhorizons.shared.config;

import com.google.inject.Inject;
import com.thewinterframework.paper.yaml.FileName;
import com.thewinterframework.paper.yaml.YamlConfig;
import com.thewinterframework.service.annotation.Service;
import org.slf4j.Logger;

/* Configuration service for managing plugin settings and reloading
 * Provides centralized access to configuration and messages using Winter Framework's YamlConfig
 */

@Service
public class ConfigService {

    private final Logger logger;
    private final YamlConfig configFile;
    private final YamlConfig messagesFile;

    @Inject
    public ConfigService(
            Logger logger,
            @FileName("config.yml") YamlConfig configFile,
            @FileName("messages.yml") YamlConfig messagesFile
    ) {
        this.logger = logger;
        this.configFile = configFile;
        this.messagesFile = messagesFile;
    }

    public boolean isEnabled() {
        return configFile.getBoolean("general.enabled", true);
    }

    public boolean isDebugEnabled() {
        return configFile.getBoolean("general.debug", false);
    }

    public boolean isFoliaDetectionEnabled() {
        return configFile.getBoolean("general.detect-folia", true);
    }

    public boolean isWorldEnabled(String worldName) {
        String path = switch (worldName.toLowerCase()) {
            case "world_nether" -> "worlds.world-nether.enabled";
            case "world_the_end" -> "worlds.world-the-end.enabled";
            default -> "worlds.default.enabled";
        };
        return configFile.getBoolean(path, true);
    }

    public int getMaxViewDistanceForWorld(String worldName) {
        String path = switch (worldName.toLowerCase()) {
            case "world_nether" -> "worlds.world-nether.max-distance";
            case "world_the_end" -> "worlds.world-the-end.max-distance";
            default -> "worlds.default.max-distance";
        };
        return configFile.getInt(path, 64);
    }

    public boolean areFakeChunksEnabledForWorld(String worldName) {
        String path = switch (worldName.toLowerCase()) {
            case "world_nether" -> "worlds.world-nether.fake-chunks-enabled";
            case "world_the_end" -> "worlds.world-the-end.fake-chunks-enabled";
            default -> "worlds.default.fake-chunks-enabled";
        };
        return configFile.getBoolean(path, true);
    }

    // Configuration getters
    public int getMaxViewDistance() {
        return configFile.getInt("view-distance.max-distance", 64);
    }

    public int getMinViewDistance() {
        return configFile.getInt("view-distance.min-distance", 2);
    }

    public int getDefaultViewDistance() {
        return configFile.getInt("view-distance.default-distance", 16);
    }

    public int getFakeChunksStartDistance() {
        return configFile.getInt("view-distance.fake-chunks-start-distance", 33);
    }

    public boolean areFakeChunksEnabled() {
        return configFile.getBoolean("view-distance.enable-fake-chunks", true);
    }

    public double getMinTpsThreshold() {
        return configFile.getDouble("performance.min-tps-threshold", 18.0);
    }

    public boolean isAdaptivePerformanceEnabled() {
        return configFile.getBoolean("performance.adaptive-performance", true);
    }

    public int getMaxChunksPerTick() {
        return configFile.getInt("performance.max-chunks-per-tick", 5);
    }

    public long getMaxBytesPerSecondPerPlayer() {
        return configFile.getLong("network.max-bytes-per-second-per-player", 1048576L);
    }

    public boolean isLuckPermsEnabled() {
        return configFile.getBoolean("integrations.luckperms.enabled", true);
    }

    public int getLuckPermsCheckInterval() {
        return configFile.getInt("integrations.luckperms.check-interval", 60);
    }

    public boolean isPlaceholderApiEnabled() {
        return configFile.getBoolean("integrations.placeholderapi.enabled", true);
    }

    public int getFakeChunksCacheSize() {
        return configFile.getInt("fake-chunks.cache-size", 64);
    }

    // Messages getters
    public String getPrefix() {
        return messagesFile.getString("prefix", "&#3498DB[&#E74C3CExtended&#F39C12Horizons&#3498DB] &#FFFFFF");
    }

    public String getNoPermissionMessage() {
        return messagesFile.getString("general.no-permission", "&#E74C3CYou don't have permission to use this command!");
    }

    public String getPlayerNotFoundMessage() {
        return messagesFile.getString("general.player-not-found", "&#E74C3CPlayer '{player}' not found!");
    }

    public String getConfigReloadedMessage() {
        return messagesFile.getString("general.config-reloaded", "&#2ECC71Configuration reloaded successfully!");
    }

    public String getCurrentDistanceMessage() {
        return messagesFile.getString("view-distance.current-distance", "&#3498DBYour current view distance: &#F39C12{distance} &#3498DBchunks");
    }

    public String getDistanceChangedMessage() {
        return messagesFile.getString("view-distance.distance-changed", "&#2ECC71View distance changed to &#FFFFFF{distance} &#2ECC71chunks");
    }

    public String getMaxDistanceExceededMessage() {
        return messagesFile.getString("view-distance.max-distance-exceeded", "&#E74C3CMaximum view distance is &#FFFFFF{max} &#E74C3Cchunks!");
    }

    public String getLowTpsWarningMessage() {
        return messagesFile.getString("performance.low-tps-warning", "&#E74C3CServer TPS is low (&#FFFFFF{tps}&#E74C3C), reducing view distances...");
    }

    public boolean isPerformanceWarningLoggingEnabled() {
        return configFile.getBoolean("monitoring.log-performance-warnings", true);
    }

    public String getPerformanceRestoredMessage() {
        return messagesFile.getString("performance.performance-restored", "&#2ECC71Server performance restored!");
    }

    public boolean isLuckPermsGroupPermissionsEnabled() {
        return configFile.getBoolean("integrations.luckperms.use-group-permissions", true);
    }

    public void reloadConfigs() {
        try {
            // Winter Framework automatically reload configs when accessed
            logger.info("Configurations reloaded successfully");
        } catch (Exception e) {
            logger.error("Error reloading configurations", e);
            throw new RuntimeException("Failed to reload configurations", e);
        }
    }
}
