package me.mapacheee.extendedhorizons.fakechunks;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.config.ConfigFacade;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkDispatchService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

@Service
public final class FakeChunkOrchestratorService {

    private final ConfigFacade configFacade;
    private final SessionRegistry sessionRegistry;
    private final ChunkPlannerService plannerService;
    private final ChunkDispatchService dispatchService;
    private final ChunkBuildCacheService cacheService;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public FakeChunkOrchestratorService(
            ConfigFacade configFacade,
            SessionRegistry sessionRegistry,
            ChunkPlannerService plannerService,
            ChunkDispatchService dispatchService,
            ChunkBuildCacheService cacheService,
            ChannelInjectionService channelInjectionService
    ) {
        this.configFacade = configFacade;
        this.sessionRegistry = sessionRegistry;
        this.plannerService = plannerService;
        this.dispatchService = dispatchService;
        this.cacheService = cacheService;
        this.channelInjectionService = channelInjectionService;
    }

    public void tickPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        PlayerSession session = this.sessionRegistry.ensureFor(player, false);
        World world = player.getWorld();
        String worldName = world.getName();
        if (!this.configFacade.get().fakeChunksEnabledForWorld(worldName)) {
            Channel channel = this.channelInjectionService.resolveChannel(player);
            this.clearSessionState(channel, session);
            return;
        }

        Channel channel = this.channelInjectionService.resolveChannel(player);
        if (channel == null || !channel.isActive()) {
            return;
        }

        int chunkX = player.getLocation().getBlockX() >> 4;
        int chunkZ = player.getLocation().getBlockZ() >> 4;
        boolean moved = session.hasChunkChanged(chunkX, chunkZ);
        int movementDx = 0;
        int movementDz = 0;
        if (moved) {
            movementDx = chunkX - ChunkPos.getX(session.lastChunkKey());
            movementDz = chunkZ - ChunkPos.getZ(session.lastChunkKey());
        }
        Vector look = player.getLocation().getDirection();
        double headingX = look == null ? 0.0d : look.getX();
        double headingZ = look == null ? 0.0d : look.getZ();
        double headingLenSq = headingX * headingX + headingZ * headingZ;
        if (headingLenSq > 1.0E-6d) {
            double inv = 1.0d / Math.sqrt(headingLenSq);
            headingX *= inv;
            headingZ *= inv;
        } else {
            headingX = 0.0d;
            headingZ = 0.0d;
        }
        this.channelInjectionService.inject(player);
        this.syncClientCenter(channel, chunkX, chunkZ);
        if (moved) {
            session.setLastChunk(chunkX, chunkZ);
            // BetterView-like behavior: restart nearby-first iteration when player changes chunk.
            session.plannerCursor(0);
        }

        int targetDistance = this.configFacade.get().targetViewDistance(worldName);
        this.syncClientRadius(channel, session, targetDistance);

        long now = System.currentTimeMillis();
        int queueSize = this.configFacade.get().chunkQueueSize();
        int buffered = session.pendingQueue().size() + session.inflightChunks().size();
        int refillThreshold = Math.max(6, queueSize / 2);
        boolean queueNeedsRefill = buffered < refillThreshold;
        boolean starvation = buffered <= 2;
        boolean boosted = moved || player.isGliding();
        boolean shouldPlan = moved
                || queueNeedsRefill
                || now - session.lastPlanAtMs() >= this.configFacade.get().forcePlanIntervalMs();

        int baseAdds = Math.max(2, this.configFacade.get().maxSendPerCycle());
        int maxAdds = Math.max(baseAdds, Math.min(queueSize, queueSize - buffered + baseAdds));
        if (boosted) {
            maxAdds = Math.min(queueSize + 2, maxAdds + 2);
        }

        TickSnapshot snapshot = new TickSnapshot(
                world,
                world.getUID(),
                chunkX,
                chunkZ,
                targetDistance,
                this.resolveServerDistance(player),
                now,
                shouldPlan,
                maxAdds,
                movementDx,
                movementDz,
                headingX,
                headingZ,
                starvation,
                boosted,
                moved
        );
        this.channelInjectionService.executeOnEventLoop(channel, () -> this.processOnNetty(channel, session, snapshot));
    }

    private void processOnNetty(Channel channel, PlayerSession session, TickSnapshot snapshot) {
        if (snapshot.shouldPlan()) {
            this.replan(
                    channel,
                    session,
                    snapshot.chunkX(),
                    snapshot.chunkZ(),
                    snapshot.now(),
                    snapshot.targetDistance(),
                    snapshot.serverDistance(),
                    snapshot.maxAdds(),
                    snapshot.worldId(),
                    snapshot.movementDx(),
                    snapshot.movementDz(),
                    snapshot.headingX(),
                    snapshot.headingZ(),
                    snapshot.starvation(),
                    snapshot.moved()
            );
        }
        this.dispatchService.processQueue(snapshot.world(), channel, session, snapshot.boosted());
        this.channelInjectionService.flush(channel);
    }

    private void replan(
            Channel channel,
            PlayerSession session,
            int chunkX,
            int chunkZ,
            long now,
            int targetDistance,
            int serverDistance,
            int maxAdds,
            UUID worldId,
            int movementDx,
            int movementDz,
            double headingX,
            double headingZ,
            boolean starvation,
            boolean moved
    ) {
        session.serverViewDistance(serverDistance);
        ChunkPlannerService.PlanInput input = new ChunkPlannerService.PlanInput(
                chunkX,
                chunkZ,
                targetDistance,
                serverDistance,
                this.configFacade.get().safeSquareFactor(),
                session.plannerCursor(),
                Math.max(16, maxAdds),
                movementDx,
                movementDz,
                headingX,
                headingZ,
                session.sentChunks(),
                session.pendingQueue(),
                session.queuedChunks(),
                session.inflightChunks(),
                starvation ? null : chunkKey -> this.cacheService.isTemporarilyUnavailable(worldId, chunkKey)
        );
        ChunkPlannerService.PlanResult planResult = this.plannerService.build(input);
        session.plannerCursor(planResult.nextCursor());
        session.lastPlanAtMs(now);

        for (Long chunkKey : planResult.chunksToUnload()) {
            this.dispatchService.sendUnload(channel, session, chunkKey);
        }

        if (moved) {
            session.pendingQueue().clear();
            session.queuedChunks().clear();
            session.pendingQueue().addAll(planResult.rebuiltQueue());
            session.queuedChunks().addAll(planResult.rebuiltQueued());
        }

        for (Long chunkKey : planResult.toAdd()) {
            if (session.queuedChunks().add(chunkKey)) {
                session.pendingQueue().addLast(chunkKey);
            }
        }
    }

    private void syncClientCenter(Channel channel, int chunkX, int chunkZ) {
        this.channelInjectionService.writeBypass(channel, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
    }

    private void syncClientRadius(Channel channel, PlayerSession session, int targetDistance) {
        if (session.lastAdvertisedDistance() == targetDistance) {
            return;
        }
        this.channelInjectionService.writeBypass(channel, new ClientboundSetChunkCacheRadiusPacket(targetDistance));
        session.lastAdvertisedDistance(targetDistance);
    }

    private int resolveServerDistance(Player player) {
        int globalDistance = Math.max(2, Bukkit.getViewDistance());
        int playerDistance;
        try {
            playerDistance = player.getViewDistance();
        } catch (Throwable ignored) {
            playerDistance = globalDistance;
        }
        if (playerDistance > 0) {
            return Math.clamp(playerDistance, 2, globalDistance);
        }
        return globalDistance;
    }

    private void clearSessionState(Channel channel, PlayerSession session) {
        if (channel == null || session == null) {
            return;
        }
        for (Long chunkKey : session.sentChunks()) {
            this.dispatchService.sendUnload(channel, session, chunkKey);
        }
        session.clearDispatchState();
        session.plannerCursor(0);
        session.lastPlanAtMs(0L);
    }

    private record TickSnapshot(
            World world,
            java.util.UUID worldId,
            int chunkX,
            int chunkZ,
            int targetDistance,
            int serverDistance,
            long now,
            boolean shouldPlan,
            int maxAdds,
            int movementDx,
            int movementDz,
            double headingX,
            double headingZ,
            boolean starvation,
            boolean boosted,
            boolean moved
    ) {
    }
}


