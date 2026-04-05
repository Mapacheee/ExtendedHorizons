package me.mapacheee.extendedhorizons.chunk;

import com.thewinterframework.configurate.Container;
import io.netty.buffer.ByteBuf;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.mapacheee.extendedhorizons.chunk.backend.ChunkDataBackend;
import me.mapacheee.extendedhorizons.chunk.backend.ChunkDataBackend.ChunkDataSource;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

public class FakeChunkDispatchService {

  private static final int MAX_GLOBAL_PACKET_BUILDS = 96;

  public interface Hooks {
    boolean isSessionValid(Player player, UUID expectedWorldId, long expectedEpoch);

    CompletableFuture<Boolean> sendSerializedForSession(
        Player player, ByteBuf payload, UUID expectedWorldId, long expectedEpoch);

    boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable);

    void recordChunkLatency(long startedNs);

    void inc(UUID playerId, String metric);

    void recordQueuePollNanos(long nanos);

    void onChunkLoadedCacheHit();

    void onChunkGenerate();

    void onChunkDiskReadHit();

    void recordChunkSerializeNanos(long nanos);

    void onChunkDropBackpressure();

    void onSerializedCacheHit();

    void onSerializedCacheMiss();

    void onSerializedPathAttempt();

    void onSerializedPathSuccess();

    void onSerializedPathFallback();
  }

  public record State(
      Map<UUID, Deque<Long>> pendingQueues,
      Map<UUID, Set<Long>> queuedSets,
      Map<UUID, AtomicInteger> inflightCounts,
      Map<UUID, Set<Long>> inflightKeys,
      AtomicBoolean enabled,
      Logger logger) {}

  private record ChunkSendRequest(
      Player player,
      World world,
      int x,
      int z,
      PlayerChunkTracker tracker,
      UUID expectedWorldId,
      long expectedEpoch) {}

  public record DispatchLimits(int maxSendPerCycle, int maxInflightPerPlayer) {}

  private final Container<Config> configContainer;
  private final ChunkPacketCacheService chunkPacketCacheService;
  private final FakeChunkRefreshCoordinator refreshCoordinator;
  private final ChunkDataBackend chunkDataBackend;
  private final State state;
  private final Map<ChunkPacketCacheService.ChunkKey, Long> unavailableUntilMs =
      new ConcurrentHashMap<>();
  private final AtomicLong backpressureDrops = new AtomicLong();
  private volatile long unavailableCleanupLastMs = 0L;

  public FakeChunkDispatchService(
      Container<Config> configContainer,
      ChunkPacketCacheService chunkPacketCacheService,
      FakeChunkRefreshCoordinator refreshCoordinator,
      ChunkDataBackend chunkDataBackend,
      State state) {
    this.configContainer = configContainer;
    this.chunkPacketCacheService = chunkPacketCacheService;
    this.refreshCoordinator = refreshCoordinator;
    this.chunkDataBackend = chunkDataBackend;
    this.state = state;
  }

  public void processQueue(
      Player player,
      World world,
      UUID playerId,
      PlayerChunkTracker tracker,
      long expectedEpoch,
      Hooks hooks) {
    processQueue(
        player,
        world,
        playerId,
        tracker,
        expectedEpoch,
        hooks,
        new DispatchLimits(config().maxSendPerCycle(), config().maxInflightPerPlayer()));
  }

  public void processQueue(
      Player player,
      World world,
      UUID playerId,
      PlayerChunkTracker tracker,
      long expectedEpoch,
      Hooks hooks,
      DispatchLimits limits) {
    if (!state.enabled().get()) return;
    if (player == null || !player.isOnline()) return;
    if (world == null || playerId == null || tracker == null || hooks == null) return;
    UUID expectedWorldId = world.getUID();
    cleanupUnavailableIfNeeded(System.currentTimeMillis());

    Deque<Long> queue = state.pendingQueues().get(playerId);
    if (queue == null) return;

    Set<Long> queued =
        state
            .queuedSets()
            .computeIfAbsent(playerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    AtomicInteger inflight =
        state.inflightCounts().computeIfAbsent(playerId, k -> new AtomicInteger(0));
    Set<Long> inflightSet =
        state
            .inflightKeys()
            .computeIfAbsent(playerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());

    long queueStartedNs = System.nanoTime();
    long queueBudgetNs = config().dispatchTimeBudgetNanos();
    int effectiveSendCap = Math.max(1, limits == null ? config().maxSendPerCycle() : limits.maxSendPerCycle());
    int effectiveInflightCap =
        Math.max(1, limits == null ? config().maxInflightPerPlayer() : limits.maxInflightPerPlayer());
    int sentThisCycle = 0;
    while (sentThisCycle < effectiveSendCap && inflight.get() < effectiveInflightCap) {
      if (System.nanoTime() - queueStartedNs >= queueBudgetNs) {
        break;
      }
      if (chunkPacketCacheService.activeBuildCount() >= MAX_GLOBAL_PACKET_BUILDS) {
        backpressureDrops.incrementAndGet();
        hooks.onChunkDropBackpressure();
        break;
      }
      Long key = queue.pollFirst();
      if (key == null) break;
      queued.remove(key);
      if (tracker.getSentChunks().contains(key)) continue;

      int cx = ChunkPos.getX(key);
      int cz = ChunkPos.getZ(key);
      inflightSet.add(key);
      inflight.incrementAndGet();
      ChunkSendRequest request =
          new ChunkSendRequest(player, world, cx, cz, tracker, expectedWorldId, expectedEpoch);
      sendFakeChunk(request, hooks)
          .whenComplete(
              (ok, err) -> {
                inflight.decrementAndGet();
                inflightSet.remove(key);
              });
      sentThisCycle++;
    }
    hooks.recordQueuePollNanos(System.nanoTime() - queueStartedNs);
  }

  private CompletableFuture<Boolean> sendFakeChunk(ChunkSendRequest request, Hooks hooks) {
    long startedNs = System.nanoTime();
    if (!state.enabled().get()) return CompletableFuture.completedFuture(false);
    Player player = request.player();
    World world = request.world();
    int x = request.x();
    int z = request.z();
    PlayerChunkTracker tracker = request.tracker();
    UUID expectedWorldId = request.expectedWorldId();
    long expectedEpoch = request.expectedEpoch();
    if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(false);
    if (world == null) return CompletableFuture.completedFuture(false);
    if (!hooks.isSessionValid(player, expectedWorldId, expectedEpoch))
      return CompletableFuture.completedFuture(false);
    long chunkKey = ChunkPos.asLong(x, z);
    ChunkPacketCacheService.ChunkKey cacheKey =
        new ChunkPacketCacheService.ChunkKey(expectedWorldId, chunkKey);
    Long blockedUntil = unavailableUntilMs.get(cacheKey);
    long now = System.currentTimeMillis();
    if (blockedUntil != null && blockedUntil > now) {
      hooks.inc(player.getUniqueId(), "chunk_unavailable_skip");
      hooks.recordChunkLatency(startedNs);
      return CompletableFuture.completedFuture(false);
    }
    if (!chunkPacketCacheService.shouldBypass(expectedWorldId, chunkKey)) {
      ByteBuf serialized = chunkPacketCacheService.getSerialized(expectedWorldId, chunkKey);
      if (serialized != null && serialized.isReadable()) {
        hooks.onChunkLoadedCacheHit();
        hooks.onSerializedCacheHit();
        hooks.onSerializedPathAttempt();
        unavailableUntilMs.remove(cacheKey);
        return hooks.sendSerializedForSession(player, serialized, expectedWorldId, expectedEpoch)
            .thenApply(
                sent -> {
                  if (!sent) {
                    hooks.onSerializedPathFallback();
                    return false;
                  }
                  hooks.onSerializedPathSuccess();
                  tracker.markChunkSent(x, z);
                  refreshCoordinator.addSubscription(player.getUniqueId(), expectedWorldId, chunkKey);
                  hooks.inc(player.getUniqueId(), "fake_sent_cache");
                  return true;
                })
            .exceptionally(
                e -> {
                  hooks.onSerializedPathFallback();
                  return false;
                })
            .whenComplete((ok, err) -> hooks.recordChunkLatency(startedNs));
      }
      hooks.onSerializedCacheMiss();
    } else {
      hooks.onSerializedCacheMiss();
    }
    return getOrBuildPayload(world, expectedWorldId, x, z, chunkKey, hooks)
        .thenCompose(
            payload -> {
              if (payload == null || !payload.isReadable()) {
                unavailableUntilMs.put(cacheKey, System.currentTimeMillis() + 2000L);
                return CompletableFuture.completedFuture(false);
              }
              unavailableUntilMs.remove(cacheKey);
              if (!hooks.isSessionValid(player, expectedWorldId, expectedEpoch)) {
                payload.release();
                return CompletableFuture.completedFuture(false);
              }
              hooks.onSerializedPathAttempt();
              return hooks.sendSerializedForSession(player, payload, expectedWorldId, expectedEpoch)
                  .thenApply(
                      sent -> {
                        if (!sent) {
                          hooks.onSerializedPathFallback();
                          return false;
                        }
                        hooks.onSerializedPathSuccess();
                        tracker.markChunkSent(x, z);
                        refreshCoordinator.addSubscription(
                            player.getUniqueId(), expectedWorldId, chunkKey);
                        hooks.inc(player.getUniqueId(), "fake_sent_serialized");
                        return true;
                      });
            })
        .exceptionally(
            e -> {
              state
                  .logger()
                  .error("Failed to send fake chunk {},{} to {}", x, z, player.getName(), e);
              return false;
            })
        .whenComplete((ok, err) -> hooks.recordChunkLatency(startedNs));
  }

  private CompletableFuture<ByteBuf> getOrBuildPayload(
      World world, UUID expectedWorldId, int x, int z, long chunkKey, Hooks hooks) {
    if (world == null || expectedWorldId == null || hooks == null) {
      return CompletableFuture.completedFuture(null);
    }
    return chunkPacketCacheService.getOrStartBuildFuture(
        expectedWorldId,
        chunkKey,
        () -> {
          long serializeStartedNs = System.nanoTime();
          return chunkDataBackend
              .loadOrBuildPacket(
                  world,
                  x,
                  z,
                  config().fakeChunksGenerateMissingChunks(),
                  (w, cx, cz, runnable) -> hooks.runAtChunk(w, cx, cz, runnable))
              .thenApply(
                  result -> {
                    if (result == null) return null;
                    if (result.source() == ChunkDataSource.GENERATED) {
                      hooks.onChunkGenerate();
                    } else if (result.source() == ChunkDataSource.DISK) {
                      hooks.onChunkDiskReadHit();
                    }
                    return result.serializedPayload();
                  })
              .whenComplete(
                  (payload, err) -> {
                    hooks.recordChunkSerializeNanos(System.nanoTime() - serializeStartedNs);
                    if (payload != null && payload.isReadable()) {
                      chunkPacketCacheService.putSerialized(expectedWorldId, chunkKey, payload);
                    }
                  });
        });
  }

  private Config config() {
    Config cfg = configContainer.get();
    return cfg == null ? Config.empty() : cfg;
  }

  public int packetBuildInFlightSize() {
    return chunkPacketCacheService.activeBuildCount();
  }

  public long backpressureDrops() {
    return backpressureDrops.get();
  }

  public int unavailableEntryCount() {
    cleanupUnavailableIfNeeded(System.currentTimeMillis());
    return unavailableUntilMs.size();
  }

  public boolean isTemporarilyUnavailable(UUID worldId, long chunkKey, long nowMs) {
    if (worldId == null) return false;
    cleanupUnavailableIfNeeded(nowMs);
    ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
    Long blockedUntil = unavailableUntilMs.get(key);
    if (blockedUntil == null) return false;
    return blockedUntil > nowMs;
  }

  private void cleanupUnavailableIfNeeded(long nowMs) {
    if (unavailableUntilMs.isEmpty()) return;
    if (nowMs - unavailableCleanupLastMs < 10_000L) return;
    unavailableCleanupLastMs = nowMs;
    unavailableUntilMs.entrySet().removeIf(entry -> entry == null || entry.getValue() == null || entry.getValue() <= nowMs);
  }
}
