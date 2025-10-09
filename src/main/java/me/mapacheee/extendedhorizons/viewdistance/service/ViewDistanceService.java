package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.Config;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.integration.service.LuckPermsIntegrationService;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/* View Distance Service - Core service managing player view distances and chunk sending
 * Handles real and fake chunk management with performance optimizations
 */

@Service
public class ViewDistanceService {

    private final Logger logger;
    private final ConfigService configService;
    private final PlayerViewService playerViewService;
    private final ChunkSenderService chunkSenderService;
    private final LuckPermsIntegrationService luckPermsService;
    private final PerformanceMonitorService performanceMonitor;

    private final Map<UUID, PlayerView> playerViews;
    private final AtomicInteger totalChunksSent;
    private final AtomicInteger totalFakeChunksSent;
    private volatile boolean adaptiveMode;
    private volatile boolean globalPause;

    @Inject
    public ViewDistanceService(
            Logger logger,
            ConfigService configService,
            PlayerViewService playerViewService,
            ChunkSenderService chunkSenderService,
            LuckPermsIntegrationService luckPermsService,
            PerformanceMonitorService performanceMonitor
    ) {
        this.logger = logger;
        this.configService = configService;
        this.playerViewService = playerViewService;
        this.chunkSenderService = chunkSenderService;
        this.luckPermsService = luckPermsService;
        this.performanceMonitor = performanceMonitor;
        this.playerViews = new ConcurrentHashMap<>();
        this.totalChunksSent = new AtomicInteger(0);
        this.totalFakeChunksSent = new AtomicInteger(0);
        this.adaptiveMode = true;
        this.globalPause = false;
    }

    public void initializePlayerView(Player player) {
        if (!configService.isEnabled()) return;
        if (!configService.isWorldEnabled(player.getWorld().getName())) return;

        PlayerView playerView = new PlayerView(player);
        playerViews.put(player.getUniqueId(), playerView);

        updatePlayerPermissions(player);
        updatePlayerViewDistance(player);

        logger.info("Initialized view for player {} with distance {}",
                   player.getName(), playerView.getCurrentDistance());
    }

    public void removePlayerView(Player player) {
        PlayerView view = playerViews.remove(player.getUniqueId());
        if (view != null) {
            chunkSenderService.unloadAllChunks(player);
            logger.debug("Removed view for player {}", player.getName());
        }
    }

    public PlayerView getPlayerView(Player player) {
        return playerViews.get(player.getUniqueId());
    }

    public void setPlayerViewDistance(Player player, int distance) {
        PlayerView view = getPlayerView(player);
        if (view == null) {
            initializePlayerView(player);
            view = getPlayerView(player);
        }

        Config config = configService.getConfig();
        int maxAllowed = getMaxAllowedDistance(player);

        if (distance > maxAllowed) {
            distance = maxAllowed;
        }

        if (distance < config.viewDistance().minDistance()) {
            distance = config.viewDistance().minDistance();
        }

        view.setTargetDistance(distance);
        updatePlayerViewDistance(player);
    }

    public void updatePlayerViewDistance(Player player) {
        PlayerView view = getPlayerView(player);
        if (view == null) return;

        if (globalPause) {
            view.setWaitingForChunks(true);
            return;
        }

        int targetDistance = calculateOptimalDistance(player, view);

        if (targetDistance != view.getCurrentDistance()) {
            view.setCurrentDistance(targetDistance);

            Config config = configService.getConfig();
            boolean enableFakeChunks = config.viewDistance().enableFakeChunks() &&
                                     targetDistance > config.viewDistance().fakeChunksStartDistance();
            view.setFakeChunksEnabled(enableFakeChunks);

            chunkSenderService.updatePlayerChunks(player, view);
        }
    }

    private int calculateOptimalDistance(Player player, PlayerView view) {
        int targetDistance = view.getTargetDistance();

        if (!adaptiveMode) return targetDistance;

        if (performanceMonitor.getCurrentTPS() < configService.getConfig().performance().minTpsThreshold()) {
            return Math.max(8, targetDistance / 2);
        }

        if (view.isMovingTooFast()) {
            return Math.max(view.getCurrentDistance() - 2, 8);
        }

        long networkUsage = view.getNetworkBytesUsed();
        Config.NetworkConfig networkConfig = configService.getConfig().network();

        if (networkUsage > networkConfig.maxBytesPerSecondPerPlayer()) {
            return Math.max(view.getCurrentDistance() - 1, 8);
        }

        return targetDistance;
    }

    private int getMaxAllowedDistance(Player player) {
        int worldMax = configService.getMaxViewDistanceForWorld(player.getWorld().getName());
        int permissionMax = luckPermsService.getMaxViewDistance(player);

        return Math.min(worldMax, permissionMax);
    }

    public void updatePlayerPermissions(Player player) {
        PlayerView view = getPlayerView(player);
        if (view == null) return;

        Config.IntegrationsConfig.LuckPermsConfig luckConfig =
            configService.getConfig().integrations().luckperms();

        if (!view.needsPermissionCheck(luckConfig.checkInterval() * 1000L)) {
            return;
        }

        int maxDistance = getMaxAllowedDistance(player);
        view.setMaxAllowedDistance(maxDistance);
        view.setLastPermissionCheck(System.currentTimeMillis());

        if (view.getTargetDistance() > maxDistance) {
            view.setTargetDistance(maxDistance);
            updatePlayerViewDistance(player);
        }
    }

    public void handlePlayerMovement(Player player) {
        PlayerView view = getPlayerView(player);
        if (view == null) return;

        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - view.getLastMoveTime();

        if (timeDiff < 100) {
            view.setMovingTooFast(true);
        } else {
            view.setMovingTooFast(false);
        }

        view.updateLastMoveTime();
        view.setLastLocation(player.getLocation());

        if (view.getCurrentWorld() != player.getWorld()) {
            view.setCurrentWorld(player.getWorld());
            chunkSenderService.unloadAllChunks(player);
            updatePlayerViewDistance(player);
        } else {
            chunkSenderService.updatePlayerChunks(player, view);
        }
    }

    public void pauseAll() {
        globalPause = true;
        logger.info("Global view distance pause enabled");
    }

    public void resumeAll() {
        globalPause = false;

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerView view = getPlayerView(player);
            if (view != null) {
                view.setWaitingForChunks(false);
                updatePlayerViewDistance(player);
            }
        }

        logger.info("Global view distance pause disabled");
    }

    public void setAdaptiveMode(boolean enabled) {
        this.adaptiveMode = enabled;
        logger.info("Adaptive mode {}", enabled ? "enabled" : "disabled");
    }

    public boolean isAdaptiveMode() {
        return adaptiveMode;
    }

    public boolean isGlobalPaused() {
        return globalPause;
    }

    public int getTotalPlayers() {
        return playerViews.size();
    }

    public double getAverageViewDistance() {
        if (playerViews.isEmpty()) return 0;

        return playerViews.values().stream()
                .mapToInt(PlayerView::getCurrentDistance)
                .average()
                .orElse(0);
    }

    public int getTotalChunksSent() {
        return totalChunksSent.get();
    }

    public int getTotalFakeChunksSent() {
        return totalFakeChunksSent.get();
    }

    public void incrementChunksSent() {
        totalChunksSent.incrementAndGet();
    }

    public void incrementFakeChunksSent() {
        totalFakeChunksSent.incrementAndGet();
    }

    public void resetStatistics() {
        totalChunksSent.set(0);
        totalFakeChunksSent.set(0);

        playerViews.values().forEach(PlayerView::resetStatistics);
    }
}
