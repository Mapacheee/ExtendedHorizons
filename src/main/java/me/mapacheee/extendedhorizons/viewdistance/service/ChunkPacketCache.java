package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/*
 *   LRU Cache for chunk packets (serialized data)
 *   Stores raw packet bytes instead of full chunks
 */
@Service
public class ChunkPacketCache {

    private Logger logger;
    private final ConfigService configService;

    private final Map<Long, byte[]> packetCache = new ConcurrentHashMap<>();

    private final Map<Long, Long> accessTimes = new ConcurrentHashMap<>();

    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long totalPacketsSaved = 0;

    @Inject
    public ChunkPacketCache(ConfigService configService) {
        this.configService = configService;
        startCleanupTask();
    }

    @OnDisable
    public void cleanup() {
        packetCache.clear();
        accessTimes.clear();
    }

    /**
     * Caches a chunk packet for later reuse
     */
    public void cachePacket(int chunkX, int chunkZ, byte[] packetData) {
        if (!configService.get().performance().fakeChunks().enabled()) {
            return;
        }

        long key = toKey(chunkX, chunkZ);

        try {
            byte[] dataToStore = packetData;

            if (configService.get().performance().fakeChunks().useCompression()) {
                dataToStore = compress(packetData);
            }

            int maxCached = configService.get().performance().fakeChunks().maxCachedPackets();
            if (packetCache.size() >= maxCached) {
                evictOldest();
            }

            packetCache.put(key, dataToStore);
            accessTimes.put(key, System.currentTimeMillis());
            totalPacketsSaved++;

        } catch (Exception e) {
            logger.warn("[EH] Failed to cache packet for chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
        }
    }

    /**
     * Retrieves a cached packet
     */
    public byte[] getPacket(int chunkX, int chunkZ) {
        if (!configService.get().performance().fakeChunks().enabled()) {
            return null;
        }

        long key = toKey(chunkX, chunkZ);
        byte[] cachedData = packetCache.get(key);

        if (cachedData == null) {
            cacheMisses++;
            return null;
        }

        accessTimes.put(key, System.currentTimeMillis());
        cacheHits++;

        try {
            if (configService.get().performance().fakeChunks().useCompression()) {
                return decompress(cachedData);
            }
            return cachedData;

        } catch (Exception e) {
            logger.warn("[EH] Failed to decompress packet for chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
            packetCache.remove(key);
            accessTimes.remove(key);
            return null;
        }
    }

    /**
     * Checks if a chunk packet is cached
     */
    public boolean isCached(int chunkX, int chunkZ) {
        return packetCache.containsKey(toKey(chunkX, chunkZ));
    }

    /**
     * Removes a packet from cache
     */
    public void invalidate(int chunkX, int chunkZ) {
        long key = toKey(chunkX, chunkZ);
        packetCache.remove(key);
        accessTimes.remove(key);
    }

    /**
     * Clears old entries based on LRU
     */
    private void evictOldest() {
        if (accessTimes.isEmpty()) return;

        Long oldestKey = accessTimes.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (oldestKey != null) {
            packetCache.remove(oldestKey);
            accessTimes.remove(oldestKey);
        }
    }

    /**
     * Periodic cleanup task to remove very old entries
     */
    private void startCleanupTask() {
        int intervalSeconds = configService.get().performance().fakeChunks().cacheCleanupInterval();

        Bukkit.getScheduler().runTaskTimerAsynchronously(
            me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class),
            this::performCleanup,
            intervalSeconds * 20L,
            intervalSeconds * 20L
        );
    }

    /**
     * Removes entries older than cleanup interval
     */
    private void performCleanup() {
        int maxAgeSeconds = configService.get().performance().fakeChunks().cacheCleanupInterval();
        long now = System.currentTimeMillis();
        long maxAgeMillis = maxAgeSeconds * 1000L;

        List<Long> toRemove = new ArrayList<>();

        for (Map.Entry<Long, Long> entry : accessTimes.entrySet()) {
            if (now - entry.getValue() > maxAgeMillis) {
                toRemove.add(entry.getKey());
            }
        }

        for (long key : toRemove) {
            packetCache.remove(key);
            accessTimes.remove(key);
        }

        if (!toRemove.isEmpty()) {
            logger.info("[EH] Cache cleanup: removed {} old packets", toRemove.size());
        }
    }

    /**
     * Compresses packet data using GZIP
     */
    private byte[] compress(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Decompresses GZIP packet data
     */
    private byte[] decompress(byte[] compressed) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
        }

        return baos.toByteArray();
    }

    /**
     * Converts chunk coordinates to cache key
     */
    private long toKey(int x, int z) {
        return ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
    }

    public int getCacheSize() {
        return packetCache.size();
    }

    public long getCacheHits() {
        return cacheHits;
    }

    public long getCacheMisses() {
        return cacheMisses;
    }

    public double getHitRate() {
        long total = cacheHits + cacheMisses;
        return total > 0 ? (cacheHits * 100.0 / total) : 0.0;
    }

    public long getTotalPacketsSaved() {
        return totalPacketsSaved;
    }

    /**
     * Estimates cache memory usage in MB
     */
    public double getEstimatedMemoryUsageMB() {
        long totalBytes = 0;
        for (byte[] data : packetCache.values()) {
            totalBytes += data.length;
        }
        return totalBytes / (1024.0 * 1024.0);
    }
}
