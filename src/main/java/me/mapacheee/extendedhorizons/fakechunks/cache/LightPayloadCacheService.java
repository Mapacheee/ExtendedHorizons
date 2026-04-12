package me.mapacheee.extendedhorizons.fakechunks.cache;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import me.mapacheee.lib.caffeine.cache.RemovalCause;

import java.time.Duration;
import java.util.UUID;

@Service
public final class LightPayloadCacheService {

    private final Container<EhConfig> configContainer;
    private volatile Cache<LightChunkKey, ByteBuf> cache;

    @Inject
    public LightPayloadCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public synchronized void rebuild() {
        int ttlSeconds = java.lang.Math.max(1, this.configContainer.get().cacheTtlSeconds());
        int maxEntries = java.lang.Math.max(128, this.configContainer.get().cacheMaxEntries() / 2);

        this.cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .removalListener((LightChunkKey key, ByteBuf value, RemovalCause cause) -> ReferenceCountUtil.release(value))
            .build();
    }

    public ByteBuf get(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return null;
        }
        ByteBuf payload = this.cache.getIfPresent(new LightChunkKey(worldId, chunkKey));
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        return payload.retainedDuplicate();
    }

    public void put(UUID worldId, long chunkKey, ByteBuf payload) {
        if (worldId == null || payload == null || !payload.isReadable()) {
            return;
        }
        this.cache.put(new LightChunkKey(worldId, chunkKey), payload.retainedDuplicate());
    }

    public void invalidate(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        this.cache.invalidate(new LightChunkKey(worldId, chunkKey));
    }

    public void invalidateAll() {
        this.cache.invalidateAll();
    }

    private record LightChunkKey(UUID worldId, long chunkKey) {
    }
}

