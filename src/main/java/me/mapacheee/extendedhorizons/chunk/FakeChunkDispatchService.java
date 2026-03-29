package me.mapacheee.extendedhorizons.chunk;

import com.thewinterframework.configurate.Container;
import java.util.BitSet;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

public class FakeChunkDispatchService {

  public interface Hooks {
    boolean isSessionValid(Player player, UUID expectedWorldId, long expectedEpoch);

    void sendPacketForSession(
        Player player, Packet<?> packet, UUID expectedWorldId, long expectedEpoch);

    boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable);

    void recordChunkLatency(long startedNs);

    void inc(UUID playerId, String metric);
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

  private final Container<Config> configContainer;
  private final ChunkPacketCacheService chunkPacketCacheService;
  private final FakeChunkRefreshCoordinator refreshCoordinator;
  private final State state;

  public FakeChunkDispatchService(
      Container<Config> configContainer,
      ChunkPacketCacheService chunkPacketCacheService,
      FakeChunkRefreshCoordinator refreshCoordinator,
      State state) {
    this.configContainer = configContainer;
    this.chunkPacketCacheService = chunkPacketCacheService;
    this.refreshCoordinator = refreshCoordinator;
    this.state = state;
  }

  public void processQueue(
      Player player,
      World world,
      UUID playerId,
      PlayerChunkTracker tracker,
      long expectedEpoch,
      Hooks hooks) {
    if (!state.enabled().get()) return;
    if (player == null || !player.isOnline()) return;
    if (world == null || playerId == null || tracker == null || hooks == null) return;
    UUID expectedWorldId = world.getUID();

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

    int sentThisCycle = 0;
    while (sentThisCycle < config().maxSendPerCycle()
        && inflight.get() < config().maxInflightPerPlayer()) {
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
    if (!chunkPacketCacheService.shouldBypass(expectedWorldId, chunkKey)) {
      ClientboundLevelChunkWithLightPacket cached =
          chunkPacketCacheService.get(expectedWorldId, chunkKey);
      if (cached != null) {
        hooks.sendPacketForSession(player, cached, expectedWorldId, expectedEpoch);
        if (hooks.isSessionValid(player, expectedWorldId, expectedEpoch)) {
          tracker.markChunkSent(x, z);
          refreshCoordinator.addSubscription(player.getUniqueId(), expectedWorldId, chunkKey);
          hooks.inc(player.getUniqueId(), "fake_sent_cache");
          hooks.recordChunkLatency(startedNs);
          return CompletableFuture.completedFuture(true);
        }
      }
    }
    return world
        .getChunkAtAsync(x, z, true)
        .thenCompose(
            chunk -> {
              if (!hooks.isSessionValid(player, expectedWorldId, expectedEpoch)) {
                return CompletableFuture.completedFuture(false);
              }
              if (chunk == null) {
                hooks.inc(player.getUniqueId(), "chunk_async_null");
                return CompletableFuture.completedFuture(false);
              }
              CompletableFuture<Boolean> future = new CompletableFuture<>();
              boolean scheduled =
                  hooks.runAtChunk(
                      world,
                      x,
                      z,
                      () -> {
                        if (!state.enabled().get()
                            || !hooks.isSessionValid(player, expectedWorldId, expectedEpoch)) {
                          future.complete(false);
                          return;
                        }
                        try {
                          ChunkAccess access = ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);
                          if (!(access instanceof LevelChunk nmsChunk)) {
                            hooks.inc(player.getUniqueId(), "chunk_not_full");
                            future.complete(false);
                            return;
                          }
                          LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();
                          BitSet[] lightMasks = getLightMasks(nmsChunk);
                          ClientboundLevelChunkWithLightPacket packet =
                              new ClientboundLevelChunkWithLightPacket(
                                  nmsChunk, lightEngine, lightMasks[0], lightMasks[1], true);
                          chunkPacketCacheService.put(expectedWorldId, chunkKey, packet);
                          hooks.sendPacketForSession(
                              player, packet, expectedWorldId, expectedEpoch);
                          if (!hooks.isSessionValid(player, expectedWorldId, expectedEpoch)) {
                            future.complete(false);
                            return;
                          }
                          tracker.markChunkSent(x, z);
                          refreshCoordinator.addSubscription(
                              player.getUniqueId(), expectedWorldId, chunkKey);
                          hooks.inc(player.getUniqueId(), "fake_sent");
                          future.complete(true);
                        } catch (Throwable t) {
                          state
                              .logger()
                              .error(
                                  "Failed live-chunk packet {},{} for {}",
                                  x,
                                  z,
                                  player.getName(),
                                  t);
                          future.complete(false);
                        }
                      });
              if (!scheduled) {
                future.complete(false);
              }
              return future;
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

  private BitSet[] getLightMasks(LevelChunk chunk) {
    var sections = chunk.getSections();
    int sectionCount = sections.length;
    BitSet skyLight = new BitSet(sectionCount + 2);
    BitSet blockLight = new BitSet(sectionCount + 2);
    for (int i = 0; i < sectionCount; i++) {
      var section = sections[i];
      if (section != null && !section.hasOnlyAir()) {
        skyLight.set(i + 1);
        blockLight.set(i + 1);
      }
    }
    return new BitSet[] {skyLight, blockLight};
  }

  private Config config() {
    Config cfg = configContainer.get();
    return cfg == null ? Config.empty() : cfg;
  }
}
