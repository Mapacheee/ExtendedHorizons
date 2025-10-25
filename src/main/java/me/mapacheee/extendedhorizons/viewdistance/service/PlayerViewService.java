package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/* Player View Service - Manages individual player view configurations and state
 * Handles view map calculations and player-specific optimizations
 */

@Service
public class PlayerViewService {

    private final Logger logger;
    private final ConfigService configService;
    private final ConcurrentHashMap<PlayerView, ViewMap> viewMaps;

    @Inject
    public PlayerViewService(Logger logger, ConfigService configService) {
        this.logger = logger;
        this.configService = configService;
        this.viewMaps = new ConcurrentHashMap<>();
    }

    public ViewMap createViewMap(PlayerView playerView) {
        ViewMap.ViewShape shape = ViewMap.ViewShape.SQUARE;
        int maxDistance = playerView.getMaxAllowedDistance();
        int fakeStartDistance = configService.getFakeChunksStartDistance();

        ViewMap viewMap = new ViewMap(shape, maxDistance, fakeStartDistance);
        viewMaps.put(playerView, viewMap);

        return viewMap;
    }

    public ViewMap getViewMap(PlayerView playerView) {
        return viewMaps.get(playerView);
    }

    public void removeViewMap(PlayerView playerView) {
        ViewMap viewMap = viewMaps.remove(playerView);
        if (viewMap != null) {
            viewMap.clearCache();
        }
    }

    public CompletableFuture<ViewMapUpdate> calculateViewUpdate(Player player, PlayerView playerView) {
        return CompletableFuture.supplyAsync(() -> {
            ViewMap viewMap = getViewMap(playerView);
            if (viewMap == null) {
                viewMap = createViewMap(playerView);
            }

            Location currentLocation = player.getLocation();
            Set<ViewMap.ChunkCoordinate> previousChunks = viewMap.getVisibleChunks();

            viewMap.updateCenter(currentLocation);

            Set<ViewMap.ChunkCoordinate> newChunks = viewMap.getNewChunks(previousChunks);
            Set<ViewMap.ChunkCoordinate> removedChunks = viewMap.getRemovedChunks(previousChunks);
            Set<ViewMap.ChunkCoordinate> fakeChunks = viewMap.getFakeChunks();

            return new ViewMapUpdate(
                newChunks,
                removedChunks,
                fakeChunks,
                viewMap.getTotalVisibleChunks(),
                viewMap.getTotalFakeChunks()
            );
        });
    }

    public void updateViewMapDistance(PlayerView playerView, int newDistance) {
        ViewMap viewMap = getViewMap(playerView);
        if (viewMap != null) {
            removeViewMap(playerView);
        }

        createViewMap(playerView);
    }

    public boolean isLocationSignificantlyDifferent(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) return true;
        if (!loc1.getWorld().equals(loc2.getWorld())) return true;

        int chunkX1 = loc1.getBlockX() >> 4;
        int chunkZ1 = loc1.getBlockZ() >> 4;
        int chunkX2 = loc2.getBlockX() >> 4;
        int chunkZ2 = loc2.getBlockZ() >> 4;

        return chunkX1 != chunkX2 || chunkZ1 != chunkZ2;
    }

    public double calculateMovementSpeed(PlayerView playerView) {
        Location lastLoc = playerView.getLastLocation();
        long timeDiff = System.currentTimeMillis() - playerView.getLastMoveTime();

        if (lastLoc == null || timeDiff <= 0) return 0;

        Player player = org.bukkit.Bukkit.getPlayer(playerView.getPlayerId());
        if (player == null) return 0;

        Location currentLoc = player.getLocation();
        double distance = lastLoc.distance(currentLoc);
        double timeInSeconds = timeDiff / 1000.0;

        return distance / timeInSeconds;
    }

    public boolean shouldReduceViewDistance(PlayerView playerView) {
        double speed = calculateMovementSpeed(playerView);

        if (speed > 20.0) {
            return true;
        }

        return playerView.getNetworkUsagePerSecond() > configService.getMaxBytesPerSecondPerPlayer();
    }

    public int calculateOptimalChunksPerTick(PlayerView playerView) {
        int baseChunksPerTick = configService.getMaxChunksPerTick();

        if (playerView.isMovingTooFast()) {
            return Math.max(1, baseChunksPerTick / 2);
        }

        if (playerView.isWaitingForChunks()) {
            return Math.min(baseChunksPerTick * 2, 10);
        }

        return baseChunksPerTick;
    }

    public void clearAllViewMaps() {
        viewMaps.values().forEach(ViewMap::clearCache);
        viewMaps.clear();
        logger.info("Cleared all view maps");
    }

    public record ViewMapUpdate(
        Set<ViewMap.ChunkCoordinate> newChunks,
        Set<ViewMap.ChunkCoordinate> removedChunks,
        Set<ViewMap.ChunkCoordinate> fakeChunks,
        int totalVisibleChunks,
        int totalFakeChunks
    ) {}
}
