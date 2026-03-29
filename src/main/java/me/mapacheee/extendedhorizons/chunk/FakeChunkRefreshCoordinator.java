package me.mapacheee.extendedhorizons.chunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;

public class FakeChunkRefreshCoordinator {

  private final Map<ChunkPacketCacheService.ChunkKey, Set<UUID>> fakeChunkSubscribers =
      new ConcurrentHashMap<>();
  private final Map<UUID, Set<ChunkPacketCacheService.ChunkKey>> playerFakeChunkIndex =
      new ConcurrentHashMap<>();
  private final Map<ChunkPacketCacheService.ChunkKey, Long> dirtyFakeChunks =
      new ConcurrentHashMap<>();

  public void clearAll() {
    fakeChunkSubscribers.clear();
    playerFakeChunkIndex.clear();
    dirtyFakeChunks.clear();
  }

  public void markDirty(UUID worldId, long chunkKey) {
    if (worldId == null) return;
    ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
    dirtyFakeChunks.put(key, System.currentTimeMillis());
  }

  public void clearDirty(UUID worldId, long chunkKey) {
    if (worldId == null) return;
    dirtyFakeChunks.remove(new ChunkPacketCacheService.ChunkKey(worldId, chunkKey));
  }

  public Long getDirtySince(UUID worldId, long chunkKey) {
    if (worldId == null) return null;
    return dirtyFakeChunks.get(new ChunkPacketCacheService.ChunkKey(worldId, chunkKey));
  }

  public void removeDirty(UUID worldId, long chunkKey) {
    if (worldId == null) return;
    dirtyFakeChunks.remove(new ChunkPacketCacheService.ChunkKey(worldId, chunkKey));
  }

  public Set<UUID> collectTargets(
      UUID worldId, long chunkKey, Map<UUID, PlayerChunkTracker> trackers) {
    Set<UUID> targets = new HashSet<>();
    if (worldId == null) return targets;
    ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
    Set<UUID> subscribers = fakeChunkSubscribers.get(key);
    if (subscribers != null && !subscribers.isEmpty()) {
      targets.addAll(new ArrayList<>(subscribers));
    }
    if (trackers == null || trackers.isEmpty()) return targets;
    for (Map.Entry<UUID, PlayerChunkTracker> entry : trackers.entrySet()) {
      UUID playerId = entry.getKey();
      PlayerChunkTracker tracker = entry.getValue();
      if (tracker == null) continue;
      if (!tracker.getSentChunks().contains(chunkKey)) continue;
      targets.add(playerId);
      addSubscription(playerId, worldId, chunkKey);
    }
    return targets;
  }

  public void addSubscription(UUID playerId, UUID worldId, long chunkKey) {
    if (playerId == null || worldId == null) return;
    ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
    fakeChunkSubscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(playerId);
    playerFakeChunkIndex.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key);
  }

  public void removeSubscription(UUID playerId, UUID worldId, long chunkKey) {
    if (playerId == null || worldId == null) return;
    ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
    Set<UUID> subscribers = fakeChunkSubscribers.get(key);
    if (subscribers != null) {
      subscribers.remove(playerId);
      if (subscribers.isEmpty()) fakeChunkSubscribers.remove(key);
    }
    Set<ChunkPacketCacheService.ChunkKey> keys = playerFakeChunkIndex.get(playerId);
    if (keys != null) {
      keys.remove(key);
      if (keys.isEmpty()) playerFakeChunkIndex.remove(playerId);
    }
  }

  public void clearPlayerSubscriptions(UUID playerId) {
    if (playerId == null) return;
    Set<ChunkPacketCacheService.ChunkKey> keys = playerFakeChunkIndex.remove(playerId);
    if (keys == null || keys.isEmpty()) return;
    for (ChunkPacketCacheService.ChunkKey key : keys) {
      Set<UUID> subscribers = fakeChunkSubscribers.get(key);
      if (subscribers == null) continue;
      subscribers.remove(playerId);
      if (subscribers.isEmpty()) fakeChunkSubscribers.remove(key);
    }
  }
}
