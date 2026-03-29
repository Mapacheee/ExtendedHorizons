package me.mapacheee.extendedhorizons.chunk.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

@Service
public class ChunkPacketCacheService {

  private final Container<Config> configContainer;
  private volatile Cache<ChunkKey, ClientboundLevelChunkWithLightPacket> packetCache;
  private volatile Cache<ChunkKey, Boolean> bypassCache;
  private final ConcurrentHashMap<ChunkKey, Set<UUID>> realPlayersByChunk =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ChunkKey> playerChunkIndex = new ConcurrentHashMap<>();
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();

  @Inject
  public ChunkPacketCacheService(Container<Config> configContainer) {
    this.configContainer = configContainer;
    rebuildCaches();
  }

  public void rebuildCaches() {
    int ttl = Math.max(1, config().cacheTtlSeconds());
    int maxEntries = Math.max(128, config().cacheMaxEntries());
    long bypassMs = Math.max(250L, config().cacheBypassAfterRealInteractionMs());
    this.packetCache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttl))
            .build();
    this.bypassCache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofMillis(bypassMs))
            .build();
  }

  public ClientboundLevelChunkWithLightPacket get(UUID worldId, int chunkX, int chunkZ) {
    return get(worldId, ChunkPos.asLong(chunkX, chunkZ));
  }

  public ClientboundLevelChunkWithLightPacket get(UUID worldId, long chunkKey) {
    if (worldId == null) return null;
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    ClientboundLevelChunkWithLightPacket packet = packetCache.getIfPresent(key);
    if (packet == null) {
      misses.incrementAndGet();
      return null;
    }
    hits.incrementAndGet();
    return packet;
  }

  public void put(
      UUID worldId, int chunkX, int chunkZ, ClientboundLevelChunkWithLightPacket packet) {
    put(worldId, ChunkPos.asLong(chunkX, chunkZ), packet);
  }

  public void put(UUID worldId, long chunkKey, ClientboundLevelChunkWithLightPacket packet) {
    if (worldId == null || packet == null) return;
    packetCache.put(new ChunkKey(worldId, chunkKey), packet);
  }

  public void invalidate(UUID worldId, int chunkX, int chunkZ) {
    invalidate(worldId, ChunkPos.asLong(chunkX, chunkZ));
  }

  public void invalidate(UUID worldId, long chunkKey) {
    if (worldId == null) return;
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    packetCache.invalidate(key);
    bypassCache.put(key, Boolean.TRUE);
  }

  public boolean shouldBypass(UUID worldId, long chunkKey) {
    if (worldId == null) return true;
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    if (Boolean.TRUE.equals(bypassCache.getIfPresent(key))) return true;
    Set<UUID> players = realPlayersByChunk.get(key);
    return players != null && !players.isEmpty();
  }

  public boolean hasRealPlayers(UUID worldId, long chunkKey) {
    if (worldId == null) return false;
    Set<UUID> players = realPlayersByChunk.get(new ChunkKey(worldId, chunkKey));
    return players != null && !players.isEmpty();
  }

  public void onPlayerChunk(UUID playerId, UUID worldId, int chunkX, int chunkZ) {
    if (playerId == null || worldId == null) return;
    ChunkKey newKey = new ChunkKey(worldId, ChunkPos.asLong(chunkX, chunkZ));
    ChunkKey prev = playerChunkIndex.put(playerId, newKey);
    if (prev != null && !prev.equals(newKey)) {
      Set<UUID> prevSet = realPlayersByChunk.get(prev);
      if (prevSet != null) {
        prevSet.remove(playerId);
        if (prevSet.isEmpty()) realPlayersByChunk.remove(prev);
      }
    }
    realPlayersByChunk.computeIfAbsent(newKey, k -> ConcurrentHashMap.newKeySet()).add(playerId);
  }

  public void onPlayerQuit(UUID playerId) {
    if (playerId == null) return;
    ChunkKey key = playerChunkIndex.remove(playerId);
    if (key == null) return;
    Set<UUID> players = realPlayersByChunk.get(key);
    if (players == null) return;
    players.remove(playerId);
    if (players.isEmpty()) realPlayersByChunk.remove(key);
  }

  public long hitCount() {
    return hits.get();
  }

  public long missCount() {
    return misses.get();
  }

  public long estimatedSize() {
    return packetCache.estimatedSize();
  }

  private Config config() {
    Config cfg = configContainer.get();
    return cfg == null ? Config.empty() : cfg;
  }

  public record ChunkKey(UUID worldId, long chunkKey) {}
}
