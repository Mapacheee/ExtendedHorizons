package me.mapacheee.extendedhorizons.fakechunks.farplayers.cache;

import com.mojang.datafixers.util.Pair;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

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

    private final Map<UUID, FarPlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<UUID>>> spatialIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerLastRegion = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerLastWorld = new ConcurrentHashMap<>();
    private final Map<UUID, List<Pair<EquipmentSlot, ItemStack>>> equipmentCache = new ConcurrentHashMap<>();

    private long getRegionKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX >> 5, chunkZ >> 5);
    }

    public void updateState(UUID playerId, FarPlayerState state) {
        this.states.put(playerId, state);

        UUID worldId = state.worldId();
        int chunkX = (int) Math.floor(state.x()) >> 4;
        int chunkZ = (int) Math.floor(state.z()) >> 4;
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
                }
            }
        }
    }

    public void updateEquipment(UUID playerId, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        this.equipmentCache.put(playerId, equipment);
    }

    public List<Pair<EquipmentSlot, ItemStack>> getEquipment(UUID playerId) {
        return this.equipmentCache.get(playerId);
    }

    public FarPlayerState getState(UUID playerId) {
        return this.states.get(playerId);
    }

    public void removePlayer(UUID playerId) {
        this.states.remove(playerId);
        this.equipmentCache.remove(playerId);
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

        int minRegionX = minChunkX >> 5;
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;

        List<FarPlayerState> nearby = new ArrayList<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                Set<UUID> playerIds = regions.get(ChunkPos.asLong(rx, rz));
                if (playerIds != null) {
                    for (UUID pid : playerIds) {
                        FarPlayerState state = this.states.get(pid);
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
