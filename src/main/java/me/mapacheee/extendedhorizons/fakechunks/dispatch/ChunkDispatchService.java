package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.runtime.ChunkBuildMetricsService;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;

import java.util.UUID;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

@Service
public final class ChunkDispatchService {

    private final Container<EhConfig> configContainer;
    private final ChunkBuildCacheService cacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final ChunkBuildMetricsService metricsService;
    private final ChunkBackend chunkBackend;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public ChunkDispatchService(
        Container<EhConfig> configContainer,
        ChunkBuildCacheService cacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService,
        ChunkBuildMetricsService metricsService,
        ChunkBackend chunkBackend,
        ChannelInjectionService channelInjectionService
    ) {
        this.configContainer = configContainer;
        this.cacheService = cacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
        this.metricsService = metricsService;
        this.chunkBackend = chunkBackend;
        this.channelInjectionService = channelInjectionService;
    }

    public void processQueue(World world, Channel channel, PlayerSession session, long deadlineNanos) {
        if (world == null || channel == null || session == null) {
            return;
        }
        EhConfig config = this.configContainer.get();
        session.configureBandwidthLimiter(
            config.bandwidthEnabled(),
            config.bandwidthBytesPerSecond(),
            config.bandwidthBurstBytes()
        );
        int chunksPerTick = config.maxSendPerCycle();
        int chunkQueueSize = config.chunkQueueSize();
        int maxInflight = config.maxInflightPerPlayer();
        int maxPending = Math.min(chunkQueueSize, maxInflight);
        int pending = this.drainCompletedEntries(world, channel, session);

        do {
            if (pending >= maxPending) {
                break;
            }

            Long chunkKey = session.pollNextChunkKey();
            if (chunkKey == null) {
                break;
            }

            int chunkX = ChunkKeyCodec.x(chunkKey);
            int chunkZ = ChunkKeyCodec.z(chunkKey);
            CompletableFuture<ByteBuf> buildFuture = this.buildChunk(world, session, chunkX, chunkZ, chunkKey);
            session.chunkQueue().addLast(new ChunkSendQueueEntry(chunkKey, buildFuture));
            pending++;
            if (--chunksPerTick <= 0) {
                break;
            }
        } while (System.nanoTime() < deadlineNanos);
    }

    private int drainCompletedEntries(World world, Channel channel, PlayerSession session) {
        int pending = 0;
        Iterator<ChunkSendQueueEntry> iterator = session.chunkQueue().iterator();
        while (iterator.hasNext()) {
            ChunkSendQueueEntry entry = iterator.next();
            if (this.checkQueueEntry(world, channel, session, entry)) {
                iterator.remove();
            } else {
                pending++;
            }
        }
        return pending;
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
        long chunkKey
    ) {
        UUID expectedWorldId = session.worldId();
        EhConfig config = this.configContainer.get();
        boolean antiXrayEnabled = config.antiXrayEnabled(world.getName());
        String antiXrayProfileHash = antiXrayEnabled
            ? this.antiXrayPayloadCacheService.resolveProfileHash(world, config)
            : null;

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
            return CompletableFuture.completedFuture(null);
        }

        boolean bypass = antiXrayEnabled || this.cacheService.shouldBypass(expectedWorldId, chunkKey);
        if (!bypass) {
            ByteBuf cached = this.cacheService.getSerialized(expectedWorldId, chunkKey);
            if (cached != null) {
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

    private boolean checkQueueEntry(World world, Channel channel, PlayerSession session, ChunkSendQueueEntry entry) {
        CompletableFuture<ByteBuf> buildFuture = entry.buildFuture();
        if (!buildFuture.isDone()) {
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
        if (!channel.isWritable()) {
            return false;
        }
        long payloadBytes = payload.readableBytes();
        if (channel.bytesBeforeUnwritable() > 0 && channel.bytesBeforeUnwritable() < payloadBytes) {
            return false;
        }
        if (!session.tryConsumeBandwidth(payloadBytes)) {
            return false;
        }
        ByteBuf toSend = payload.retainedDuplicate();
        if (!this.trySend(channel, session, world.getUID(), session.epoch(), toSend, entry.chunkKey())) {
            session.onChunkBuildFailed(entry.chunkKey());
        }
        entry.releaseFuture();
        return true;
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
        writePromise.addListener(future -> {
            if (future.isSuccess()) {
                session.onChunkSent(chunkKey);
            } else {
                session.onChunkBuildFailed(chunkKey);
            }
        });
        return true;
    }

    private boolean isSessionValid(PlayerSession session, UUID worldId, long epoch) {
        return session != null
                && worldId.equals(session.worldId())
                && session.epoch() == epoch;
    }
}
