package me.mapacheee.extendedhorizons.optimization.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.ChunkRegion;
import org.bukkit.World;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/* Cache Service - Manages caching of fake chunks and performance optimizations
 * Handles memory management and cache eviction strategies
 */

@Service
public class CacheService {

    private final Logger logger;
    private final ConfigService configService;
    private final ConcurrentHashMap<String, ChunkRegion> regionCache;
    private final AtomicLong cacheSize;
    private final ScheduledExecutorService cacheCleanupExecutor;

    private static final long CACHE_CLEANUP_INTERVAL = 60; // seconds
    private static final long REGION_MAX_AGE = 300000; // 5 minutes

    @Inject
    public CacheService(Logger logger, ConfigService configService) {
        this.logger = logger;
        this.configService = configService;
        this.regionCache = new ConcurrentHashMap<>();
        this.cacheSize = new AtomicLong(0);
        this.cacheCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExtendedHorizons-CacheCleanup");
            t.setDaemon(true);
            return t;
        });

        startCacheCleanupTask();
    }

    public ChunkRegion getOrCreateRegion(int regionX, int regionZ, World world) {
        String key = createRegionKey(regionX, regionZ, world);

        return regionCache.computeIfAbsent(key, k -> {
            ChunkRegion region = new ChunkRegion(regionX, regionZ, world);
            updateCacheSize();
            logger.debug("Created new region cache for {}", key);
            return region;
        });
    }

    public ChunkRegion getRegion(int regionX, int regionZ, World world) {
        String key = createRegionKey(regionX, regionZ, world);
        return regionCache.get(key);
    }

    public void evictRegion(int regionX, int regionZ, World world) {
        String key = createRegionKey(regionX, regionZ, world);
        ChunkRegion region = regionCache.remove(key);
        if (region != null) {
            region.clearCache();
            updateCacheSize();
            logger.debug("Evicted region cache for {}", key);
        }
    }

    public void clearWorldCache(World world) {
        regionCache.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(world.getName() + ":")) {
                entry.getValue().clearCache();
                return true;
            }
            return false;
        });
        updateCacheSize();
        logger.info("Cleared cache for world {}", world.getName());
    }

    public void clearAllCache() {
        regionCache.values().forEach(ChunkRegion::clearCache);
        regionCache.clear();
        cacheSize.set(0);
        logger.info("Cleared all region cache");
    }

    private void startCacheCleanupTask() {
        cacheCleanupExecutor.scheduleAtFixedRate(
            this::performCacheCleanup,
            CACHE_CLEANUP_INTERVAL,
            CACHE_CLEANUP_INTERVAL,
            TimeUnit.SECONDS
        );
    }

    private void performCacheCleanup() {
        int maxCacheSizeMB = configService.getFakeChunksCacheSize();
        long maxCacheSizeBytes = maxCacheSizeMB * 1024L * 1024L; // Convert MB to bytes

        // Remove old regions
        int removedCount = 0;
        for (var entry : regionCache.entrySet()) {
            ChunkRegion region = entry.getValue();
            if (region.shouldEvict(REGION_MAX_AGE)) {
                regionCache.remove(entry.getKey());
                region.clearCache();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            updateCacheSize();
            logger.debug("Cache cleanup removed {} old regions", removedCount);
        }

        if (getCurrentCacheSizeBytes() > maxCacheSizeBytes) {
            performEmergencyEviction(maxCacheSizeBytes);
        }
    }

    private void performEmergencyEviction(long maxCacheSizeBytes) {
        logger.warn("Cache size ({} MB) exceeds limit ({} MB), performing emergency eviction",
                   getCurrentCacheSizeMB(), maxCacheSizeBytes / (1024 * 1024));

        regionCache.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e1.getValue().getLastAccessTime(), e2.getValue().getLastAccessTime()))
            .limit(regionCache.size() / 4)
            .forEach(entry -> {
                regionCache.remove(entry.getKey());
                entry.getValue().clearCache();
            });

        updateCacheSize();
        logger.info("Emergency eviction completed, new cache size: {} MB", getCurrentCacheSizeMB());
    }

    private void updateCacheSize() {
        long totalSize = regionCache.values().stream()
            .mapToLong(region -> region.getCachedChunkCount() * 1024L) // Estimate 1KB per cached chunk
            .sum();
        cacheSize.set(totalSize);
    }

    private String createRegionKey(int regionX, int regionZ, World world) {
        return world.getName() + ":" + regionX + "," + regionZ;
    }

    public long getCurrentCacheSizeBytes() {
        return cacheSize.get();
    }

    public long getCurrentCacheSizeMB() {
        return getCurrentCacheSizeBytes() / (1024 * 1024);
    }

    public int getCachedRegionCount() {
        return regionCache.size();
    }

    public int getTotalCachedChunks() {
        return regionCache.values().stream()
            .mapToInt(ChunkRegion::getCachedChunkCount)
            .sum();
    }

    public CacheStatistics getStatistics() {
        return new CacheStatistics(
            getCachedRegionCount(),
            getTotalCachedChunks(),
            getCurrentCacheSizeMB(),
            configService.getFakeChunksCacheSize()
        );
    }

    public record CacheStatistics(
        int cachedRegions,
        int cachedChunks,
        long currentSizeMB,
        long maxSizeMB
    ) {}
}
