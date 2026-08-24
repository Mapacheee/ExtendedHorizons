package me.mapacheee.extendedhorizons.fakechunks.cache;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import me.mapacheee.lib.caffeine.cache.RemovalCause;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public final class LightPayloadCacheService {

    private static final int MAX_ENTRIES_DIVISOR = 2;
    private static final int MIN_CACHE_ENTRIES = 128;

    private final Container<EhConfig> configContainer;
    private final Object lifecycleLock = new Object();
    private final AtomicLong generationId = new AtomicLong();
    private volatile CacheGeneration generation;

    @Inject
    public LightPayloadCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public void rebuild() {
        int ttlSeconds = Math.max(1, this.configContainer.get().cacheTtlSeconds());
        int maxEntries = Math.max(MIN_CACHE_ENTRIES, this.configContainer.get().cacheMaxEntries() / MAX_ENTRIES_DIVISOR);
        CacheGeneration next = new CacheGeneration(ttlSeconds, maxEntries);
        CacheGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = next;
            this.generationId.incrementAndGet();
        }
        if (previous != null) {
            previous.close();
        }
    }

    /** Returns one owned reference that the caller must release, or {@code null}. */
    public ByteBuf get(UUID worldId, long chunkKey) {
        return this.get(worldId, chunkKey, this.generationId.get());
    }

    /** Returns one owned reference only if the expected cache generation is still current. */
    public ByteBuf get(UUID worldId, long chunkKey, long expectedGeneration) {
        if (worldId == null) {
            return null;
        }
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            return current == null || expectedGeneration != this.generationId.get()
                ? null
                : current.acquire(new LightChunkKey(worldId, chunkKey));
        }
    }

    public long generation() {
        return this.generationId.get();
    }

    /** Retains a cache-owned reference; ownership of {@code payload} stays with the caller. */
    public void put(UUID worldId, long chunkKey, ByteBuf payload) {
        this.put(worldId, chunkKey, this.generationId.get(), payload);
    }

    /** Retains a cache-owned reference only if the expected generation is still current. */
    public void put(UUID worldId, long chunkKey, long expectedGeneration, ByteBuf payload) {
        if (worldId == null || payload == null) {
            return;
        }
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            if (current != null && expectedGeneration == this.generationId.get()) {
                current.put(new LightChunkKey(worldId, chunkKey), payload);
            }
        }
    }

    public void invalidate(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            if (current != null) {
                current.invalidate(new LightChunkKey(worldId, chunkKey));
            }
        }
    }

    public void cleanUp() {
        CacheGeneration current = this.generation;
        if (current != null) {
            current.cleanUp();
        }
    }

    public void invalidateAll() {
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            if (current != null) {
                this.generationId.incrementAndGet();
                current.invalidateAll();
            }
        }
    }

    @OnDisable
    public void onDisable() {
        CacheGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = null;
            this.generationId.incrementAndGet();
        }
        if (previous != null) {
            previous.close();
        }
    }

    private record LightChunkKey(UUID worldId, long chunkKey) {}

    private static final class CacheGeneration implements AutoCloseable {

        private final Cache<LightChunkKey, CachedPayload> cache;
        private final Cache<LightChunkKey, Boolean> invalidated;
        private volatile boolean active = true;
        private boolean closed;

        private CacheGeneration(int ttlSeconds, int maxEntries) {
            this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .executor(Runnable::run)
                .removalListener((LightChunkKey key, CachedPayload value, RemovalCause cause) -> close(value))
                .build();
            this.invalidated = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        }

        private ByteBuf acquire(LightChunkKey key) {
            if (!this.active) {
                return null;
            }
            CachedPayload payload = this.cache.getIfPresent(key);
            return payload == null ? null : payload.acquire();
        }

        private synchronized void put(LightChunkKey key, ByteBuf source) {
            if (!this.active || Boolean.TRUE.equals(this.invalidated.getIfPresent(key))) {
                return;
            }
            CachedPayload payload = CachedPayload.retain(source);
            if (payload == null) {
                return;
            }
            try {
                CachedPayload previous = this.cache.asMap().put(key, payload);
                close(previous);
            } catch (RuntimeException exception) {
                try {
                    this.cache.asMap().remove(key, payload);
                } finally {
                    payload.close();
                }
            }
        }

        private synchronized void invalidate(LightChunkKey key) {
            if (!this.active) {
                return;
            }
            this.invalidated.put(key, Boolean.TRUE);
            close(this.cache.asMap().remove(key));
        }

        private synchronized void cleanUp() {
            if (this.active) {
                this.cache.cleanUp();
                this.invalidated.cleanUp();
            }
        }

        private synchronized void invalidateAll() {
            if (this.active) {
                this.drain();
            }
        }

        private void retire() {
            this.active = false;
        }

        @Override
        public synchronized void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.active = false;
            this.drain();
        }

        private void drain() {
            for (CachedPayload payload : this.cache.asMap().values()) {
                close(payload);
            }
            this.cache.invalidateAll();
            this.cache.cleanUp();
            this.invalidated.invalidateAll();
            this.invalidated.cleanUp();
        }

        private static void close(CachedPayload payload) {
            if (payload != null) {
                payload.close();
            }
        }
    }
}

