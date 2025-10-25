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
 * Handles memory management and cache eviction strategies for fake chunk regions
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
        try {
            long currentTime = System.currentTimeMillis();
            regionCache.entrySet().removeIf(entry -> {
                ChunkRegion region = entry.getValue();
                if (currentTime - region.getLastAccessTime() > REGION_MAX_AGE) {
                    region.clearCache();
                    logger.debug("Cleaned up expired region cache for {}", entry.getKey());
                    return true;
                }
                return false;
            });
            updateCacheSize();
        } catch (Exception e) {
            logger.error("Error during cache cleanup", e);
        }
    }

    private void updateCacheSize() {
        cacheSize.set(regionCache.size());
    }

    private String createRegionKey(int regionX, int regionZ, World world) {
        return world.getName() + ":" + regionX + "," + regionZ;
    }

    public CacheStatistics getStatistics() {
        return new CacheStatistics(cacheSize.get(), regionCache.size());
    }

    public record CacheStatistics(long totalSize, long regionCount) {
        public long currentSizeMB() {
            // Assuming totalSize is in some unit, but for now return as is
            // In a real implementation, calculate based on memory usage
            return totalSize;
        }
    }
}
