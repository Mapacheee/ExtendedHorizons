package me.mapacheee.extendedhorizons.fakechunks.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.config.ConfigFacade;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public final class ChunkBuildCacheService {

    private final ConfigFacade configFacade;

    private volatile Cache<ChunkKey, ByteBuf> serializedCache;
    private volatile Cache<ChunkKey, CompletableFuture<ByteBuf>> buildEntryCache;
    private volatile Cache<ChunkKey, Boolean> bypassCache;
    private final Map<ChunkKey, Long> unavailableUntilMs = new ConcurrentHashMap<>();

    @Inject
    public ChunkBuildCacheService(ConfigFacade configFacade) {
        this.configFacade = configFacade;
        this.rebuildCaches();
    }

    public synchronized void rebuildCaches() {
        int ttlSeconds = this.configFacade.get().cacheTtlSeconds();
        int maxEntries = this.configFacade.get().cacheMaxEntries();
        long bypassMs = this.configFacade.get().cacheBypassAfterRealInteractionMs();

        this.serializedCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .removalListener((ChunkKey key, ByteBuf value, RemovalCause cause) -> ReferenceCountUtil.release(value))
                .build();

        this.buildEntryCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterAccess(Duration.ofSeconds(ttlSeconds))
                .build();

        this.bypassCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofMillis(bypassMs))
                .build();
    }

    public ByteBuf getSerialized(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return null;
        }
        ByteBuf payload = this.serializedCache.getIfPresent(new ChunkKey(worldId, chunkKey));
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        return payload.retainedDuplicate();
    }

    public CompletableFuture<ByteBuf> getOrStartBuildFuture(
            UUID worldId,
            long chunkKey,
            Supplier<CompletableFuture<ByteBuf>> starter
    ) {
        if (worldId == null || starter == null) {
            return CompletableFuture.completedFuture(null);
        }
        ChunkKey key = new ChunkKey(worldId, chunkKey);
        CompletableFuture<ByteBuf> existing = this.buildEntryCache.getIfPresent(key);
        if (existing != null) {
            return existing.thenApply(this::retainReadable);
        }

        CompletableFuture<ByteBuf> started;
        try {
            started = starter.get();
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(null);
        }
        if (started == null) {
            return CompletableFuture.completedFuture(null);
        }
        this.buildEntryCache.put(key, started);
        started.whenComplete((payload, throwable) -> {
            if (throwable != null || payload == null || !payload.isReadable()) {
                this.buildEntryCache.invalidate(key);
                return;
            }
            this.serializedCache.put(key, payload.retainedDuplicate());
        });
        return started.thenApply(this::retainReadable);
    }

    public void invalidate(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        ChunkKey key = new ChunkKey(worldId, chunkKey);
        this.serializedCache.invalidate(key);
        this.buildEntryCache.invalidate(key);
        this.bypassCache.put(key, Boolean.TRUE);
        this.unavailableUntilMs.remove(key);
    }

    public boolean shouldBypass(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return true;
        }
        return Boolean.TRUE.equals(this.bypassCache.getIfPresent(new ChunkKey(worldId, chunkKey)));
    }

    public void markUnavailable(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        long retryAt = System.currentTimeMillis() + this.configFacade.get().unavailableRetryMs();
        this.unavailableUntilMs.put(new ChunkKey(worldId, chunkKey), retryAt);
    }

    public boolean isTemporarilyUnavailable(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return false;
        }
        ChunkKey key = new ChunkKey(worldId, chunkKey);
        Long until = this.unavailableUntilMs.get(key);
        if (until == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (until <= now) {
            this.unavailableUntilMs.remove(key);
            return false;
        }
        return true;
    }

    public record ChunkKey(UUID worldId, long chunkKey) {
    }

    private ByteBuf retainReadable(ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        return payload.retainedDuplicate();
    }
}

