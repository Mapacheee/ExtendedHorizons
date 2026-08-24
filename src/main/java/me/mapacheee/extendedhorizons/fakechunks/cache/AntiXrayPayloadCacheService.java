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
import org.bukkit.World;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public final class AntiXrayPayloadCacheService {

    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ENTRIES_DIVISOR = 2;
    private static final int PROFILE_HASH_DIVISOR = 4;
    private static final int MIN_CACHE_ENTRIES = 128;
    private static final int MIN_PROFILE_ENTRIES = 64;

    private final Container<EhConfig> configContainer;
    private final Object lifecycleLock = new Object();
    private final AtomicLong generationId = new AtomicLong();
    private volatile CacheGeneration generation;

    @Inject
    public AntiXrayPayloadCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public void rebuild() {
        int ttlSeconds = Math.max(1, this.configContainer.get().cacheTtlSeconds());
        int maxEntries = Math.max(MIN_CACHE_ENTRIES, this.configContainer.get().cacheMaxEntries() / MAX_ENTRIES_DIVISOR);
        int profileHashMaxEntries = Math.max(MIN_PROFILE_ENTRIES, maxEntries / PROFILE_HASH_DIVISOR);
        CacheGeneration next = new CacheGeneration(ttlSeconds, maxEntries, profileHashMaxEntries);
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

    public String resolveProfileHash(World world, EhConfig config) {
        CacheGeneration current = this.generation;
        if (world == null || config == null || !config.antiXrayEnabled(world.getName())) {
            if (world != null && current != null) {
                current.invalidateProfile(world.getUID());
            }
            return null;
        }
        if (current == null) {
            return null;
        }
        List<String> hiddenBlocks = config.antiXrayHiddenBlocks(world.getName());
        UUID worldId = world.getUID();
        ProfileHashEntry cached = current.getProfile(worldId);
        if (cached != null && cached.config() == config && cached.hiddenBlocks() == hiddenBlocks) {
            return cached.profileHash();
        }
        if (hiddenBlocks == null || hiddenBlocks.isEmpty()) {
            current.putProfile(worldId, new ProfileHashEntry(config, hiddenBlocks, "empty"));
            return "empty";
        }

        List<String> normalized = new ArrayList<>(hiddenBlocks.size());
        for (String value : hiddenBlocks) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toLowerCase(java.util.Locale.ROOT));
        }
        normalized.sort(Comparator.naturalOrder());

        StringBuilder builder = new StringBuilder(world.getName().length() + normalized.size() * 20);
        builder.append(world.getName().toLowerCase(java.util.Locale.ROOT)).append('|');
        for (String entry : normalized) {
            builder.append(entry).append(';');
        }
        String profileHash = Integer.toHexString(builder.toString().hashCode());
        current.putProfile(worldId, new ProfileHashEntry(config, hiddenBlocks, profileHash));
        return profileHash;
    }

    /** Returns one owned reference that the caller must release, or {@code null}. */
    public ByteBuf get(UUID worldId, long chunkKey, String profileHash, EhConfig.SerializerMode serializerMode) {
        return this.get(worldId, chunkKey, profileHash, serializerMode, this.generationId.get());
    }

    /** Returns one owned reference only if the expected cache generation is still current. */
    public ByteBuf get(
        UUID worldId,
        long chunkKey,
        String profileHash,
        EhConfig.SerializerMode serializerMode,
        long expectedGeneration
    ) {
        if (worldId == null || profileHash == null || serializerMode == null) {
            return null;
        }
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            return current == null || expectedGeneration != this.generationId.get()
                ? null
                : current.acquire(new AntiXrayPayloadKey(worldId, chunkKey, profileHash, serializerMode, FORMAT_VERSION));
        }
    }

    public long generation() {
        return this.generationId.get();
    }

    /** Retains a cache-owned reference; ownership of {@code payload} stays with the caller. */
    public void put(UUID worldId, long chunkKey, String profileHash, EhConfig.SerializerMode serializerMode, ByteBuf payload) {
        this.put(worldId, chunkKey, profileHash, serializerMode, this.generationId.get(), payload);
    }

    /** Retains a cache-owned reference only if the expected generation is still current. */
    public void put(
        UUID worldId,
        long chunkKey,
        String profileHash,
        EhConfig.SerializerMode serializerMode,
        long expectedGeneration,
        ByteBuf payload
    ) {
        if (worldId == null || profileHash == null || serializerMode == null || payload == null) {
            return;
        }
        AntiXrayPayloadKey key = new AntiXrayPayloadKey(worldId, chunkKey, profileHash, serializerMode, FORMAT_VERSION);
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            if (current != null && expectedGeneration == this.generationId.get()) {
                current.put(key, payload);
            }
        }
    }

    public void invalidateChunk(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        synchronized (this.lifecycleLock) {
            CacheGeneration current = this.generation;
            if (current != null) {
                current.invalidateChunk(new ChunkIndexKey(worldId, chunkKey));
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

    private record AntiXrayPayloadKey(
        UUID worldId,
        long chunkKey,
        String profileHash,
        EhConfig.SerializerMode serializerMode,
        int formatVersion
    ) {}

    private record ProfileHashEntry(
        EhConfig config,
        List<String> hiddenBlocks,
        String profileHash
    ) {}

    private record ChunkIndexKey(UUID worldId, long chunkKey) {}

    private static final class CacheGeneration implements AutoCloseable {

        private final Cache<AntiXrayPayloadKey, CachedPayload> payloadCache;
        private final Cache<UUID, ProfileHashEntry> profileHashCache;
        private final Cache<ChunkIndexKey, Boolean> invalidated;
        private final ConcurrentHashMap<ChunkIndexKey, Set<AntiXrayPayloadKey>> chunkIndex = new ConcurrentHashMap<>();
        private volatile boolean active = true;
        private boolean closed;

        private CacheGeneration(int ttlSeconds, int maxEntries, int profileHashMaxEntries) {
            this.payloadCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .executor(Runnable::run)
                .removalListener((AntiXrayPayloadKey key, CachedPayload value, RemovalCause cause) ->
                    this.onPayloadRemoval(key, value, cause))
                .build();
            this.profileHashCache = Caffeine.newBuilder()
                .maximumSize(profileHashMaxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
            this.invalidated = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        }

        private ByteBuf acquire(AntiXrayPayloadKey key) {
            if (!this.active) {
                return null;
            }
            CachedPayload payload = this.payloadCache.getIfPresent(key);
            return payload == null ? null : payload.acquire();
        }

        private synchronized void put(AntiXrayPayloadKey key, ByteBuf source) {
            ChunkIndexKey chunkKey = new ChunkIndexKey(key.worldId(), key.chunkKey());
            if (!this.active || Boolean.TRUE.equals(this.invalidated.getIfPresent(chunkKey))) {
                return;
            }
            CachedPayload payload = CachedPayload.retain(source);
            if (payload == null) {
                return;
            }
            try {
                CachedPayload previous = this.payloadCache.asMap().put(key, payload);
                close(previous);
                if (this.payloadCache.asMap().get(key) == payload) {
                    this.chunkIndex.computeIfAbsent(chunkKey, ignored -> ConcurrentHashMap.newKeySet()).add(key);
                } else {
                    this.removeFromIndex(key);
                }
            } catch (RuntimeException exception) {
                try {
                    this.payloadCache.asMap().remove(key, payload);
                    this.removeFromIndex(key);
                } finally {
                    payload.close();
                }
            }
        }

        private ProfileHashEntry getProfile(UUID worldId) {
            return this.active ? this.profileHashCache.getIfPresent(worldId) : null;
        }

        private synchronized void putProfile(UUID worldId, ProfileHashEntry entry) {
            if (this.active) {
                this.profileHashCache.put(worldId, entry);
            }
        }

        private synchronized void invalidateProfile(UUID worldId) {
            if (this.active) {
                this.profileHashCache.invalidate(worldId);
            }
        }

        private synchronized void invalidateChunk(ChunkIndexKey chunkKey) {
            if (!this.active) {
                return;
            }
            this.invalidated.put(chunkKey, Boolean.TRUE);
            Set<AntiXrayPayloadKey> keys = this.chunkIndex.remove(chunkKey);
            if (keys == null) {
                return;
            }
            for (AntiXrayPayloadKey key : keys) {
                close(this.payloadCache.asMap().remove(key));
            }
        }

        private synchronized void cleanUp() {
            if (!this.active) {
                return;
            }
            this.payloadCache.cleanUp();
            this.profileHashCache.cleanUp();
            this.invalidated.cleanUp();
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

        private synchronized void onPayloadRemoval(
            AntiXrayPayloadKey key,
            CachedPayload payload,
            RemovalCause cause
        ) {
            close(payload);
            if (key != null && this.payloadCache.asMap().get(key) == null) {
                this.removeFromIndex(key);
            }
        }

        private void removeFromIndex(AntiXrayPayloadKey key) {
            ChunkIndexKey chunkKey = new ChunkIndexKey(key.worldId(), key.chunkKey());
            Set<AntiXrayPayloadKey> keys = this.chunkIndex.get(chunkKey);
            if (keys == null) {
                return;
            }
            keys.remove(key);
            if (keys.isEmpty()) {
                this.chunkIndex.remove(chunkKey, keys);
            }
        }

        private void drain() {
            for (CachedPayload payload : this.payloadCache.asMap().values()) {
                close(payload);
            }
            this.payloadCache.invalidateAll();
            this.payloadCache.cleanUp();
            this.profileHashCache.invalidateAll();
            this.profileHashCache.cleanUp();
            this.invalidated.invalidateAll();
            this.invalidated.cleanUp();
            this.chunkIndex.clear();
        }

        private static void close(CachedPayload payload) {
            if (payload != null) {
                payload.close();
            }
        }
    }
}


