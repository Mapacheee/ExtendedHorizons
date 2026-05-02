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
import org.bukkit.World;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public final class AntiXrayPayloadCacheService {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES_DIVISOR = 2;
    private static final int PROFILE_HASH_DIVISOR = 4;
    private static final int MIN_CACHE_ENTRIES = 128;
    private static final int MIN_PROFILE_ENTRIES = 64;

    private final Container<EhConfig> configContainer;
    private volatile Cache<AntiXrayPayloadKey, ByteBuf> cache;
    private volatile Cache<UUID, ProfileHashEntry> profileHashCache;

    @Inject
    public AntiXrayPayloadCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public void rebuild() {
        int ttlSeconds = Math.max(1, this.configContainer.get().cacheTtlSeconds());
        int maxEntries = Math.max(MIN_CACHE_ENTRIES, this.configContainer.get().cacheMaxEntries() / MAX_ENTRIES_DIVISOR);
        int profileHashMaxEntries = Math.max(MIN_PROFILE_ENTRIES, maxEntries / PROFILE_HASH_DIVISOR);

        this.cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .removalListener((AntiXrayPayloadKey key, ByteBuf value, RemovalCause cause) -> ReferenceCountUtil.release(value))
            .build();

        this.profileHashCache = Caffeine.newBuilder()
            .maximumSize(profileHashMaxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .build();
    }

    public String resolveProfileHash(World world, EhConfig config) {
        if (world == null || config == null || !config.antiXrayEnabled(world.getName())) {
            if (world != null) {
                this.profileHashCache.invalidate(world.getUID());
            }
            return null;
        }
        List<String> hiddenBlocks = config.antiXrayHiddenBlocks(world.getName());
        UUID worldId = world.getUID();
        ProfileHashEntry cached = this.profileHashCache.getIfPresent(worldId);
        if (cached != null && cached.config() == config && cached.hiddenBlocks() == hiddenBlocks) {
            return cached.profileHash();
        }
        if (hiddenBlocks == null || hiddenBlocks.isEmpty()) {
            this.profileHashCache.put(worldId, new ProfileHashEntry(config, hiddenBlocks, "empty"));
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
        this.profileHashCache.put(worldId, new ProfileHashEntry(config, hiddenBlocks, profileHash));
        return profileHash;
    }

    public ByteBuf get(UUID worldId, long chunkKey, String profileHash, EhConfig.SerializerMode serializerMode) {
        if (worldId == null || profileHash == null || serializerMode == null) {
            return null;
        }
        ByteBuf payload = this.cache.getIfPresent(new AntiXrayPayloadKey(worldId, chunkKey, profileHash, serializerMode, FORMAT_VERSION));
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        return payload.retainedDuplicate();
    }

    public void put(UUID worldId, long chunkKey, String profileHash, EhConfig.SerializerMode serializerMode, ByteBuf payload) {
        if (worldId == null || profileHash == null || serializerMode == null || payload == null || !payload.isReadable()) {
            return;
        }
        this.cache.put(
            new AntiXrayPayloadKey(worldId, chunkKey, profileHash, serializerMode, FORMAT_VERSION),
            payload.retainedDuplicate()
        );
    }

    public void invalidateChunk(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        this.cache.asMap().keySet().removeIf(key -> key.worldId().equals(worldId) && key.chunkKey() == chunkKey);
    }

    public void cleanUp() {
        this.cache.cleanUp();
        this.profileHashCache.cleanUp();
    }

    public void invalidateAll() {
        this.cache.invalidateAll();
        this.profileHashCache.invalidateAll();
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
}


