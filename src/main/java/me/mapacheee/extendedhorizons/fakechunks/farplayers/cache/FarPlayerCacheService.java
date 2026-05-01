package me.mapacheee.extendedhorizons.fakechunks.farplayers.cache;

import com.google.inject.Inject;
import com.mojang.datafixers.util.Pair;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class FarPlayerCacheService {

    private static final int REGION_SIZE_BITS = 5;
    private static final int REGION_SIZE = 1 << REGION_SIZE_BITS;
    private static final int DEFAULT_MAX_ENTRIES = 500;

    private final Container<EhConfig> configContainer;
    private volatile Cache<UUID, FarPlayerState> statesCache;
    private volatile Cache<UUID, List<Pair<EquipmentSlot, ItemStack>>> equipmentCache;
    private final Map<UUID, Map<Long, Set<UUID>>> spatialIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerLastRegion = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerLastWorld = new ConcurrentHashMap<>();

    @Inject
    public FarPlayerCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public void rebuild() {
        int maxEntries = this.configContainer.get().farPlayerCacheEntries();
        this.statesCache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .build();
        this.equipmentCache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .build();
    }

    private long getRegionKey(int chunkX, int chunkZ) {
        return ChunkKeyCodec.pack(chunkX >> REGION_SIZE_BITS, chunkZ >> REGION_SIZE_BITS);
    }

    public void updateState(UUID playerId, FarPlayerState state) {
        this.statesCache.put(playerId, state);

        UUID worldId = state.worldId();
        int chunkX = (int) (state.x() / REGION_SIZE);
        int chunkZ = (int) (state.z() / REGION_SIZE);
        long regionKey = getRegionKey(chunkX, chunkZ);

        UUID prevWorld = this.playerLastWorld.get(playerId);
        Long prevRegion = this.playerLastRegion.get(playerId);

        if (prevWorld != null && (!prevWorld.equals(worldId) || prevRegion == null || prevRegion != regionKey)) {
            removeFromSpatialIndex(playerId, prevWorld, prevRegion);
        }

        if (prevWorld == null || !prevWorld.equals(worldId) || prevRegion == null || prevRegion != regionKey) {
            this.spatialIndex.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(playerId);
            this.playerLastWorld.put(playerId, worldId);
            this.playerLastRegion.put(playerId, regionKey);
        }
    }

    private void removeFromSpatialIndex(UUID playerId, UUID worldId, Long regionKey) {
        if (worldId != null && regionKey != null) {
            Map<Long, Set<UUID>> regions = this.spatialIndex.get(worldId);
            if (regions != null) {
                Set<UUID> playersInRegion = regions.get(regionKey);
                if (playersInRegion != null) {
                    playersInRegion.remove(playerId);
                    if (playersInRegion.isEmpty()) {
                        regions.remove(regionKey, playersInRegion);
                        if (regions.isEmpty()) {
                            this.spatialIndex.remove(worldId, regions);
                        }
                    }
                }
            }
        }
    }

    public void updateEquipment(UUID playerId, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        this.equipmentCache.put(playerId, equipment);
    }

    public List<Pair<EquipmentSlot, ItemStack>> getEquipment(UUID playerId) {
        return this.equipmentCache.getIfPresent(playerId);
    }

    public FarPlayerState getState(UUID playerId) {
        return this.statesCache.getIfPresent(playerId);
    }

    public void removePlayer(UUID playerId) {
        this.statesCache.invalidate(playerId);
        this.equipmentCache.invalidate(playerId);
        UUID worldId = this.playerLastWorld.remove(playerId);
        Long regionKey = this.playerLastRegion.remove(playerId);
        removeFromSpatialIndex(playerId, worldId, regionKey);
    }

    public Collection<FarPlayerState> getNearbyPlayers(UUID worldId, int chunkX, int chunkZ, int radiusChunks) {
        Map<Long, Set<UUID>> regions = this.spatialIndex.get(worldId);
        if (regions == null || regions.isEmpty()) {
            return Collections.emptyList();
        }

        int minChunkX = chunkX - radiusChunks;
        int maxChunkX = chunkX + radiusChunks;
        int minChunkZ = chunkZ - radiusChunks;
        int maxChunkZ = chunkZ + radiusChunks;

        int minRegionX = minChunkX >> REGION_SIZE_BITS;
        int maxRegionX = maxChunkX >> REGION_SIZE_BITS;
        int minRegionZ = minChunkZ >> REGION_SIZE_BITS;
        int maxRegionZ = maxChunkZ >> REGION_SIZE_BITS;

        List<FarPlayerState> nearby = new ArrayList<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                Set<UUID> playerIds = regions.get(ChunkKeyCodec.pack(rx, rz));
                if (playerIds != null) {
                    for (UUID pid : playerIds) {
                        FarPlayerState state = this.statesCache.getIfPresent(pid);
                        if (state != null) {
                            nearby.add(state);
                        }
                    }
                }
            }
        }
        return nearby;
    }
}
