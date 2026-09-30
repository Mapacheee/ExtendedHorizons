package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkPayloadService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public final class ChunkDispatchService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ChunkDispatchService.class);
  private static final long BUILD_TIMEOUT_NANOS = 5_000_000_000L;

  private final Container<EhConfig> configContainer;
  private final ChunkBuildCacheService cacheService;
  private final ChunkPayloadService payloadService;
  private final ChannelInjectionService channelInjectionService;
  private final GlobalGenerationLimiterService generationLimiterService;

  @Inject
  public ChunkDispatchService(
    Container<EhConfig> configContainer,
    ChunkBuildCacheService cacheService,
    ChunkPayloadService payloadService,
    ChannelInjectionService channelInjectionService,
    GlobalGenerationLimiterService generationLimiterService
  ) {
    this.configContainer = configContainer;
    this.cacheService = cacheService;
    this.payloadService = payloadService;
    this.channelInjectionService = channelInjectionService;
    this.generationLimiterService = generationLimiterService;
  }

  public void processQueue(World world, Channel channel, PlayerSession session) {
    if (world == null || channel == null || session == null || session.closed()) {
      return;
    }
    EhConfig config = this.configContainer.get();
    if (!this.cacheService.available()) {
      return;
    }
    boolean debug = config.debugEnabled();
    session.configureBandwidthLimiter(
      config.bandwidthEnabled(),
      config.bandwidthBytesPerSecond(),
      config.bandwidthBurstBytes()
    );
    int chunksPerTick = config.maxSendPerCycle();
    int maxInflight = config.maxInflightPerPlayer();
    int maxQueueSize = config.chunkQueueSize();
    session.prioritizeChunkQueue();
    int inFlight = this.drainCompletedEntries(world, channel, session, chunksPerTick);

    if (debug) {
      LOGGER.info(
        "EH dispatch: inFlight={} queueSize={} maxInflight={} maxQueueSize={} chunksPerTick={}",
        inFlight, session.chunkQueue().size(), maxInflight, maxQueueSize, chunksPerTick
      );
    }

    while (true) {
      if (inFlight >= maxInflight) {
        break;
      }
      if (session.chunkQueue().size() >= maxQueueSize) {
        break;
      }
      if (!this.generationLimiterService.tryAcquire()) {
        break;
      }
      Long chunkKey = session.pollNextChunkKey();
      if (chunkKey == null) {
        this.generationLimiterService.release();
        break;
      }
      int chunkX = ChunkKeyCodec.x(chunkKey);
      int chunkZ = ChunkKeyCodec.z(chunkKey);
      UUID expectedWorldId = session.worldId();
      long expectedEpoch = session.epoch();
      long cacheGeneration = this.cacheService.generation();
      CompletableFuture<ByteBuf> buildFuture = this.payloadService.prepare(
        world,
        expectedWorldId,
        chunkX,
        chunkZ,
        chunkKey,
        cacheGeneration,
        config,
        session.isChunkRefresh(chunkKey)
      );
      if (buildFuture.isDone()) {
        this.generationLimiterService.release();
      }
      ChunkSendQueueEntry queueEntry = new ChunkSendQueueEntry(
        chunkKey,
        expectedWorldId,
        expectedEpoch,
        cacheGeneration,
        buildFuture
      );
      if (!session.enqueueChunk(queueEntry, expectedWorldId, expectedEpoch)) {
        queueEntry.releaseFuture();
        session.onChunkBuildFailed(chunkKey);
        break;
      }
      inFlight++;
      if (--chunksPerTick <= 0) {
        break;
      }
    }
  }

  private int drainCompletedEntries(World world, Channel channel, PlayerSession session, int maxSendPerCycle) {
    DispatchProgress progress = new DispatchProgress();
    session.chunkQueue().removeIf(entry -> {
      if (!this.isQueueEntryValid(world, session, entry)) {
        session.onChunkBuildFailed(entry.chunkKey());
        entry.releaseFuture();
        return true;
      }
      if (!entry.buildFuture().isDone()) {
        if (System.nanoTime() - entry.queuedAtNanos() > BUILD_TIMEOUT_NANOS) {
          session.onChunkBuildFailed(entry.chunkKey());
          this.cacheService.markUnavailable(
            entry.worldId(),
            entry.chunkKey(),
            entry.cacheGeneration()
          );
          entry.releaseFuture();
          return true;
        }
        progress.inFlight++;
        return false;
      }
      if ((progress.sent >= maxSendPerCycle || progress.bandwidthBlocked)
        && !entry.buildFuture().isCompletedExceptionally()) {
        progress.inFlight++;
        return false;
      }
      boolean processed = this.checkQueueEntry(world, channel, session, entry, progress);
      if (!processed) {
        progress.inFlight++;
      }
      return processed;
    });
    return progress.inFlight;
  }

  public void sendUnload(Channel channel, PlayerSession session, long chunkKey) {
    if (channel == null || session == null) {
      return;
    }
    int chunkX = ChunkKeyCodec.x(chunkKey);
    int chunkZ = ChunkKeyCodec.z(chunkKey);

    this.channelInjectionService.writeBypass(channel,
      new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkX, chunkZ)));
    session.onChunkUnloaded(chunkKey);
  }

  private boolean checkQueueEntry(World world, Channel channel, PlayerSession session, ChunkSendQueueEntry entry,
    DispatchProgress progress) {
    CompletableFuture<ByteBuf> buildFuture = entry.buildFuture();
    if (!this.isQueueEntryValid(world, session, entry)) {
      session.onChunkBuildFailed(entry.chunkKey());
      entry.releaseFuture();
      return true;
    }
    if (buildFuture.isCompletedExceptionally()) {
      session.onChunkBuildFailed(entry.chunkKey());
      entry.releaseFuture();
      return true;
    }
    ByteBuf payload = entry.acquirePayload();
    if (payload == null) {
      session.onChunkBuildFailed(entry.chunkKey());
      entry.releaseFuture();
      return true;
    }
    boolean removeEntry = false;
    try {
      if (!this.isChunkStillInRange(session, entry.chunkKey())) {
        session.onChunkBuildFailed(entry.chunkKey());
        removeEntry = true;
        return true;
      }
      if (shouldDeferWrite(channel)) {
        return false;
      }
      long payloadBytes = payload.readableBytes();
      if (!session.tryConsumeBandwidth(payloadBytes)) {
        progress.bandwidthBlocked = true;
        return false;
      }
      ByteBuf toSend;
      try {
        toSend = EncodedPayloadCopy.copy(channel.alloc(), payload);
      } catch (RuntimeException exception) {
        LOGGER.warn("Failed to copy chunk payload {} for dispatch", entry.chunkKey(), exception);
        session.onChunkBuildFailed(entry.chunkKey());
        removeEntry = true;
        return true;
      }
      long sendAttempt = session.beginChunkSend(entry.chunkKey());
      if (sendAttempt == 0L) {
        ReferenceCountUtil.release(toSend);
        removeEntry = true;
        return true;
      }
      if (this.trySend(
        channel,
        session,
        entry.worldId(),
        entry.sessionEpoch(),
        toSend,
        entry.chunkKey(),
        sendAttempt
      )) {
        progress.sent++;
      } else {
        session.onChunkSendFailed(entry.chunkKey(), sendAttempt);
      }
      removeEntry = true;
      return true;
    } finally {
      payload.release();
      if (removeEntry) {
        entry.releaseFuture();
      }
    }
  }

  private boolean trySend(
    Channel channel,
    PlayerSession session,
    UUID expectedWorldId,
    long expectedEpoch,
    ByteBuf payload,
    long chunkKey,
    long sendAttempt
  ) {
    if (!this.isSessionValid(session, expectedWorldId, expectedEpoch)) {
      ReferenceCountUtil.release(payload);
      return false;
    }
    ChannelPromise writePromise = this.channelInjectionService.writeEncodedFuture(channel, payload);
    if (writePromise == null || (writePromise.isDone() && !writePromise.isSuccess())) {
      return false;
    }
    UUID capturedWorldId = expectedWorldId;
    long capturedEpoch = expectedEpoch;
    long capturedChunkKey = chunkKey;
    long capturedSendAttempt = sendAttempt;
    writePromise.addListener(future -> {
      if (!this.isSessionValid(session, capturedWorldId, capturedEpoch)) {
        return;
      }
      if (future.isSuccess()) {
        session.onChunkSent(capturedChunkKey, capturedSendAttempt);
      } else {
        session.onChunkSendFailed(capturedChunkKey, capturedSendAttempt);
      }
    });
    return true;
  }

  private boolean isSessionValid(PlayerSession session, UUID worldId, long epoch) {
    return session != null
      && !session.closed()
      && worldId.equals(session.worldId())
      && session.epoch() == epoch;
  }

  private boolean isQueueEntryValid(World world, PlayerSession session, ChunkSendQueueEntry entry) {
    return world.getUID().equals(entry.worldId())
      && entry.cacheGeneration() == this.cacheService.generation()
      && this.isSessionValid(session, entry.worldId(), entry.sessionEpoch());
  }

  private boolean isChunkStillInRange(PlayerSession session, long chunkKey) {
    int chunkX = ChunkKeyCodec.x(chunkKey);
    int chunkZ = ChunkKeyCodec.z(chunkKey);
    long centerKey = session.chunkKey();
    int centerX = ChunkKeyCodec.x(centerKey);
    int centerZ = ChunkKeyCodec.z(centerKey);
    return ChunkPlannerService.isWithinRange(chunkX - centerX, chunkZ - centerZ, session.distance());
  }

  static boolean shouldDeferWrite(Channel channel) {
    return !channel.isWritable();
  }

  private static final class DispatchProgress {
    private int inFlight;
    private int sent;
    private boolean bandwidthBlocked;
  }
}
