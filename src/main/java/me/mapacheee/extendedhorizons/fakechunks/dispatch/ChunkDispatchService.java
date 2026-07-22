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
        if (world == null || channel == null || session == null) {
            return;
        }
        EhConfig config = this.configContainer.get();
        boolean debug = config.debugEnabled();
        session.configureBandwidthLimiter(
            config.bandwidthEnabled(),
            config.bandwidthBytesPerSecond(),
            config.bandwidthBurstBytes()
        );
        int chunksPerTick = config.maxSendPerCycle();
        int maxInflight = config.maxInflightPerPlayer();
        int maxQueueSize = config.chunkQueueSize();
        int inFlight = this.drainCompletedEntries(world, channel, session, config, chunksPerTick, debug);

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
            CompletableFuture<ByteBuf> buildFuture = this.buildChunk(world, session, chunkX, chunkZ, chunkKey, config);
            if (buildFuture.isDone()) {
                this.generationLimiterService.release();
            }
            session.chunkQueue().addLast(new ChunkSendQueueEntry(chunkKey, buildFuture));
            inFlight++;
            if (--chunksPerTick <= 0) { break; }
        }
    }

    private int drainCompletedEntries(World world, Channel channel, PlayerSession session, EhConfig config, int maxSendPerCycle, boolean debug) {
        int[] counters = {0, 0};
        session.chunkQueue().removeIf(entry -> {
            if (!entry.buildFuture().isDone()) {
                counters[0]++;
                return false;
            }
            if (counters[1] >= maxSendPerCycle && !entry.buildFuture().isCompletedExceptionally()) {
                counters[0]++;
                return false;
            }
            boolean processed = this.checkQueueEntry(world, channel, session, config, entry, counters, debug);
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
        PlayerSession session,
        int chunkX,
        int chunkZ,
        long chunkKey,
        EhConfig config
    ) {
        UUID expectedWorldId = session.worldId();
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
                config.serializerMode()
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

        return this.cacheService.getOrStartBuildFuture(
            expectedWorldId,
            chunkKey,
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
        )
        .whenComplete((payload, throwable) -> {
            if (payload == null && throwable == null) {
                this.cacheService.markUnavailable(expectedWorldId, chunkKey);
                if (config.debugEnabled()) {
                    LOGGER.info("EH buildChunk failed: null payload for {}", chunkKey);
                }
                return;
            }
            if (throwable == null && antiXrayProfileHash != null) {
                this.antiXrayPayloadCacheService.put(
                    expectedWorldId,
                    chunkKey,
                    antiXrayProfileHash,
                    config.serializerMode(),
                    payload
                );
            }
        })
        .exceptionally(throwable -> null);
    }

    private boolean checkQueueEntry(World world, Channel channel, PlayerSession session, EhConfig config, ChunkSendQueueEntry entry, int[] counters, boolean debug) {
        CompletableFuture<ByteBuf> buildFuture = entry.buildFuture();
        if (!buildFuture.isDone()) {
            if (System.nanoTime() - entry.queuedAtNanos() > BUILD_TIMEOUT_NANOS) {
                session.onChunkBuildFailed(entry.chunkKey());
                this.cacheService.markUnavailable(world.getUID(), entry.chunkKey());
                entry.releaseFuture();
                return true;
            }
            return false;
        }
        if (buildFuture.isCompletedExceptionally()) {
            session.onChunkBuildFailed(entry.chunkKey());
            entry.releaseFuture();
            return true;
        }
        ByteBuf payload = buildFuture.getNow(null);
        if (payload == null) {
            session.onChunkBuildFailed(entry.chunkKey());
            entry.releaseFuture();
            return true;
        }
        payload.retain();
        boolean removeEntry = false;
        try {
            if (!this.isChunkStillInRange(session, entry.chunkKey())) {
                session.onChunkBuildFailed(entry.chunkKey());
                removeEntry = true;
                return true;
            }
            if (!channel.isWritable()) {
                return false;
            }
            long payloadBytes = payload.readableBytes();
            try {
                long beforeUnwritable = channel.bytesBeforeUnwritable();
                if (beforeUnwritable > 0 && beforeUnwritable < payloadBytes) {
                    return false;
                }
            } catch (AbstractMethodError ignored) {}
            if (!session.tryConsumeBandwidth(payloadBytes)) {
                return false;
            }
            ByteBuf toSend = payload.retainedDuplicate();
            if (this.trySend(channel, session, world.getUID(), session.epoch(), toSend, entry.chunkKey())) {
                counters[1]++;
            } else {
                session.onChunkBuildFailed(entry.chunkKey());
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
        long chunkKey
    ) {
        if (!this.isSessionValid(session, expectedWorldId, expectedEpoch)) {
            ReferenceCountUtil.release(payload);
            return false;
        }
        ChannelPromise writePromise = this.channelInjectionService.writeBypassFuture(channel, payload);
        if (writePromise == null) {
            ReferenceCountUtil.release(payload);
            return false;
        }
        session.onChunkSent(chunkKey);
        UUID capturedWorldId = expectedWorldId;
        long capturedEpoch = expectedEpoch;
        long capturedChunkKey = chunkKey;
        writePromise.addListener(future -> {
            if (!future.isSuccess() && this.isSessionValid(session, capturedWorldId, capturedEpoch)) {
                session.onChunkBuildFailed(capturedChunkKey);
            }
        });
        return true;
    }

    private boolean isSessionValid(PlayerSession session, UUID worldId, long epoch) {
        return session != null
                && worldId.equals(session.worldId())
                && session.epoch() == epoch;
    }

    private boolean isChunkStillInRange(PlayerSession session, long chunkKey) {
        int chunkX = ChunkKeyCodec.x(chunkKey);
        int chunkZ = ChunkKeyCodec.z(chunkKey);
        long centerKey = session.chunkKey();
        int centerX = ChunkKeyCodec.x(centerKey);
        int centerZ = ChunkKeyCodec.z(centerKey);
        return ChunkPlannerService.isWithinRange(chunkX - centerX, chunkZ - centerZ, session.distance());
    }
}
