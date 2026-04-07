package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.ConfigFacade;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public final class ChunkDispatchService {

    private final ConfigFacade configFacade;
    private final ChunkBuildCacheService cacheService;
    private final ChunkBackend chunkBackend;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public ChunkDispatchService(
            ConfigFacade configFacade,
            ChunkBuildCacheService cacheService,
            ChunkBackend chunkBackend,
            ChannelInjectionService channelInjectionService
    ) {
        this.configFacade = configFacade;
        this.cacheService = cacheService;
        this.chunkBackend = chunkBackend;
        this.channelInjectionService = channelInjectionService;
    }

    public void processQueue(World world, Channel channel, PlayerSession session, boolean boosted) {
        if (world == null || channel == null || session == null) {
            return;
        }
        this.drainPendingSends(session);

        int baseSendCap = this.configFacade.get().maxSendPerCycle();
        int baseInflightCap = this.configFacade.get().maxInflightPerPlayer();
        int sendCap = boosted ? Math.min(8, baseSendCap + 2) : baseSendCap;
        int inflightCap = boosted ? Math.min(10, baseInflightCap + 2) : baseInflightCap;
        long budgetNanos = this.configFacade.get().dispatchTimeBudgetNanos();
        long deadline = System.nanoTime() + (boosted ? Math.round(budgetNanos * 1.35d) : budgetNanos);
        int inflightCount = session.inflightChunks().size();

        int sent = 0;
        while (sent < sendCap
                && inflightCount < inflightCap
                && System.nanoTime() < deadline) {
            Long chunkKey = session.pendingQueue().pollFirst();
            if (chunkKey == null) {
                break;
            }
            session.queuedChunks().remove(chunkKey);
            if (session.sentChunks().contains(chunkKey) || !session.inflightChunks().add(chunkKey)) {
                continue;
            }

            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            UUID worldId = session.worldId();
            long epoch = session.epoch();
            CompletableFuture<Boolean> sendFuture = this.buildAndSend(world, channel, session, worldId, epoch, chunkX, chunkZ, chunkKey);
            session.pendingSendQueue().addLast(new ChunkSendQueueEntry(chunkKey, sendFuture));
            sent++;
            inflightCount++;
        }
    }

    public void sendUnload(Channel channel, PlayerSession session, long chunkKey) {
        if (channel == null || session == null) {
            return;
        }
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);

        int centerX = ChunkPos.getX(session.lastChunkKey());
        int centerZ = ChunkPos.getZ(session.lastChunkKey());
        int safeRadius = session.serverViewDistance();
        int chebyshev = Math.max(Math.abs(chunkX - centerX), Math.abs(chunkZ - centerZ));
        if (chebyshev <= safeRadius) {
            // Never unload chunks still owned by vanilla server radius.
            return;
        }

        this.channelInjectionService.writeBypass(channel, new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkX, chunkZ)));
        session.sentChunks().remove(chunkKey);
        session.queuedChunks().remove(chunkKey);
        session.inflightChunks().remove(chunkKey);
    }

    private CompletableFuture<Boolean> buildAndSend(
            World world,
            Channel channel,
            PlayerSession session,
            UUID expectedWorldId,
            long expectedEpoch,
            int chunkX,
            int chunkZ,
            long chunkKey
    ) {
        if (this.cacheService.isTemporarilyUnavailable(expectedWorldId, chunkKey)) {
            return CompletableFuture.completedFuture(false);
        }

        boolean bypass = this.cacheService.shouldBypass(expectedWorldId, chunkKey);
        if (!bypass) {
            ByteBuf cached = this.cacheService.getSerialized(expectedWorldId, chunkKey);
            if (cached != null) {
                return CompletableFuture.completedFuture(this.trySend(channel, session, expectedWorldId, expectedEpoch, cached, chunkKey));
            }
        }

        return this.cacheService.getOrStartBuildFuture(
                        expectedWorldId,
                        chunkKey,
                        () -> this.chunkBackend.buildChunkPayload(
                                world,
                                chunkX,
                                chunkZ,
                                this.configFacade.get().generateMissingChunks(),
                                (worldRef, cx, cz, runnable) -> {
                                    ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
                                    if (plugin == null || !plugin.isEnabled()) {
                                        return false;
                                    }
                                    return FoliaTaskUtil.runAtChunk(worldRef, cx, cz, plugin, runnable);
                                }
                        )
                )
                .thenApply(payload -> {
                    if (payload == null) {
                        this.cacheService.markUnavailable(expectedWorldId, chunkKey);
                        return false;
                    }
                    return this.trySend(channel, session, expectedWorldId, expectedEpoch, payload, chunkKey);
                })
                .exceptionally(throwable -> false);
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
        boolean sent = this.channelInjectionService.writeBypass(channel, payload);
        if (!sent) {
            ReferenceCountUtil.release(payload);
        }
        if (sent) {
            session.sentChunks().add(chunkKey);
        }
        return sent;
    }

    private boolean isSessionValid(PlayerSession session, UUID worldId, long epoch) {
        return session != null
                && worldId.equals(session.worldId())
                && session.epoch() == epoch;
    }

    private void drainPendingSends(PlayerSession session) {
        if (session.pendingSendQueue().isEmpty()) {
            return;
        }
        int queueSize = session.pendingSendQueue().size();
        int desiredChecks = Math.max(8, this.configFacade.get().maxInflightPerPlayer() * 2);
        int checks = Math.min(desiredChecks, queueSize);
        for (int i = 0; i < checks; i++) {
            ChunkSendQueueEntry entry = session.pendingSendQueue().pollFirst();
            if (entry == null) {
                break;
            }
            CompletableFuture<Boolean> sendFuture = entry.sendFuture();
            if (sendFuture == null || !sendFuture.isDone()) {
                session.pendingSendQueue().addLast(entry);
                continue;
            }
            session.inflightChunks().remove(entry.chunkKey());
            Boolean sent = sendFuture.getNow(Boolean.FALSE);
            if (!Boolean.TRUE.equals(sent)) {
                session.sentChunks().remove(entry.chunkKey());
            }
        }
    }
}

