/* PlaceholderAPI Provider - Provides placeholders for ExtendedHorizons integration
 * Offers comprehensive placeholders for view distance, performance, and statistics
 */
package me.mapacheee.extendedhorizons.integration.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import me.mapacheee.extendedhorizons.optimization.service.CacheService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service
public class PlaceholderAPIProvider extends PlaceholderExpansion {

    private final ConfigService configService;
    private final ViewDistanceService viewDistanceService;
    private final PerformanceMonitorService performanceMonitor;
    private final CacheService cacheService;
    private final LuckPermsIntegrationService luckPermsService;

    @Inject
    public PlaceholderAPIProvider(
            ConfigService configService,
            ViewDistanceService viewDistanceService,
            PerformanceMonitorService performanceMonitor,
            CacheService cacheService,
            LuckPermsIntegrationService luckPermsService
    ) {
        this.configService = configService;
        this.viewDistanceService = viewDistanceService;
        this.performanceMonitor = performanceMonitor;
        this.cacheService = cacheService;
        this.luckPermsService = luckPermsService;

        if (canRegister()) {
            register();
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "extendedhorizons";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mapacheee";
    }

    @Override
    public @NotNull String getVersion() {
        return ExtendedHorizonsPlugin.getInstance().getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return configService.isPlaceholderApiEnabled();
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return handleGlobalPlaceholder(params);
        }

        return switch (params.toLowerCase()) {
            // Player-specific placeholders
            case "distance" -> getPlayerDistance(player);
            case "max_distance" -> getPlayerMaxDistance(player);
            case "target_distance" -> getPlayerTargetDistance(player);
            case "fake_chunks_enabled" -> getPlayerFakeChunksEnabled(player);
            case "chunks_sent" -> getPlayerChunksSent(player);
            case "fake_chunks_sent" -> getPlayerFakeChunksSent(player);
            case "network_usage" -> getPlayerNetworkUsage(player);
            case "network_usage_percent" -> getPlayerNetworkUsagePercent(player);
            case "moving_too_fast" -> getPlayerMovingTooFast(player);
            case "waiting_for_chunks" -> getPlayerWaitingForChunks(player);
            case "permission_group" -> getPlayerPermissionGroup(player);
            case "world_enabled" -> getPlayerWorldEnabled(player);
            case "adaptive_mode" -> getPlayerAdaptiveMode(player);

            // Global placeholders (also work with player context)
            default -> handleGlobalPlaceholder(params);
        };
    }

    private String handleGlobalPlaceholder(String params) {
        return switch (params.toLowerCase()) {
            // Server statistics
            case "server_tps" -> String.format("%.1f", performanceMonitor.getCurrentTPS());
            case "server_lagging" -> String.valueOf(performanceMonitor.isServerLagging());
            case "performance_score" -> String.format("%.2f", performanceMonitor.getPerformanceScore());
            case "recommended_max_distance" -> String.valueOf(performanceMonitor.getRecommendedMaxViewDistance());

            // Memory and performance
            case "memory_used" -> String.valueOf(performanceMonitor.getCurrentMetrics().usedMemoryMB());
            case "memory_max" -> String.valueOf(performanceMonitor.getCurrentMetrics().maxMemoryMB());
            case "memory_percent" -> String.format("%.1f", performanceMonitor.getCurrentMetrics().memoryUsagePercent());
            case "cpu_usage" -> String.format("%.1f", performanceMonitor.getCurrentMetrics().cpuUsage());

            // View distance statistics
            case "total_players" -> String.valueOf(viewDistanceService.getTotalPlayers());
            case "average_distance" -> String.format("%.1f", viewDistanceService.getAverageViewDistance());
            case "total_chunks_sent" -> String.valueOf(viewDistanceService.getTotalChunksSent());
            case "total_fake_chunks_sent" -> String.valueOf(viewDistanceService.getTotalFakeChunksSent());
            case "adaptive_mode_enabled" -> String.valueOf(viewDistanceService.isAdaptiveMode());
            case "global_paused" -> String.valueOf(viewDistanceService.isGlobalPaused());

            // Cache statistics
            case "cache_size_mb" -> String.valueOf(cacheService.getCurrentCacheSizeMB());
            case "cache_regions" -> String.valueOf(cacheService.getCachedRegionCount());
            case "cache_chunks" -> String.valueOf(cacheService.getTotalCachedChunks());

            // Integration status
            case "luckperms_enabled" -> String.valueOf(luckPermsService.isLuckPermsAvailable());
            case "placeholderapi_enabled" -> "true"; // If this is being called, it's enabled

            // Configuration values - use specific config methods
            case "max_view_distance" -> String.valueOf(configService.getMaxViewDistance());
            case "min_view_distance" -> String.valueOf(configService.getMinViewDistance());
            case "default_view_distance" -> String.valueOf(configService.getDefaultViewDistance());
            case "fake_chunks_start_distance" -> String.valueOf(configService.getFakeChunksStartDistance());

            default -> null;
        };
    }

    private String getPlayerDistance(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.getCurrentDistance()) : "0";
    }

    private String getPlayerMaxDistance(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.getMaxAllowedDistance()) : "0";
    }

    private String getPlayerTargetDistance(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.getTargetDistance()) : "0";
    }

    private String getPlayerFakeChunksEnabled(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.areFakeChunksEnabled()) : "false";
    }

    private String getPlayerChunksSent(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.getChunksSent()) : "0";
    }

    private String getPlayerFakeChunksSent(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.getFakeChunksSent()) : "0";
    }

    private String getPlayerNetworkUsage(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.getNetworkBytesUsed()) : "0";
    }

    private String getPlayerNetworkUsagePercent(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        if (view == null) return "0.0";

        // Use specific config method instead of getConfig().network().maxBytesPerSecondPerPlayer()
        long maxBytes = configService.getMaxBytesPerSecondPerPlayer();
        if (maxBytes <= 0) return "0.0";

        double percent = (double) view.getNetworkBytesUsed() / maxBytes * 100;
        return String.format("%.1f", percent);
    }

    private String getPlayerMovingTooFast(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.isMovingTooFast()) : "false";
    }

    private String getPlayerWaitingForChunks(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.isWaitingForChunks()) : "false";
    }

    private String getPlayerPermissionGroup(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null && view.getCurrentPermissionGroup() != null ? view.getCurrentPermissionGroup() : "default";
    }

    private String getPlayerWorldEnabled(Player player) {
        return String.valueOf(configService.isWorldEnabled(player.getWorld().getName()));
    }

    private String getPlayerAdaptiveMode(Player player) {
        PlayerView view = viewDistanceService.getPlayerView(player);
        return view != null ? String.valueOf(view.isAdaptiveModeEnabled()) : "true";
    }
}
