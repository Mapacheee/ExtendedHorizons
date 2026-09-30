package me.mapacheee.extendedhorizons.fakechunks.prefetch;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkPayloadService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.GlobalGenerationLimiterService;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPrefetchPlan;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class ChunkPrefetchService {

  private static final int MAX_CANDIDATE_SCANS = 64;
  private static final long MAX_SNAPSHOT_AGE_NANOS = 500_000_000L;

  private final Container<EhConfig> configContainer;
  private final ChunkPayloadService payloadService;
  private final ChunkBuildCacheService cacheService;
  private final GlobalGenerationLimiterService generationLimiterService;
  private final SessionRegistry sessionRegistry;
  private final Map<UUID, PlayerPlan> plans = new ConcurrentHashMap<>();
  private final Map<ChunkKey, PendingWork> pending = new HashMap<>();
  private int remainingStarts;
  private volatile boolean stopping;

  @Inject
  public ChunkPrefetchService(
    Container<EhConfig> configContainer,
    ChunkPayloadService payloadService,
    ChunkBuildCacheService cacheService,
    GlobalGenerationLimiterService generationLimiterService,
    SessionRegistry sessionRegistry
  ) {
    this.configContainer = configContainer;
    this.payloadService = payloadService;
    this.cacheService = cacheService;
    this.generationLimiterService = generationLimiterService;
    this.sessionRegistry = sessionRegistry;
  }

  public void beginCycle(EhConfig config) {
    synchronized (this) {
      this.generationLimiterService.reset(config.maxGlobalGenerationsPerTick());
      this.remainingStarts = !this.stopping && config.prefetchEnabled() ? config.prefetchMaxStartsPerTick() : 0;
    }
    this.plans.entrySet().removeIf(entry -> {
      PlayerPlan plan = entry.getValue();
      return !config.prefetchEnabled() || plan.session.closed() || !plan.session.enabled()
        || this.sessionRegistry.get(entry.getKey()) != plan.session
        || plan.session.epoch() != plan.signature.epoch()
        || plan.signature.cacheGeneration() != this.cacheService.generation();
    });
  }

  public void process(World world, PlayerSession session, BorderBounds border, long sampledAtNanos) {
    EhConfig config = this.configContainer.get();
    if (this.stopping || !config.prefetchEnabled() || !session.enabled() || session.closed()
      || !this.cacheService.available() || System.nanoTime() - sampledAtNanos > MAX_SNAPSHOT_AGE_NANOS) {
      this.forget(session);
      return;
    }
    Long predicted = session.predictChunkKey(config.prefetchLookaheadSeconds(), config.prefetchMaxExtraDistance());
    if (predicted == null) {
      this.forget(session);
      return;
    }
    PlanSignature signature = new PlanSignature(
      session.worldId(), session.epoch(), this.cacheService.generation(), session.chunkKey(),
      predicted, session.distance(), config.prefetchMaxExtraDistance()
    );
    PlayerPlan plan = this.plans.compute(session.playerId(), (id, previous) -> {
      if (previous != null && previous.session == session && previous.signature.equals(signature)) {
        return previous;
      }
      ChunkPrefetchPlan candidates = ChunkPrefetchPlan.create(
        signature.center(), signature.predicted(), signature.distance(), signature.extraDistance(),
        -30_000_000.0d, -30_000_000.0d, 30_000_000.0d, 30_000_000.0d
      );
      return new PlayerPlan(session, signature, candidates.chunks());
    });

    if (plan.index >= plan.chunks.length) {
      plan.index = 0;
    }
    int scans = MAX_CANDIDATE_SCANS;
    while (plan.index < plan.chunks.length && scans-- > 0) {
      long chunkKey = plan.chunks[plan.index];
      ChunkKey key = new ChunkKey(signature.worldId(), chunkKey);
      if (!border.intersects(chunkKey) || this.isPending(key)
        || this.cacheService.isTemporarilyUnavailable(key.worldId(), chunkKey)
        || this.payloadService.isPrepared(world, chunkKey, config)) {
        plan.index++;
        continue;
      }
      PendingWork work = new PendingWork(key, session.playerId());
      if (!this.reserve(work, config)) {
        break;
      }
      plan.index++;
      CompletableFuture<Void> completion;
      try {
        completion = this.payloadService.prefetch(world, chunkKey, signature.cacheGeneration(), config);
      } catch (RuntimeException | Error exception) {
        this.finish(work);
        this.generationLimiterService.release();
        throw exception;
      }
      if (completion.isDone()) {
        this.generationLimiterService.release();
      }
      completion.whenComplete((ignored, throwable) -> this.finish(work));
    }
  }

  public void forget(PlayerSession session) {
    this.plans.computeIfPresent(session.playerId(), (id, plan) -> plan.session == session ? null : plan);
  }

  private synchronized boolean isPending(ChunkKey key) {
    return this.pending.containsKey(key);
  }

  private synchronized boolean reserve(PendingWork work, EhConfig config) {
    if (this.stopping || this.remainingStarts <= 0 || this.pending.containsKey(work.key())
      || this.pending.size() >= config.prefetchMaxInflightGlobal()) {
      return false;
    }
    int playerPending = 0;
    for (PendingWork other : this.pending.values()) {
      if (other.playerId().equals(work.playerId())) {
        playerPending++;
      }
    }
    if (playerPending >= config.prefetchMaxInflightPerPlayer()
      || !this.generationLimiterService.tryAcquirePrefetch()) {
      return false;
    }
    this.remainingStarts--;
    this.pending.put(work.key(), work);
    return true;
  }

  private synchronized void finish(PendingWork work) {
    this.pending.remove(work.key(), work);
  }

  @OnDisable
  public synchronized void onDisable() {
    this.stopping = true;
    this.remainingStarts = 0;
    this.plans.clear();
    this.pending.clear();
  }

  public record BorderBounds(double minX, double minZ, double maxX, double maxZ) {
    public boolean intersects(long chunkKey) {
      double x = ChunkKeyCodec.x(chunkKey) * 16.0d;
      double z = ChunkKeyCodec.z(chunkKey) * 16.0d;
      return x + 16.0d > this.minX && x < this.maxX && z + 16.0d > this.minZ && z < this.maxZ;
    }
  }

  private record PlanSignature(
    UUID worldId, long epoch, long cacheGeneration, long center, long predicted,
    int distance, int extraDistance
  ) {
  }

  private record PendingWork(ChunkKey key, UUID playerId) {
  }

  private record ChunkKey(UUID worldId, long chunkKey) {
  }

  private static final class PlayerPlan {
    private final PlayerSession session;
    private final PlanSignature signature;
    private final long[] chunks;
    private int index;

    private PlayerPlan(PlayerSession session, PlanSignature signature, long[] chunks) {
      this.session = session;
      this.signature = signature;
      this.chunks = chunks;
    }
  }
}
