package me.mapacheee.extendedhorizons.fakechunks;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.config.ConfigFacade;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkDispatchService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

@Service
public final class FakeChunkOrchestratorService {

    private final ConfigFacade configFacade;
    private final SessionRegistry sessionRegistry;
    private final ChunkDispatchService dispatchService;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public FakeChunkOrchestratorService(
            ConfigFacade configFacade,
            SessionRegistry sessionRegistry,
            ChunkDispatchService dispatchService,
            ChannelInjectionService channelInjectionService
    ) {
        this.configFacade = configFacade;
        this.sessionRegistry = sessionRegistry;
        this.dispatchService = dispatchService;
        this.channelInjectionService = channelInjectionService;
    }

    public void tickPlayer(Player player, long maxTimePerPlayerNanos) {
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
        this.channelInjectionService.inject(player, session);
        this.channelInjectionService.bindSession(channel, session);

        int chunkX = player.getLocation().getBlockX() >> 4;
        int chunkZ = player.getLocation().getBlockZ() >> 4;
        int targetDistance = this.resolveClientDistance(player, worldName);
        int serverDistance = this.resolveServerDistance(player);

        TickSnapshot snapshot = new TickSnapshot(
                world,
                world.getUID(),
                chunkX,
                chunkZ,
                targetDistance,
                serverDistance,
                System.nanoTime() + Math.max(250_000L, maxTimePerPlayerNanos)
        );
        this.channelInjectionService.executeOnEventLoop(channel, () -> this.processOnNetty(channel, session, snapshot));
    }

    private void processOnNetty(Channel channel, PlayerSession session, TickSnapshot snapshot) {
        session.setWorld(snapshot.worldId());
        session.serverViewDistance(snapshot.serverDistance());
        session.moveTo(snapshot.chunkX(), snapshot.chunkZ());

        if (!session.initiated()) {
            session.initiated(true);
            session.setChunkPos(snapshot.chunkX(), snapshot.chunkZ());
        }

        if (!this.preTick(session, snapshot.targetDistance(), snapshot.serverDistance())) {
            this.unloadSessionChunks(channel, session);
            this.syncClientRadius(channel, session, snapshot.serverDistance());
            session.unloadBvChunks();
            return;
        }

        this.syncClientCenter(channel, snapshot.chunkX(), snapshot.chunkZ());
        this.syncClientRadius(channel, session, snapshot.targetDistance());
        this.dispatchService.processQueue(snapshot.world(), channel, session, snapshot.deadlineNanos());
        this.channelInjectionService.flush(channel);
    }

    private boolean preTick(PlayerSession session, int targetDistance, int serverDistance) {
        if (targetDistance <= serverDistance) {
            if (session.enabled()) {
                // Handled by caller when disabling to ensure unload packets are actually sent.
                session.enabled(false);
            }
            return false;
        }

        if (!session.enabled() || session.distance() != targetDistance) {
            session.enabled(true);
            session.updateDistance(targetDistance);
        }
        return true;
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

    private int resolveClientDistance(Player player, String worldName) {
        return Math.max(2, this.configFacade.get().targetViewDistance(worldName));
    }

    private void clearSessionState(Channel channel, PlayerSession session) {
        if (channel == null || session == null) {
            return;
        }
        this.unloadSessionChunks(channel, session);
        this.channelInjectionService.writeBypass(channel, new ClientboundSetChunkCacheRadiusPacket(session.serverViewDistance()));
        session.unloadBvChunks();
        session.clearDispatchState();
    }

    private void unloadSessionChunks(Channel channel, PlayerSession session) {
        for (long chunkKey : session.loadedBvChunkKeys()) {
            this.dispatchService.sendUnload(channel, session, chunkKey);
        }
    }

    private record TickSnapshot(
            World world,
            java.util.UUID worldId,
            int chunkX,
            int chunkZ,
            int targetDistance,
            int serverDistance,
            long deadlineNanos
    ) {
    }
}


