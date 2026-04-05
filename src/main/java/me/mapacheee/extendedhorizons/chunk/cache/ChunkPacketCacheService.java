package me.mapacheee.extendedhorizons.chunk.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import io.netty.buffer.ByteBuf;
import com.thewinterframework.service.annotation.Service;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.world.level.ChunkPos;

@Service
public class ChunkPacketCacheService {

  private final Container<Config> configContainer;
  private volatile Cache<ChunkKey, ByteBuf> serializedCache;
  private volatile Cache<ChunkKey, ChunkBuildEntry> buildEntryCache;
  private volatile Cache<ChunkKey, Boolean> bypassCache;
  private final ConcurrentHashMap<ChunkKey, Set<UUID>> realPlayersByChunk =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ChunkKey> playerChunkIndex = new ConcurrentHashMap<>();
  private final AtomicLong serializedHits = new AtomicLong();
  private final AtomicLong serializedMisses = new AtomicLong();

  @Inject
  public ChunkPacketCacheService(Container<Config> configContainer) {
    this.configContainer = configContainer;
    rebuildCaches();
  }

  public void rebuildCaches() {
    Cache<ChunkKey, ByteBuf> previousSerialized = this.serializedCache;
    Cache<ChunkKey, ChunkBuildEntry> previousBuildEntry = this.buildEntryCache;
    if (previousSerialized != null) previousSerialized.invalidateAll();
    if (previousBuildEntry != null) previousBuildEntry.invalidateAll();
    int ttl = Math.max(1, config().cacheTtlSeconds());
    int maxEntries = Math.max(128, config().cacheMaxEntries());
    long bypassMs = Math.max(250L, config().cacheBypassAfterRealInteractionMs());
    this.serializedCache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofSeconds(ttl))
            .removalListener(
                (ChunkKey key, ByteBuf value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                  if (value != null && value.refCnt() > 0) {
                    value.release();
                  }
                })
            .build();
    this.buildEntryCache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterAccess(Duration.ofSeconds(ttl))
            .removalListener(
                (ChunkKey key,
                    ChunkBuildEntry value,
                    com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                  if (value != null) {
                    value.clear();
                  }
                })
            .build();
    this.bypassCache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(Duration.ofMillis(bypassMs))
            .build();
  }

  public ByteBuf getSerialized(UUID worldId, long chunkKey) {
    if (worldId == null) return null;
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    ByteBuf serialized = serializedCache.getIfPresent(key);
    if (serialized == null) {
      serializedMisses.incrementAndGet();
      return null;
    }
    if (!serialized.isReadable()) {
      serializedMisses.incrementAndGet();
      return null;
    }
    serializedHits.incrementAndGet();
    return serialized.retainedDuplicate();
  }

  public void putSerialized(UUID worldId, long chunkKey, ByteBuf payload) {
    if (worldId == null || payload == null || !payload.isReadable()) return;
    serializedCache.put(new ChunkKey(worldId, chunkKey), payload.retainedDuplicate());
  }

  public void invalidate(UUID worldId, long chunkKey) {
    if (worldId == null) return;
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    serializedCache.invalidate(key);
    ChunkBuildEntry entry = buildEntryCache.getIfPresent(key);
    if (entry != null) {
      entry.clear();
    }
    buildEntryCache.invalidate(key);
    bypassCache.put(key, Boolean.TRUE);
  }

  public CompletableFuture<ByteBuf> getOrStartBuildFuture(
      UUID worldId, long chunkKey, Supplier<CompletableFuture<ByteBuf>> starter) {
    if (worldId == null || starter == null) return CompletableFuture.completedFuture(null);
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    ChunkBuildEntry entry = buildEntryCache.get(key, k -> new ChunkBuildEntry());
    if (entry == null) return CompletableFuture.completedFuture(null);
    return entry.getOrStart(starter);
  }

  public int activeBuildCount() {
    Cache<ChunkKey, ChunkBuildEntry> cache = this.buildEntryCache;
    if (cache == null) return 0;
    int count = 0;
    for (ChunkBuildEntry entry : cache.asMap().values()) {
      if (entry != null && entry.hasActiveFuture()) {
        count++;
      }
    }
    return count;
  }

  public boolean shouldBypass(UUID worldId, long chunkKey) {
    if (worldId == null) return true;
    ChunkKey key = new ChunkKey(worldId, chunkKey);
    if (Boolean.TRUE.equals(bypassCache.getIfPresent(key))) return true;
    if (!config().cacheBypassWhenRealPlayers()) return false;
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
    return serializedHits.get();
  }

  public long missCount() {
    return serializedMisses.get();
  }

  public long estimatedSize() {
    return serializedCache.estimatedSize();
  }

  public long serializedHitCount() {
    return serializedHits.get();
  }

  public long serializedMissCount() {
    return serializedMisses.get();
  }

  public long estimatedSerializedSize() {
    return serializedCache.estimatedSize();
  }

  private Config config() {
    Config cfg = configContainer.get();
    return cfg == null ? Config.empty() : cfg;
  }

  private static final class ChunkBuildEntry {
    private CompletableFuture<ByteBuf> future;

    private synchronized CompletableFuture<ByteBuf> getOrStart(
        Supplier<CompletableFuture<ByteBuf>> starter) {
      if (future != null) {
        return future.thenApply(this::retainReadable);
      }
      CompletableFuture<ByteBuf> started;
      try {
        started = starter.get();
      } catch (Throwable t) {
        return CompletableFuture.completedFuture(null);
      }
      if (started == null) {
        return CompletableFuture.completedFuture(null);
      }
      started.whenComplete(
          (payload, err) -> {
            if (err != null || payload == null || !payload.isReadable()) {
              synchronized (ChunkBuildEntry.this) {
                if (future == started) {
                  if (payload != null && payload.refCnt() > 0) {
                    payload.release();
                  }
                  future = null;
                }
              }
            }
          });
      future = started;
      return started.thenApply(this::retainReadable);
    }

    private synchronized void clear() {
      CompletableFuture<ByteBuf> current = future;
      future = null;
      if (current == null) return;
      if (!current.isDone()) {
        current.whenComplete(
            (payload, err) -> {
              if (payload != null && payload.refCnt() > 0) {
                payload.release();
              }
            });
        return;
      }
      ByteBuf payload = current.getNow(null);
      if (payload != null && payload.refCnt() > 0) {
        payload.release();
      }
    }

    private synchronized boolean hasActiveFuture() {
      return future != null && !future.isDone();
    }

    private ByteBuf retainReadable(ByteBuf payload) {
      if (payload == null || !payload.isReadable()) return null;
      return payload.retainedDuplicate();
    }
  }

  public record ChunkKey(UUID worldId, long chunkKey) {}
}
