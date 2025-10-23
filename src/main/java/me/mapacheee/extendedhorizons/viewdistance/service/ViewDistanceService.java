package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.integration.service.ILuckPermsIntegrationService;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import me.mapacheee.extendedhorizons.shared.storage.ViewDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collection;

/* View Distance Service - Core service managing player view distances and chunk sending
 * Handles real and fake chunk management with performance optimizations
 */

@Service
public class ViewDistanceService implements IViewDistanceService {

    private final Logger logger;
    private final ConfigService configService;
    private final PlayerViewService playerViewService;
    private final ILuckPermsIntegrationService luckPermsService;
    private final PerformanceMonitorService performanceMonitor;
    private final ViewDataStorage storage;

    private final Map<UUID, PlayerView> playerViews;
    private final AtomicInteger totalChunksSent;
    private final AtomicInteger totalFakeChunksSent;
    private volatile boolean adaptiveMode;
    private volatile boolean globalPause;

    private IChunkSenderService chunkSenderService;

    @Inject
    public ViewDistanceService(
            Logger logger,
            ConfigService configService,
            PlayerViewService playerViewService,
            ILuckPermsIntegrationService luckPermsService,
            PerformanceMonitorService performanceMonitor,
            ViewDataStorage storage
    ) {
        this.logger = logger;
        this.configService = configService;
        this.playerViewService = playerViewService;
        this.luckPermsService = luckPermsService;
        this.performanceMonitor = performanceMonitor;
        this.storage = storage;
        this.playerViews = new ConcurrentHashMap<>();
        this.totalChunksSent = new AtomicInteger(0);
        this.totalFakeChunksSent = new AtomicInteger(0);
        this.adaptiveMode = false;
        this.globalPause = false;
    }

    @Inject
    public void setChunkSenderService(IChunkSenderService chunkSenderService) {
        this.chunkSenderService = chunkSenderService;
        logger.info("ChunkSenderService injected successfully");
    }

    private IChunkSenderService getChunkSenderService() {
        if (chunkSenderService == null) {
            logger.warn("ChunkSenderService is not yet injected, skipping chunk operations");
            return null;
        }
        return chunkSenderService;
    }

    public void initializePlayerView(Player player) {
        if (!configService.isEnabled()) return;
        if (!configService.isWorldEnabled(player.getWorld().getName())) return;

        PlayerView playerView = new PlayerView(player);
        playerViews.put(player.getUniqueId(), playerView);

        var playerData = storage.getPlayerDataSync(player.getUniqueId());
        int preferredDistance = playerData != null ? playerData.preferredDistance() : configService.getDefaultViewDistance();

        int defaultDistance = Math.min(
                preferredDistance,
                getMaxAllowedDistance(player)
        );
        playerView.setTargetDistance(defaultDistance);
        playerView.setCurrentDistance(defaultDistance);

        try {
            player.setViewDistance(defaultDistance);
        } catch (Throwable ignored) {}
        try {
            player.setSendViewDistance(defaultDistance);
        } catch (Throwable ignored) { }

        updatePlayerPermissions(player);
        logger.info("Initialized view for player {} with distance {}",
                   player.getName(), playerView.getCurrentDistance());
    }

    public void removePlayerView(Player player) {
        PlayerView view = playerViews.remove(player.getUniqueId());
        if (view != null) {
            getChunkSenderService().unloadAllChunks(player);
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

        int maxAllowed = getMaxAllowedDistance(player);

        if (distance > maxAllowed) {
            distance = maxAllowed;
        }

        if (distance < configService.getMinViewDistance()) {
            distance = configService.getMinViewDistance();
        }

        view.setTargetDistance(distance);
        try {
            player.setViewDistance(distance);
        } catch (Throwable ignored) { }
        try {
            player.setSendViewDistance(distance);
        } catch (Throwable ignored) { }

        storage.savePlayerData(view);
        updatePlayerViewDistance(player);
    }

    public void updatePlayerViewDistance(Player player) {
        PlayerView playerView = playerViews.get(player.getUniqueId());
        if (playerView == null) return;

        if (globalPause) {
            playerView.setWaitingForChunks(true);
            return;
        }

        int targetDistance = calculateOptimalDistance(player, playerView);

        if (targetDistance != playerView.getCurrentDistance()) {
            playerView.setCurrentDistance(targetDistance);

            boolean enableFakeChunks = configService.areFakeChunksEnabled()
                    && configService.areFakeChunksEnabledForWorld(player.getWorld().getName())
                    && targetDistance > configService.getFakeChunksStartDistance();
            playerView.setFakeChunksEnabled(enableFakeChunks);

            try {
                player.setViewDistance(targetDistance);
            } catch (Throwable ignored) { }
            try {
                player.setSendViewDistance(targetDistance);
            } catch (Throwable ignored) { }

            IChunkSenderService chunkSender = getChunkSenderService();
            if (chunkSender != null) {
                chunkSender.sendChunks(player, playerView);
            } else {
                logger.debug("Skipping chunk sending for {} - ChunkSenderService not ready", player.getName());
            }
        }
    }

    private int calculateOptimalDistance(Player player, PlayerView view) {
        return view.getTargetDistance();
    }

    private int getMaxAllowedDistance(Player player) {
        int worldMax = configService.getMaxViewDistanceForWorld(player.getWorld().getName());
        int permissionMax = luckPermsService.getMaxViewDistance(player);

        return Math.min(worldMax, permissionMax);
    }

    public void updatePlayerPermissions(Player player) {
        PlayerView view = getPlayerView(player);
        if (view == null) return;

        int checkInterval = configService.getLuckPermsCheckInterval();

        if (!view.needsPermissionCheck(checkInterval * 1000L)) {
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

        view.setMovingTooFast(timeDiff < 100);

        view.updateLastMoveTime();
        view.setLastLocation(player.getLocation());

        if (view.getCurrentWorld() != player.getWorld()) {
            view.setCurrentWorld(player.getWorld());
            Objects.requireNonNull(getChunkSenderService()).unloadAllChunks(player);
            updatePlayerViewDistance(player);
        } else {
            IChunkSenderService chunkSender = getChunkSenderService();
            if (chunkSender != null) {
                chunkSender.sendChunks(player, view);
            } else {
                logger.debug("Skipping chunk sending for {} - ChunkSenderService not ready", player.getName());
            }
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

    @Override
    public void updatePlayerView(Player player) {
        updatePlayerViewDistance(player);
    }

    @Override
    public void setViewDistance(Player player, int distance) {
        setPlayerViewDistance(player, distance);
    }

    @Override
    public int getViewDistance(Player player) {
        PlayerView view = getPlayerView(player);
        return view != null ? view.getTargetDistance() : configService.getDefaultViewDistance();
    }

    @Override
    public int getEffectiveViewDistance(Player player) {
        PlayerView view = getPlayerView(player);
        return view != null ? view.getCurrentDistance() : configService.getDefaultViewDistance();
    }

    @Override
    public void setGlobalPause(boolean paused) {
        if (paused) {
            pauseAll();
        } else {
            resumeAll();
        }
    }

    @Override
    public PlayerView getPlayerView(UUID playerId) {
        return playerViews.get(playerId);
    }

    @Override
    public Collection<PlayerView> getAllPlayerViews() {
        return playerViews.values();
    }

    @Override
    public void handleChunkLoad(Player player, me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap.ChunkCoordinate coordinate) {
        PlayerView view = getPlayerView(player);
        if (view != null) {
            incrementChunksSent();
        }
    }

    @Override
    public void handleChunkUnload(Player player, me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap.ChunkCoordinate coordinate) {}

    @Override
    public void resetStats() {
        resetStatistics();
    }
}
