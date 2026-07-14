package me.mapacheee.extendedhorizons.fakechunks;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkDispatchService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.FarPlayerTrackingService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.netty.PacketIdRegistry;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.UUID;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class FakeChunkOrchestratorService {

    private static final Duration DEFAULT_PERMISSION_TTL = Duration.ofSeconds(5);
    private static final long DEFAULT_PERMISSION_MAX_SIZE = 512L;
    private static final int MIN_DISTANCE = 2;
    private static final int DEFAULT_VIEW_DISTANCE = 10;
    private static final String PERMISSION_BYPASS = "extendedhorizons.bypass";
    private static final String PERMISSION_PREFIX = "extendedhorizons.max.";

    private final Container<EhConfig> configContainer;
    private static final Logger LOGGER = LoggerFactory.getLogger(FakeChunkOrchestratorService.class);
    private final SessionRegistry sessionRegistry;
    private final ChunkDispatchService dispatchService;
    private final ChannelInjectionService channelInjectionService;
    private final FarPlayerTrackingService farPlayerTrackingService;
    private final FarPlayerCacheService farPlayerCacheService;
    private Cache<UUID, PermissionCacheEntry> permissionCache;

    @Inject
    public FakeChunkOrchestratorService(
        Container<EhConfig> configContainer,
        SessionRegistry sessionRegistry,
        ChunkDispatchService dispatchService,
        ChannelInjectionService channelInjectionService,
        FarPlayerTrackingService farPlayerTrackingService,
        FarPlayerCacheService farPlayerCacheService
    ) {
        this.configContainer = configContainer;
        this.sessionRegistry = sessionRegistry;
        this.dispatchService = dispatchService;
        this.channelInjectionService = channelInjectionService;
        this.farPlayerTrackingService = farPlayerTrackingService;
        this.farPlayerCacheService = farPlayerCacheService;
        this.rebuildPermissionCache();
    }

    public void rebuildPermissionCache() {
        EhConfig config = this.configContainer.get();
        long maxSize = config.permissionCacheEntries();
        int ttlSeconds = config.permissionCacheTtlSeconds();
        Duration ttl = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : DEFAULT_PERMISSION_TTL;
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttl)
            .build();
    }

    public void tickPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        World world = player.getWorld();
        String worldName = world.getName();

        PlayerSession session = this.sessionRegistry.ensureFor(player, false);
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
        int targetDistance = this.resolveClientDistance(player, session, worldName);
        int serverDistance = this.resolveServerDistance(player);

        boolean chunkChanged = session.hasChunkChanged(chunkX, chunkZ);
        boolean distanceChanged = (session.lastAdvertisedDistance() != targetDistance || session.distance() != targetDistance);
        boolean farPlayersEnabled = this.configContainer.get().farPlayersEnabled();
        int moveTicks = this.configContainer.get().farPlayerMoveTicks();
        boolean isFarPlayerTick = farPlayersEnabled && session.enabled() && (Bukkit.getCurrentTick() % moveTicks == 0);
        boolean needsQueueProcessing = !session.chunkQueue().isEmpty();

        boolean shouldTick = !session.initiated()
            || chunkChanged
            || distanceChanged
            || needsQueueProcessing
            || isFarPlayerTick;

        if (!shouldTick) {
            return;
        }

        List<FarPlayerState> visibleCandidates = null;
        boolean shouldUpdateFarPlayers = farPlayersEnabled && (chunkChanged || distanceChanged || !session.initiated() || isFarPlayerTick);
        if (shouldUpdateFarPlayers) {
            Collection<FarPlayerState> candidates = this.farPlayerCacheService.getNearbyPlayers(
                world.getUID(), chunkX, chunkZ, targetDistance
            );
            if (!candidates.isEmpty()) {
                visibleCandidates = new ArrayList<>(candidates.size());
                for (FarPlayerState state : candidates) {
                    if (state.uuid().equals(player.getUniqueId())) {
                        continue;
                    }
                    Player target = Bukkit.getPlayer(state.uuid());
                    if (target != null && target.isOnline() && player.canSee(target)) {
                        visibleCandidates.add(state);
                    }
                }
            }
        }

        TickSnapshot snapshot = new TickSnapshot(
            world,
            world.getUID(),
            player.getUniqueId(),
            chunkX,
            chunkZ,
            loc.getYaw(),
            targetDistance,
            serverDistance,
            visibleCandidates
        );
        this.channelInjectionService.executeOnEventLoop(channel, () -> this.processOnNetty(channel, session, snapshot));
    }

    private void processOnNetty(Channel channel, PlayerSession session, TickSnapshot snapshot) {
        session.setWorld(snapshot.worldId());
        session.serverViewDistance(snapshot.serverDistance());
        session.moveTo(snapshot.chunkX(), snapshot.chunkZ(), snapshot.yaw());
        for (long key : session.drainPendingUnloads()) {
            this.dispatchService.sendUnload(channel, session, key);
        }

        if (!session.initiated()) {
            session.initiated(true);
            session.setChunkPos(snapshot.chunkX(), snapshot.chunkZ());
        }

        if (this.configContainer.get().debugEnabled()) {
            LOGGER.info(
                "EH tick player={} world={} center=({}, {}) targetDistance={} serverDistance={} enabled={} initiated={}",
                snapshot.viewerId(), snapshot.world().getName(), snapshot.chunkX(), snapshot.chunkZ(),
                snapshot.targetDistance(), snapshot.serverDistance(), session.enabled(), session.initiated()
            );
        }

        if (!this.preTick(session, snapshot.targetDistance(), snapshot.serverDistance())) {
            this.farPlayerTrackingService.clearTracked(channel, session);
            this.unloadSessionChunks(channel, session);
            this.syncClientRadius(channel, session, snapshot.serverDistance());
            session.unloadEhChunks();
            return;
        }

        this.syncClientCenter(channel, session, snapshot.chunkX(), snapshot.chunkZ());
        this.syncClientRadius(channel, session, snapshot.targetDistance());

        if (this.configContainer.get().farPlayersEnabled() && snapshot.visibleCandidates() != null) {
            this.farPlayerTrackingService.track(
                snapshot.viewerId(),
                ChunkKeyCodec.pack(snapshot.chunkX(), snapshot.chunkZ()),
                session,
                channel,
                snapshot.targetDistance(),
                snapshot.visibleCandidates()
            );
        } else if (!this.configContainer.get().farPlayersEnabled()) {
            this.farPlayerTrackingService.clearTracked(channel, session);
        }

        if (!PacketIdRegistry.hasLevelChunkWithLightId()) {
            PacketIdRegistry.resolveFromEncoder(channel);
            if (!PacketIdRegistry.hasLevelChunkWithLightId()) {
                this.channelInjectionService.flush(channel);
                return;
            }
        }
        this.dispatchService.processQueue(snapshot.world(), channel, session);
        this.channelInjectionService.flush(channel);
    }

    private boolean preTick(PlayerSession session, int targetDistance, int serverDistance) {
        if (targetDistance <= serverDistance) {
            if (session.enabled()) {
                session.enabled(false);
            }
            if (this.configContainer.get().debugEnabled()) {
                LOGGER.info(
                    "EH preTick disabled: targetDistance={} <= serverDistance={} (no fake chunks)",
                    targetDistance, serverDistance
                );
            }
            return false;
        }

        if (!session.enabled() || session.distance() != targetDistance) {
            session.enabled(true);
            session.updateDistance(targetDistance);
            if (this.configContainer.get().debugEnabled()) {
                LOGGER.info("EH preTick enabled: distance set to {}", targetDistance);
            }
        }
        return true;
    }

    private void syncClientCenter(Channel channel, PlayerSession session, int chunkX, int chunkZ) {
        long key = ChunkKeyCodec.pack(chunkX, chunkZ);
        if (session.lastAdvertisedChunkKey() == key) {
            return;
        }
        this.channelInjectionService.writeBypass(channel, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
        session.lastAdvertisedChunkKey(key);
    }

    private void syncClientRadius(Channel channel, PlayerSession session, int targetDistance) {
        if (session.lastAdvertisedDistance() == targetDistance) {
            return;
        }
        this.channelInjectionService.writeBypass(channel, new ClientboundSetChunkCacheRadiusPacket(targetDistance));
        session.lastAdvertisedDistance(targetDistance);
    }

    private int resolveServerDistance(Player player) {
        int globalDistance = Math.max(MIN_DISTANCE, Bukkit.getViewDistance());
        int playerDistance;
        try {
            playerDistance = player.getViewDistance();
        } catch (Throwable throwable) {
            LOGGER.error("Error on get player view distance", throwable);
            playerDistance = globalDistance;
        }
        if (playerDistance > 0) {
            return Math.clamp(playerDistance, MIN_DISTANCE, globalDistance);
        }
        return globalDistance;
    }

    private int resolveClientDistance(Player player, PlayerSession session, String worldName) {
        if (worldName == null) {
            return DEFAULT_VIEW_DISTANCE;
        }

        int worldDistance = this.configContainer.get().targetViewDistance(worldName);
        PermissionCacheEntry permissionSnapshot = this.resolvePermissionSnapshot(player, session);
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

        int base;
        if (session != null && session.playerOverrideDistance() > 0) {
            base = session.playerOverrideDistance();
        } else {
            base = worldDistance;
        }

        int target = Math.min(base, effectiveCap);
        int clientRequestedDistance = -1;
        try {
            clientRequestedDistance = player.getClientViewDistance();
        } catch (LinkageError e) {
            clientRequestedDistance = player.getViewDistance();
        }
        if (clientRequestedDistance > 0) {
            target = Math.min(target, clientRequestedDistance);
        }
        return Math.max(MIN_DISTANCE, target);
    }

    private PermissionCacheEntry resolvePermissionSnapshot(Player player, PlayerSession session) {
        long now = System.nanoTime();
        int cachedCap = session.cachedPermissionCap();
        if (cachedCap != -2 && now < session.permissionCacheExpiryNanos()) {
            return new PermissionCacheEntry(cachedCap, session.cachedHasBypass());
        }

        int permissionCap = resolvePermissionCap(player);
        boolean hasBypass = player.hasPermission(PERMISSION_BYPASS);

        session.cachedPermissionCap(permissionCap);
        session.cachedHasBypass(hasBypass);
        int ttlSeconds = this.configContainer.get().permissionCacheTtlSeconds();
        long ttlNanos = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds).toNanos() : DEFAULT_PERMISSION_TTL.toNanos();
        session.permissionCacheExpiryNanos(now + ttlNanos);

        UUID playerId = player.getUniqueId();
        PermissionCacheEntry updated = new PermissionCacheEntry(permissionCap, hasBypass);
        this.permissionCache.put(playerId, updated);

        return updated;
    }

    private static final int MAX_PERMISSION_CAP = 100;
    private static final String[] PERMISSION_STRINGS = new String[MAX_PERMISSION_CAP + 1];

    static {
        for (int i = 1; i <= MAX_PERMISSION_CAP; i++) {
            PERMISSION_STRINGS[i] = PERMISSION_PREFIX + i;
        }
    }

    private static int resolvePermissionCap(Player player) {
        for (int i = MAX_PERMISSION_CAP; i >= 1; i--) {
            if (player.hasPermission(PERMISSION_STRINGS[i])) {
                return i;
            }
        }
        return -1;
    }

    public void invalidatePermissionCache(UUID playerId) {
        if (playerId == null) {
            return;
        }
        this.permissionCache.invalidate(playerId);
        PlayerSession session = this.sessionRegistry.get(playerId);
        if (session != null) {
            session.cachedPermissionCap(-2);
        }
    }

    public void invalidateAllPermissionCache() {
        this.permissionCache.invalidateAll();
        this.sessionRegistry.forEachSession(session -> session.cachedPermissionCap(-2));
    }

    private void clearSessionState(@Nullable Channel channel, @Nullable PlayerSession session) {
        if (channel == null || session == null) {
            return;
        }
        this.farPlayerTrackingService.clearTracked(channel, session);
        this.unloadSessionChunks(channel, session);
        int radius = session.serverViewDistance();
        if (radius > 0) {
            this.channelInjectionService.writeBypass(channel, new ClientboundSetChunkCacheRadiusPacket(radius));
        }
        session.unloadEhChunks();
        session.clearDispatchState();
        this.channelInjectionService.flush(channel);
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
        float yaw,
        int targetDistance,
        int serverDistance,
        Collection<FarPlayerState> visibleCandidates
    ) {}

    private record PermissionCacheEntry(int permissionCap, boolean hasBypass) {}
}

