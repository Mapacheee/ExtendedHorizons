package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;

import com.google.inject.Inject;
import org.bukkit.Bukkit;
import java.util.concurrent.TimeUnit;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.atomic.AtomicLong;

import java.util.*;

/*
 *   LRU cache for PacketEvents Column objects intercepted from server chunk packets
 *   Stores complete chunk data with lighting for reuse as fake chunks
 *   Implements automatic TTL-based expiration and size-based eviction
 */
@Service
public class PacketChunkCacheService {

    private final Map<Long, Entry> cache;
    private final ConfigService configService;
    private ScheduledTask cleanupTask;
    private final long ttlMillis;

    // Metrics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    private static final class Entry {
        final Column column;
        long lastAccess;

        Entry(Column c) {
            this.column = c;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    @Inject
    public PacketChunkCacheService(ConfigService configService) {
        this.configService = configService;

        int maxEntries = configService.get().performance().fakeChunks().maxCachedPackets();
        if (maxEntries <= 0)
            maxEntries = 512;

        int ttlSeconds = configService.get().performance().fakeChunks().packetCacheTtlSeconds();
        if (ttlSeconds <= 0)
            ttlSeconds = 300;
        this.ttlMillis = ttlSeconds * 1000L;

        final int finalMaxEntries = maxEntries;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
                boolean remove = size() > finalMaxEntries;
                if (remove) {
                    evictions.incrementAndGet();
                }
                return remove;
            }
        });
    }

    @OnEnable
    public void register() {
        int intervalSeconds = configService.get().performance().fakeChunks().cacheCleanupInterval();
        if (intervalSeconds <= 0)
            intervalSeconds = 30;

        this.cleanupTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    long now = System.currentTimeMillis();
                    synchronized (cache) {
                        cache.entrySet().removeIf(e -> {
                            boolean expired = now - e.getValue().lastAccess > ttlMillis;
                            if (expired) {
                                evictions.incrementAndGet();
                            }
                            return expired;
                        });
                    }
                }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @OnDisable
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        clear();
    }

    public Column get(int x, int z) {
        long key = ChunkUtils.packChunkKey(x, z);
        Entry e = cache.get(key);
        if (e == null) {
            misses.incrementAndGet();
            return null;
        }

        long now = System.currentTimeMillis();
        if (now - e.lastAccess > ttlMillis) {
            cache.remove(key);
            misses.incrementAndGet();
            evictions.incrementAndGet();
            return null;
        }

        e.lastAccess = now;
        hits.incrementAndGet();
        return e.column;
    }

    public void put(int x, int z, Column column) {
        long key = ChunkUtils.packChunkKey(x, z);
        cache.put(key, new Entry(column));
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public Map<String, Long> getStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("hits", hits.get());
        stats.put("misses", misses.get());
        stats.put("evictions", evictions.get());
        stats.put("size", (long) cache.size());
        return stats;
    }

}
