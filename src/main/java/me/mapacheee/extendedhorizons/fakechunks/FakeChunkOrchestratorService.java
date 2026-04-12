package me.mapacheee.extendedhorizons.fakechunks;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkDispatchService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.FarPlayerTrackingService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class FakeChunkOrchestratorService {

    private static final Duration PERMISSION_CACHE_TTL = Duration.ofSeconds(5);
    private static final long PERMISSION_CACHE_MAX_SIZE = 4096L;

    private final Container<EhConfig> configContainer;
    private static final Logger LOGGER = LoggerFactory.getLogger(FakeChunkOrchestratorService.class);
    private final SessionRegistry sessionRegistry;
    private final ChunkDispatchService dispatchService;
    private final ChannelInjectionService channelInjectionService;
    private final FarPlayerTrackingService farPlayerTrackingService;
    private final Cache<UUID, PermissionCacheEntry> permissionCache;

    @Inject
    public FakeChunkOrchestratorService(
        Container<EhConfig> configContainer,
        SessionRegistry sessionRegistry,
        ChunkDispatchService dispatchService,
        ChannelInjectionService channelInjectionService,
        FarPlayerTrackingService farPlayerTrackingService
    ) {
        this.configContainer = configContainer;
        this.sessionRegistry = sessionRegistry;
        this.dispatchService = dispatchService;
        this.channelInjectionService = channelInjectionService;
        this.farPlayerTrackingService = farPlayerTrackingService;
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(PERMISSION_CACHE_MAX_SIZE)
            .expireAfterWrite(PERMISSION_CACHE_TTL)
            .build();
    }

    public void tickPlayer(Player player, long maxTimePerPlayerNanos) {
        if (player == null || !player.isOnline()) {
            return;
        }

        PlayerSession session = this.sessionRegistry.ensureFor(player, false);
        World world = player.getWorld();
        String worldName = world.getName();
        Channel channel = this.channelInjectionService.resolveChannel(player);
        if (!this.configContainer.get().fakeChunksEnabledForWorld(worldName)) {
            this.clearSessionState(channel, session);
            return;
        }
        if (channel == null || !channel.isActive()) {
            return;
        }
        this.channelInjectionService.inject(player, session);
        this.channelInjectionService.bindSession(channel, session);

        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        int targetDistance = this.resolveClientDistance(player, worldName);
        int serverDistance = this.resolveServerDistance(player);

        TickSnapshot snapshot = new TickSnapshot(
            world,
            world.getUID(),
            player.getUniqueId(),
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

        this.farPlayerTrackingService.track(
            snapshot.viewerId(),
            snapshot.worldId(),
            ChunkPos.asLong(snapshot.chunkX(), snapshot.chunkZ()),
            session,
            channel,
            snapshot.serverDistance(),
            snapshot.targetDistance()
        );

        this.dispatchService.processQueue(snapshot.world(), channel, session, snapshot.deadlineNanos());
        this.channelInjectionService.flush(channel);
    }

    private boolean preTick(PlayerSession session, int targetDistance, int serverDistance) {
        if (targetDistance <= serverDistance) {
            if (session.enabled()) {
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
        } catch (Throwable throwable) {
            LOGGER.error("Error on get player view distance", throwable);
            playerDistance = globalDistance;
        }
        if (playerDistance > 0) {
            return Math.clamp(playerDistance, 2, globalDistance);
        }
        return globalDistance;
    }

    private int resolveClientDistance(Player player, String worldName) {
        int worldDistance = this.configContainer.get().targetViewDistance(worldName);
        PermissionCacheEntry permissionSnapshot = this.resolvePermissionSnapshot(player);
        int permissionCap = permissionSnapshot.permissionCap();
        boolean hasBypass = permissionSnapshot.hasBypass();

        int effectiveCap;
        if (permissionCap > 0) {
            if (hasBypass) {
                effectiveCap = permissionCap;
            } else {
                effectiveCap = Math.min(worldDistance, permissionCap);
            }
        } else {
            effectiveCap = worldDistance;
        }

        PlayerSession session = this.sessionRegistry.get(player.getUniqueId());
        int base;
        if (session != null && session.playerOverrideDistance() > 0) {
            base = session.playerOverrideDistance();
        } else {
            base = worldDistance;
        }

        return Math.max(2, Math.min(base, effectiveCap));
    }

    private PermissionCacheEntry resolvePermissionSnapshot(Player player) {
        UUID playerId = player.getUniqueId();
        PermissionCacheEntry cached = this.permissionCache.getIfPresent(playerId);
        if (cached != null) {
            return cached;
        }

        int permissionCap = resolvePermissionCap(player);
        boolean hasBypass = player.hasPermission("extendedhorizons.bypass");
        PermissionCacheEntry updated = new PermissionCacheEntry(permissionCap, hasBypass);
        this.permissionCache.put(playerId, updated);
        return updated;
    }

    private static int resolvePermissionCap(Player player) {
        int maxFound = -1;
        for (var perm : player.getEffectivePermissions()) {
            if (!perm.getValue()) {
                continue;
            }
            String name = perm.getPermission();
            if (!name.startsWith("extendedhorizons.max.")) {
                continue;
            }
            String suffix = name.substring("extendedhorizons.max.".length());
            try {
                int value = Integer.parseInt(suffix);
                if (value > maxFound) {
                    maxFound = value;
                }
            } catch (NumberFormatException throwable) {
                LOGGER.error("Error on parse permission", throwable);
            }
        }
        return maxFound;
    }

    public void invalidatePermissionCache(UUID playerId) {
        if (playerId == null) {
            return;
        }
        this.permissionCache.invalidate(playerId);
    }

    public void invalidateAllPermissionCache() {
        this.permissionCache.invalidateAll();
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
        UUID worldId,
        UUID viewerId,
        int chunkX,
        int chunkZ,
        int targetDistance,
        int serverDistance,
        long deadlineNanos
    ) {}

    private record PermissionCacheEntry(int permissionCap, boolean hasBypass) {}
}


