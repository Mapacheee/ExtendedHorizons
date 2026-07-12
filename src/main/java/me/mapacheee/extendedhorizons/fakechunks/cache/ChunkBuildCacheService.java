package me.mapacheee.extendedhorizons.fakechunks.cache;

import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import me.mapacheee.lib.caffeine.cache.RemovalCause;
import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.config.EhConfig;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Service
public final class ChunkBuildCacheService {

    private final Container<EhConfig> configContainer;

    private volatile Cache<ChunkKey, ByteBuf> serializedCache;
    private volatile Cache<ChunkKey, CompletableFuture<ByteBuf>> buildEntryCache;
    private volatile Cache<ChunkKey, Boolean> bypassCache;
    private volatile Cache<ChunkKey, Long> unavailableUntilMs;

    @Inject
    public ChunkBuildCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuildCaches();
    }

    public void rebuildCaches() {
        int ttlSeconds = this.configContainer.get().cacheTtlSeconds();
        int maxEntries = this.configContainer.get().cacheMaxEntries();
        long bypassMs = this.configContainer.get().cacheBypassAfterRealInteractionMs();
        Cache<ChunkKey, ByteBuf> oldSerializedCache = this.serializedCache;
        Cache<ChunkKey, CompletableFuture<ByteBuf>> oldBuildEntryCache = this.buildEntryCache;
        Cache<ChunkKey, Boolean> oldBypassCache = this.bypassCache;
        Cache<ChunkKey, Long> oldUnavailableCache = this.unavailableUntilMs;

        Cache<ChunkKey, ByteBuf> newSerializedCache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .removalListener((ChunkKey key, ByteBuf value, RemovalCause cause) -> ReferenceCountUtil.release(value))
            .build();

        Cache<ChunkKey, CompletableFuture<ByteBuf>> newBuildEntryCache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .removalListener((ChunkKey key, CompletableFuture<ByteBuf> value, RemovalCause cause) -> releaseFuturePayload(value))
            .build();

        Cache<ChunkKey, Boolean> newBypassCache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofMillis(bypassMs))
            .build();

        long unavailableTtlMs = Math.max(1_000L, this.configContainer.get().unavailableRetryMs() * 2L);
        Cache<ChunkKey, Long> newUnavailableCache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofMillis(unavailableTtlMs))
            .build();

        this.serializedCache = newSerializedCache;
        this.buildEntryCache = newBuildEntryCache;
        this.bypassCache = newBypassCache;
        this.unavailableUntilMs = newUnavailableCache;

        drainCache(oldSerializedCache);
        drainCache(oldBuildEntryCache);
        drainCache(oldBypassCache);
        drainCache(oldUnavailableCache);
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

        CompletableFuture<ByteBuf> shared = new CompletableFuture<>();
        this.buildEntryCache.put(key, shared);

        CompletableFuture<ByteBuf> started;
        try {
            started = starter.get();
        } catch (Throwable throwable) {
            this.buildEntryCache.invalidate(key);
            shared.complete(null);
            return CompletableFuture.completedFuture(null);
        }
        if (started == null) {
            this.buildEntryCache.invalidate(key);
            shared.complete(null);
            return CompletableFuture.completedFuture(null);
        }
        started.whenComplete((payload, throwable) -> {
            if (throwable != null || payload == null || !payload.isReadable()) {
                shared.complete(null);
                this.buildEntryCache.invalidate(key);
                return;
            }
            ByteBuf sharedPayload = payload.retainedDuplicate();
            try {
                this.serializedCache.put(key, payload.retainedDuplicate());
                this.bypassCache.invalidate(key);
                shared.complete(sharedPayload);
            } finally {
                ReferenceCountUtil.release(payload);
            }
        });
        return shared.thenApply(this::retainReadable);
    }

    public void invalidate(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        ChunkKey key = new ChunkKey(worldId, chunkKey);
        this.serializedCache.invalidate(key);
        this.buildEntryCache.invalidate(key);
        this.bypassCache.put(key, Boolean.TRUE);
        this.unavailableUntilMs.invalidate(key);
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
        long retryAt = System.currentTimeMillis() + this.configContainer.get().unavailableRetryMs();
        this.unavailableUntilMs.put(new ChunkKey(worldId, chunkKey), retryAt);
    }

    public boolean isTemporarilyUnavailable(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return false;
        }
        Long until = this.unavailableUntilMs.getIfPresent(new ChunkKey(worldId, chunkKey));
        if (until == null) {
            return false;
        }
        return System.currentTimeMillis() < until;
    }

    public void cleanUp() {
        this.serializedCache.cleanUp();
        this.buildEntryCache.cleanUp();
        this.bypassCache.cleanUp();
        this.unavailableUntilMs.cleanUp();
    }

    public long getEstimatedSize() {
        return this.serializedCache.estimatedSize();
    }

    public void invalidateAll() {
        this.serializedCache.invalidateAll();
        this.buildEntryCache.invalidateAll();
        this.bypassCache.invalidateAll();
        this.unavailableUntilMs.invalidateAll();
        this.cleanUp();
    }

    @OnDisable
    public void onDisable() {
        this.invalidateAll();
    }

    public record ChunkKey(UUID worldId, long chunkKey) {
    }

    private ByteBuf retainReadable(ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        return payload.retainedDuplicate();
    }

    private static void releaseFuturePayload(CompletableFuture<ByteBuf> value) {
        if (value == null) {
            return;
        }
        if (value.isDone()) {
            if (!value.isCompletedExceptionally()) {
                ReferenceCountUtil.release(value.getNow(null));
            }
            return;
        }
        value.whenComplete((buf, throwable) -> {
            if (throwable == null) {
                ReferenceCountUtil.release(buf);
            }
        });
    }

    private static void drainCache(Cache<?, ?> cache) {
        if (cache == null) {
            return;
        }
        cache.invalidateAll();
        cache.cleanUp();
    }
}
