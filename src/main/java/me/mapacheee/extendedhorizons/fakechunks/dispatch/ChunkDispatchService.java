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
import me.mapacheee.extendedhorizons.fakechunks.cache.CompressedPayloadCacheService;
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
  private static final long GENERATION_TIMEOUT_NANOS = 60_000_000_000L;

  private final Container<EhConfig> configContainer;
  private final ChunkBuildCacheService cacheService;
  private final ChunkPayloadService payloadService;
  private final ChannelInjectionService channelInjectionService;
  private final CompressedPayloadCacheService compressedCacheService;

  @Inject
  public ChunkDispatchService(
    Container<EhConfig> configContainer,
    ChunkBuildCacheService cacheService,
    ChunkPayloadService payloadService,
    ChannelInjectionService channelInjectionService,
    CompressedPayloadCacheService compressedCacheService
  ) {
    this.configContainer = configContainer;
    this.cacheService = cacheService;
    this.payloadService = payloadService;
    this.channelInjectionService = channelInjectionService;
    this.compressedCacheService = compressedCacheService;
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
    long buildTimeoutNanos = config.generateMissingChunks() ? GENERATION_TIMEOUT_NANOS : BUILD_TIMEOUT_NANOS;
    int inFlight = this.drainCompletedEntries(world, channel, session, chunksPerTick, buildTimeoutNanos);
    int refreshStarts = Math.max(1, chunksPerTick / 4);

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
      Long chunkKey = refreshStarts > 0 ? session.pollNextRefreshChunkKey() : null;
      if (chunkKey != null) {
        refreshStarts--;
      } else {
        chunkKey = session.pollNextChunkKey();
        if (chunkKey == null && refreshStarts == 0) {
          chunkKey = session.pollNextRefreshChunkKey();
        }
      }
      if (chunkKey == null) {
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
        config
      );
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

  private int drainCompletedEntries(World world, Channel channel, PlayerSession session,
    int maxSendPerCycle, long buildTimeoutNanos) {
    DispatchProgress progress = new DispatchProgress();
    progress.compression = this.channelInjectionService.compressionRoute(channel);
    session.chunkQueue().removeIf(entry -> {
      if (!this.isQueueEntryValid(world, session, entry)) {
        session.onChunkBuildFailed(entry.chunkKey());
        entry.releaseFuture();
        return true;
      }
      if (!entry.buildFuture().isDone()) {
        if (System.nanoTime() - entry.queuedAtNanos() > buildTimeoutNanos) {
          if (this.configContainer.get().debugEnabled()) {
            LOGGER.info("EH chunk build timed out after {} seconds for chunk [{}, {}] in world '{}'",
              buildTimeoutNanos / 1_000_000_000L, ChunkKeyCodec.x(entry.chunkKey()),
              ChunkKeyCodec.z(entry.chunkKey()), world.getName());
          }
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
      EncodedChunk toSend;
      try {
        ByteBuf compressed = null;
        byte[] fingerprint = null;
        if (progress.compression != null) {
          CompressedPayloadCacheService.Request request = this.compressedCacheService.request(payload,
            progress.compression.threshold());
          if (request.pending()) {
            return false;
          }
          compressed = request.payload();
          fingerprint = request.fingerprint();
        }
        try {
          if (this.isQueueEntryValid(world, session, entry)
            && session.suppressUnchangedRefresh(entry.chunkKey(), fingerprint)) {
            removeEntry = true;
            return true;
          }
          if (!session.tryConsumeBandwidth(payloadBytes)) {
            progress.bandwidthBlocked = true;
            return false;
          }
          toSend = new EncodedChunk(EncodedPayloadCopy.copy(channel.alloc(), compressed == null ? payload : compressed),
            compressed == null ? null : progress.compression, fingerprint);
        } finally {
          ReferenceCountUtil.release(compressed);
        }
      } catch (RuntimeException exception) {
        LOGGER.warn("Failed to copy chunk payload {} for dispatch", entry.chunkKey(), exception);
        session.onChunkBuildFailed(entry.chunkKey());
        removeEntry = true;
        return true;
      }
      long sendAttempt = session.beginChunkSend(entry.chunkKey());
      if (sendAttempt == 0L) {
        ReferenceCountUtil.release(toSend.payload());
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
    EncodedChunk payload,
    long chunkKey,
    long sendAttempt
  ) {
    if (!this.isSessionValid(session, expectedWorldId, expectedEpoch)) {
      ReferenceCountUtil.release(payload.payload());
      return false;
    }
    ChannelPromise writePromise = payload.route() == null
      ? this.channelInjectionService.writeEncodedFuture(channel, payload.payload())
      : this.channelInjectionService.writeEncodedFuture(channel, payload.payload(), payload.route());
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
        session.onChunkSent(capturedChunkKey, capturedSendAttempt, payload.fingerprint());
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
    private ChannelInjectionService.CompressionRoute compression;
    private int inFlight;
    private int sent;
    private boolean bandwidthBlocked;
  }

  private record EncodedChunk(ByteBuf payload, ChannelInjectionService.CompressionRoute route, byte[] fingerprint) {
  }
}
