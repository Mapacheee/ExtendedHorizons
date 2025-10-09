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

    public String getInvalidDistanceMessage() {
        return messagesFile.getString("view-distance.invalid-distance", "&#E74C3CInvalid distance specified!");
    }

    public String getPlayerOnlyMessage() {
        return messagesFile.getString("general.player-only", "&#E74C3CThis command can only be used by players!");
    }

    public String getUnknownCommandMessage() {
        return messagesFile.getString("general.unknown-command", "&#E74C3CUnknown command! Use /eh help for available commands.");
    }

    public String getNoViewDataMessage() {
        return messagesFile.getString("view-distance.no-view-data", "&#E74C3CNo view data available!");
    }

    public String getDistanceSetOtherMessage() {
        return messagesFile.getString("view-distance.distance-set-other", "&#2ECC71Set &#FFFFFF{player}'s &#2ECC71view distance to &#FFFFFF{distance} &#2ECC71chunks");
    }

    public String getViewDistanceResetMessage() {
        return messagesFile.getString("view-distance.reset", "&#2ECC71View distance reset to default: &#FFFFFF{distance} &#2ECC71chunks");
    }

    public String getConfigErrorMessage() {
        return messagesFile.getString("general.config-error", "&#E74C3CError reloading configuration!");
    }

    public String getWorldNotFoundMessage() {
        return messagesFile.getString("world.not-found", "&#E74C3CWorld '{world}' not found!");
    }

    public String getWorldUsageMessage() {
        return messagesFile.getString("world.usage", "&#E74C3CUsage: /eh world <world> [distance]");
    }

    public String getWorldConfigNoticeMessage() {
        return messagesFile.getString("world.config-notice", "&#F39C12World-specific configuration changes require config file editing and reload.");
    }

    public String getDebugEnabledMessage() {
        return messagesFile.getString("debug.enabled", "&#2ECC71Debug mode is enabled");
    }

    public String getDebugDisabledMessage() {
        return messagesFile.getString("debug.disabled", "&#E74C3CDebug mode is disabled");
    }

    // Help messages
    public String getHelpHeaderMessage() {
        return messagesFile.getString("help.header", "&#3498DB========= &#F39C12ExtendedHorizons Help &#3498DB=========");
    }

    public String getHelpInfoMessage() {
        return messagesFile.getString("help.info", "&#3498DB/eh info &#FFFFFF- Show plugin and player information");
    }

    public String getHelpDistanceMessage() {
        return messagesFile.getString("help.distance", "&#3498DB/eh distance [value] &#FFFFFF- Get/set your view distance");
    }

    public String getHelpResetMessage() {
        return messagesFile.getString("help.reset", "&#3498DB/eh reset &#FFFFFF- Reset view distance to default");
    }

    public String getHelpAdminHeaderMessage() {
        return messagesFile.getString("help.admin-header", "&#E74C3C=== Admin Commands ===");
    }

    public String getHelpReloadMessage() {
        return messagesFile.getString("help.reload", "&#E74C3C/eh reload &#FFFFFF- Reload configuration");
    }

    public String getHelpStatsMessage() {
        return messagesFile.getString("help.stats", "&#E74C3C/eh stats &#FFFFFF- Show plugin statistics");
    }

    public String getHelpDebugMessage() {
        return messagesFile.getString("help.debug", "&#E74C3C/eh debug &#FFFFFF- Toggle debug mode");
    }

    public String getHelpWorldMessage() {
        return messagesFile.getString("help.world", "&#E74C3C/eh world <world> &#FFFFFF- World-specific settings");
    }

    public String getHelpFooterMessage() {
        return messagesFile.getString("help.footer", "&#3498DB===========================================");
    }

    // Stats messages
    public String getStatsHeaderMessage() {
        return messagesFile.getString("stats.header", "&#3498DB========= &#F39C12ExtendedHorizons Stats &#3498DB=========");
    }

    public String getStatsPlayersOnlineMessage() {
        return messagesFile.getString("stats.players-online", "&#3498DBPlayers Online: &#FFFFFF{online}&#3498DB/&#FFFFFF{max}");
    }

    public String getStatsAverageDistanceMessage() {
        return messagesFile.getString("stats.average-distance", "&#3498DBAverage Distance: &#F39C12{distance} &#3498DBchunks");
    }

    public String getStatsChunksSentMessage() {
        return messagesFile.getString("stats.chunks-sent", "&#3498DBChunks Sent: &#FFFFFF{chunks}");
    }

    public String getStatsFakeChunksSentMessage() {
        return messagesFile.getString("stats.fake-chunks-sent", "&#3498DBFake Chunks Sent: &#FFFFFF{chunks}");
    }

    public String getStatsCacheSizeMessage() {
        return messagesFile.getString("stats.cache-size", "&#3498DBCache Size: &#FFFFFF{size} &#3498DBMB");
    }

    public String getStatsServerTpsMessage() {
        return messagesFile.getString("stats.server-tps", "&#3498DBServer TPS: &#F39C12{tps}");
    }

    public String getStatsFooterMessage() {
        return messagesFile.getString("stats.footer", "&#3498DB===========================================");
    }

    public String getDatabaseFileName() {
        return configFile.getString("database.file-name", "extendedhorizons");
    }

    public boolean isDatabaseAutoServerEnabled() {
        return configFile.getBoolean("database.auto-server", true);
    }

    public int getDatabaseAutoServerPort() {
        return configFile.getInt("database.auto-server-port", 9092);
    }

    public boolean isDatabaseEnabled() {
        return configFile.getBoolean("database.enabled", true);
    }

    public int getDatabaseConnectionPoolSize() {
        return configFile.getInt("database.connection-pool-size", 10);
    }

    public void reload() throws Exception {
        try {
            logger.info("Configuration files reloaded successfully");
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
            throw e;
        }
    }

    public String getMinDistanceErrorMessage() {
        return messagesFile.getString("view-distance.min-distance-error", "&#E74C3CMinimum view distance is &#FFFFFF{min} &#E74C3Cchunks!");
    }
}
