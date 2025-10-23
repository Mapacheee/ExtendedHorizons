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

    private String hyphenVariant(String worldName) {
        return worldName.replace('_', '-');
    }

    private boolean isNether(String lower) {
        return lower.endsWith("_nether") || lower.contains("nether");
    }

    private boolean isTheEnd(String lower) {
        return lower.endsWith("_the_end") || lower.contains("the_end") || lower.contains("the-end");
    }

    public boolean isWorldEnabled(String worldName) {
        String raw = worldName;
        String hy = hyphenVariant(worldName);
        String lower = worldName.toLowerCase();

        boolean netherFallback = isNether(lower);
        boolean endFallback = isTheEnd(lower);

        return configFile.getBoolean("worlds." + raw + ".enabled",
            configFile.getBoolean("worlds." + hy + ".enabled",
                configFile.getBoolean(netherFallback ? "worlds.world-nether.enabled" : (endFallback ? "worlds.world-the-end.enabled" : "worlds.__none__"),
                    configFile.getBoolean("worlds.default.enabled", true))));
    }

    public boolean isWelcomeMessageEnabled() {
        return configFile.getBoolean("messages.welcome-message.enabled", true);
    }

    public int getMaxViewDistanceForWorld(String worldName) {
        String raw = worldName;
        String hy = hyphenVariant(worldName);
        String lower = worldName.toLowerCase();
        boolean netherFallback = isNether(lower);
        boolean endFallback = isTheEnd(lower);

        return configFile.getInt("worlds." + raw + ".max-distance",
            configFile.getInt("worlds." + hy + ".max-distance",
                configFile.getInt(netherFallback ? "worlds.world-nether.max-distance" : (endFallback ? "worlds.world-the-end.max-distance" : "worlds.__none__.max-distance"),
                    configFile.getInt("worlds.default.max-distance", 64))));
    }

    public boolean areFakeChunksEnabledForWorld(String worldName) {
        String raw = worldName;
        String hy = hyphenVariant(worldName);
        String lower = worldName.toLowerCase();
        boolean netherFallback = isNether(lower);
        boolean endFallback = isTheEnd(lower);

        return configFile.getBoolean("worlds." + raw + ".fake-chunks-enabled",
            configFile.getBoolean("worlds." + hy + ".fake-chunks-enabled",
                configFile.getBoolean(netherFallback ? "worlds.world-nether.fake-chunks-enabled" : (endFallback ? "worlds.world-the-end.fake-chunks-enabled" : "worlds.__none__.fake-chunks-enabled"),
                    configFile.getBoolean("worlds.default.fake-chunks-enabled", true))));
    }

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

    public int getMaxChunksPerTick() {
        return configFile.getInt("performance.max-chunks-per-tick", 5);
    }

    public long getMaxBytesPerSecondPerPlayer() {
        return configFile.getLong("network.max-bytes-per-second-per-player", 1048576L);
    }

    public boolean isLuckPermsEnabled() {
        return configFile.getBoolean("integrations.luckperms.enabled", true);
    }

    public boolean isLuckPermsGroupPermissionsEnabled() {
        return configFile.getBoolean("integrations.luckperms.use-group-permissions", true);
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

    public String getMinDistanceExceededMessage() {
        return messagesFile.getString("view-distance.min-distance-exceeded", "&#E74C3CMinimum view distance is &#FFFFFF{min} &#E74C3Cchunks!");
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

    public String getWelcomeMessage() {
        return messagesFile.getString("messages.welcome-message.text",
            "&#2ECC71Welcome! Your view distance has been set to &#FFFFFF{distance} &#2ECC71chunks.");
    }

    // Help messages
    public String getHelpHeaderMessage() {
        return messagesFile.getString("help.header", "&#3498DB========== &#E74C3CExtended&#F39C12Horizons &#3498DBHelp ==========");
    }

    public String getHelpFooterMessage() {
        return messagesFile.getString("help.footer", "&#3498DB==========================================");
    }

    public String getHelpSetMessage() {
        return messagesFile.getString("help.set", "&#F39C12/eh set <distance> &#FFFFFF- Set your view distance");
    }

    public String getHelpSetPlayerMessage() {
        return messagesFile.getString("help.set-player", "&#F39C12/eh set <player> <distance> &#FFFFFF- Set another player's view distance");
    }

    public String getHelpGetMessage() {
        return messagesFile.getString("help.get", "&#F39C12/eh get &#FFFFFF- Check your current view distance");
    }

    public String getHelpReloadMessage() {
        return messagesFile.getString("help.reload", "&#F39C12/eh reload &#FFFFFF- Reload configuration");
    }

    public String getHelpInfoMessage() {
        return messagesFile.getString("help.info", "&#F39C12/eh info &#FFFFFF- View plugin information");
    }

    public String getHelpDistanceMessage() {
        return messagesFile.getString("help.distance", "&#F39C12/eh distance <player> &#FFFFFF- Check another player's view distance");
    }

    public String getHelpResetMessage() {
        return messagesFile.getString("help.reset", "&#F39C12/eh reset &#FFFFFF- Reset your view distance to default");
    }

    public String getHelpAdminHeaderMessage() {
        return messagesFile.getString("help.admin-header", "&#E74C3C========== Admin Commands ==========");
    }

    public String getHelpStatsMessage() {
        return messagesFile.getString("help.stats", "&#F39C12/eh stats &#FFFFFF- View server statistics");
    }

    public String getHelpDebugMessage() {
        return messagesFile.getString("help.debug", "&#F39C12/eh debug &#FFFFFF- Toggle debug mode");
    }

    public String getHelpWorldMessage() {
        return messagesFile.getString("help.world", "&#F39C12/eh world <world> [distance] &#FFFFFF- Manage world settings");
    }

    public String getMinDistanceErrorMessage() {
        return messagesFile.getString("view-distance.min-distance-error", "&#E74C3CMinimum view distance is &#FFFFFF{min} &#E74C3Cchunks!");
    }

    // Stats messages
    public String getStatsHeaderMessage() {
        return messagesFile.getString("stats.header", "&#3498DB========== &#E74C3CExtended&#F39C12Horizons &#3498DBStats ==========");
    }

    public String getStatsPlayersOnlineMessage() {
        return messagesFile.getString("stats.players-online", "&#3498DBPlayers Online: &#FFFFFF{online}&#3498DB/&#FFFFFF{max}");
    }

    public String getStatsAverageDistanceMessage() {
        return messagesFile.getString("stats.average-distance", "&#F39C12Average View Distance: &#FFFFFF{distance} &#F39C12chunks");
    }

    public String getStatsChunksSentMessage() {
        return messagesFile.getString("stats.chunks-sent", "&#3498DBChunks Sent: &#FFFFFF{chunks}");
    }

    public String getStatsFakeChunksSentMessage() {
        return messagesFile.getString("stats.fake-chunks-sent", "&#3498DBFake Chunks Sent: &#FFFFFF{chunks}");
    }

    public String getStatsCacheSizeMessage() {
        return messagesFile.getString("stats.cache-size", "&#F39C12Cache Size: &#FFFFFF{size}");
    }


    public String getStatsFooterMessage() {
        return messagesFile.getString("stats.footer", "&#3498DB==========================================");
    }

    public void reload() {
        try {
            logger.info("Configuration and messages reloaded successfully");
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
            throw new RuntimeException("Configuration reload failed", e);
        }
    }

    public boolean isValidViewDistance(int distance) {
        return distance >= getMinViewDistance() && distance <= getMaxViewDistance();
    }

    public boolean isValidViewDistanceForWorld(String worldName, int distance) {
        return distance >= getMinViewDistance() && distance <= getMaxViewDistanceForWorld(worldName);
    }

    public String getPluginInfoMessage() {
        return messagesFile.getString("general.plugin-info", "&#3498DB{plugin} v{version} by {author}");
    }

    public String getFakeChunksEnabledInfoMessage() {
        return messagesFile.getString("view-distance.fake-chunks-enabled", "&#F39C12Fake chunks enabled for distances above &#FFFFFF{distance} &#F39C12chunks");
    }

    public String getOtherCurrentDistanceMessage() {
        return messagesFile.getString("view-distance.other-current-distance", "&#3498DB{player}'s view distance: &#F39C12{distance} &#3498DBchunks");
    }

    public String getNoViewDataOtherMessage() {
        return messagesFile.getString("view-distance.no-view-data-other", "&#E74C3CNo view data available for {player}");
    }

    public String getWorldMaxDistanceInfoMessage() {
        return messagesFile.getString("world.max-distance-info", "&#3498DBWorld &#FFFFFF{world} &#3498DBmax distance: &#F39C12{distance}");
    }
}
