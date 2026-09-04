package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.runtime.ChunkBuildMetricsService;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
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
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final ChunkBuildMetricsService metricsService;
    private final ChunkBackend chunkBackend;
    private final ChannelInjectionService channelInjectionService;
    private final GlobalGenerationLimiterService generationLimiterService;

    @Inject
    public ChunkDispatchService(
        Container<EhConfig> configContainer,
        ChunkBuildCacheService cacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService,
        ChunkBuildMetricsService metricsService,
        ChunkBackend chunkBackend,
        ChannelInjectionService channelInjectionService,
        GlobalGenerationLimiterService generationLimiterService
    ) {
        this.configContainer = configContainer;
        this.cacheService = cacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
        this.metricsService = metricsService;
        this.chunkBackend = chunkBackend;
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
        int inFlight = this.drainCompletedEntries(world, channel, session, chunksPerTick);

        if (debug) {
            LOGGER.info(
                "EH dispatch: inFlight={} queueSize={} maxInflight={} maxQueueSize={} chunksPerTick={}",
                inFlight, session.chunkQueue().size(), maxInflight, maxQueueSize, chunksPerTick
            );
        }

        while (true) {
            if (inFlight >= maxInflight) { break; }
            if (session.chunkQueue().size() >= maxQueueSize) { break; }
            if (!this.generationLimiterService.tryAcquire()) { break; }
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
            CompletableFuture<ByteBuf> buildFuture = this.buildChunk(
                world,
                expectedWorldId,
                chunkX,
                chunkZ,
                chunkKey,
                cacheGeneration,
                config
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
            if (--chunksPerTick <= 0) { break; }
        }
    }

    private int drainCompletedEntries(World world, Channel channel, PlayerSession session, int maxSendPerCycle) {
        int[] counters = {0, 0};
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
                counters[0]++;
                return false;
            }
            if (counters[1] >= maxSendPerCycle && !entry.buildFuture().isCompletedExceptionally()) {
                counters[0]++;
                return false;
            }
            boolean processed = this.checkQueueEntry(world, channel, session, entry, counters);
            if (!processed) {
                counters[0]++;
            }
            return processed;
        });
        return counters[0];
    }

    public void sendUnload(Channel channel, PlayerSession session, long chunkKey) {
        if (channel == null || session == null) {
            return;
        }
        int chunkX = ChunkKeyCodec.x(chunkKey);
        int chunkZ = ChunkKeyCodec.z(chunkKey);

        this.channelInjectionService.writeBypass(channel, new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkX, chunkZ)));
        session.onChunkUnloaded(chunkKey);
    }

    private CompletableFuture<ByteBuf> buildChunk(
        World world,
        UUID expectedWorldId,
        int chunkX,
        int chunkZ,
        long chunkKey,
        long cacheGeneration,
        EhConfig config
    ) {
        long antiXrayCacheGeneration = this.antiXrayPayloadCacheService.generation();
        boolean antiXrayEnabled = config.antiXrayEnabled(world.getName());
        String antiXrayProfileHash = antiXrayEnabled
            ? this.antiXrayPayloadCacheService.resolveProfileHash(world, config)
            : null;

        if (config.debugEnabled()) {
            LOGGER.info(
                "EH buildChunk: chunk=({}, {}) antiXrayEnabled={} bypassCache={}",
                chunkX, chunkZ, antiXrayEnabled,
                antiXrayEnabled || this.cacheService.shouldBypass(expectedWorldId, chunkKey)
            );
        }

        if (antiXrayProfileHash != null) {
            ByteBuf antiXrayCached = this.antiXrayPayloadCacheService.get(
                expectedWorldId,
                chunkKey,
                antiXrayProfileHash,
                config.serializerMode(),
                antiXrayCacheGeneration
            );
            if (antiXrayCached != null) {
                this.metricsService.recordAntiXrayFinalCacheHit();
                return CompletableFuture.completedFuture(antiXrayCached);
            }
            this.metricsService.recordAntiXrayFinalCacheMiss();
        }

        if (this.cacheService.isTemporarilyUnavailable(expectedWorldId, chunkKey)) {
            if (config.debugEnabled()) {
                LOGGER.info("EH buildChunk skip: cache temporarily unavailable for {}", chunkKey);
            }
            return CompletableFuture.completedFuture(null);
        }

        boolean bypass = antiXrayEnabled || this.cacheService.shouldBypass(expectedWorldId, chunkKey);
        if (!bypass) {
            ByteBuf cached = this.cacheService.getSerialized(expectedWorldId, chunkKey);
            if (cached != null) {
                if (config.debugEnabled()) {
                    LOGGER.info("EH buildChunk cache hit for {}", chunkKey);
                }
                return CompletableFuture.completedFuture(cached);
            }
        }

        CompletableFuture<ByteBuf> source = this.cacheService.getOrStartBuildFuture(
            expectedWorldId,
            chunkKey,
            cacheGeneration,
            () -> this.chunkBackend.buildChunkPayload(
                world,
                chunkX,
                chunkZ,
                config.generateMissingChunks(),
                (worldRef, cx, cz, runnable) -> {
                    ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
                    if (plugin == null || !plugin.isEnabled()) {
                        return false;
                    }
                    return FoliaTaskUtil.runAtChunk(worldRef, cx, cz, plugin, runnable);
                }
            )
        );
        CompletableFuture<ByteBuf> result = new CompletableFuture<>();
        source.whenComplete((payload, throwable) -> {
            if (throwable != null) {
                result.complete(null);
                return;
            } else if (payload == null) {
                this.cacheService.markUnavailable(expectedWorldId, chunkKey, cacheGeneration);
                if (config.debugEnabled()) {
                    LOGGER.info("EH buildChunk failed: null payload for {}", chunkKey);
                }
            } else if (antiXrayProfileHash != null) {
                try {
                    this.antiXrayPayloadCacheService.put(
                        expectedWorldId,
                        chunkKey,
                        antiXrayProfileHash,
                        config.serializerMode(),
                        antiXrayCacheGeneration,
                        payload
                    );
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to cache anti-xray payload for chunk {}", chunkKey, exception);
                }
            }
            if (!result.complete(payload)) {
                ReferenceCountUtil.release(payload);
            }
        });
        result.whenComplete((payload, throwable) -> {
            if (result.isCancelled()) {
                source.cancel(false);
            }
        });
        return result;
    }

    private boolean checkQueueEntry(World world, Channel channel, PlayerSession session, ChunkSendQueueEntry entry, int[] counters) {
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
                counters[1]++;
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
}
