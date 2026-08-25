package me.mapacheee.extendedhorizons.fakechunks.farplayers;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public final class FarPlayerTrackingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarPlayerTrackingService.class);
    private static final int FAR_ENTITY_ID_RANGE_START = 1_000_000_000;
    private static final int FAR_ENTITY_ID_RANGE_END   = 1_900_000_000;
    private static final int FAR_ENTITY_ID_ALLOCATION_ATTEMPTS = 10_000;
    private static final double FAR_RADIUS_PADDING = 0.35d;
    private static final int CHUNK_SHIFT = 4;
    private static final int ALLOCATION_FAILED = -1;

    private final Container<EhConfig> configContainer;
    private final FarPlayerCacheService cacheService;
    private final FarPlayerBackend backend;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public FarPlayerTrackingService(
        Container<EhConfig> configContainer,
        FarPlayerCacheService cacheService,
        FarPlayerBackend backend,
        ChannelInjectionService channelInjectionService
    ) {
        this.configContainer = configContainer;
        this.cacheService = cacheService;
        this.backend = backend;
        this.channelInjectionService = channelInjectionService;
    }

    public void track(
        UUID viewerId,
        long viewerChunkKey,
        PlayerSession session,
        Channel channel,
        int targetDistance,
        Collection<FarPlayerState> candidates
    ) {
        int viewerChunkX = ChunkKeyCodec.x(viewerChunkKey);
        int viewerChunkZ = ChunkKeyCodec.z(viewerChunkKey);

        int tick = session.incrementTrackingTicker();
        EhConfig config = this.configContainer.get();
        int moveTicks = Math.max(1, config.farPlayerMoveTicks());
        int equipTicks = Math.max(1, config.farPlayerEquipTicks());
        int equipInterval = Math.max(1, equipTicks / moveTicks);
        boolean syncMove = true;
        boolean syncEquip = Math.floorMod(tick, equipInterval) == 0;

        double farLimit = targetDistance + FAR_RADIUS_PADDING;
        double farLimitSq = farLimit * farLimit;

        Map<UUID, Integer> trackedFarPlayers = session.trackedFarPlayers();
        Set<Integer> usedFarEntityIds = session.usedFarEntityIdBuffer();
        usedFarEntityIds.clear();
        usedFarEntityIds.addAll(trackedFarPlayers.values());

        Set<UUID> newlyRetained = session.trackingBuffer();
        newlyRetained.clear();

        for (FarPlayerState state : candidates) {
            if (state.uuid().equals(viewerId)) {
                continue;
            }

            Integer trackedEntityId = trackedFarPlayers.get(state.uuid());
            boolean alreadyTracked = trackedEntityId != null;

            if (session.isServerTrackingEntity(state.entityId())) {
                if (alreadyTracked) {
                    this.despawnAndRemove(channel, trackedFarPlayers, usedFarEntityIds, state.uuid(), trackedEntityId);
                }
                continue;
            }

            int stateChunkX = (int) Math.floor(state.x()) >> CHUNK_SHIFT;
            int stateChunkZ = (int) Math.floor(state.z()) >> CHUNK_SHIFT;
            long stateChunkKey = ChunkKeyCodec.pack(stateChunkX, stateChunkZ);
            int relChunkX = stateChunkX - viewerChunkX;
            int relChunkZ = stateChunkZ - viewerChunkZ;
            double distSq = (double) relChunkX * relChunkX + (double) relChunkZ * relChunkZ;

            if (distSq > farLimitSq) {
                if (alreadyTracked) {
                    this.despawnAndRemove(channel, trackedFarPlayers, usedFarEntityIds, state.uuid(), trackedEntityId);
                }
                continue;
            }

            if (!session.isChunkReadyForEntities(stateChunkKey)) {
                if (alreadyTracked) {
                    this.despawnAndRemove(channel, trackedFarPlayers, usedFarEntityIds, state.uuid(), trackedEntityId);
                }
                continue;
            }

            newlyRetained.add(state.uuid());
            if (!alreadyTracked) {
                int farEntityId = this.allocateFarEntityId(usedFarEntityIds, state.uuid(), state.entityId());
                if (farEntityId == ALLOCATION_FAILED) {
                    continue;
                }
                this.spawn(channel, trackedFarPlayers, usedFarEntityIds, state, farEntityId);
            } else {
                this.moveAndSync(channel, trackedEntityId, state, syncMove, syncEquip);
            }
        }

        Iterator<Map.Entry<UUID, Integer>> iterator = trackedFarPlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            if (!newlyRetained.contains(entry.getKey())) {
                this.despawn(channel, entry.getValue());
                iterator.remove();
                usedFarEntityIds.remove(entry.getValue());
            }
        }
    }

    private void spawn(
        Channel channel,
        Map<UUID, Integer> trackedFarPlayers,
        Set<Integer> usedFarEntityIds,
        FarPlayerState state,
        int farEntityId
    ) {
        FarPlayerState packetState = this.withEntityId(state, farEntityId);
        Object playerInfoPacket = this.backend.createPlayerInfoPacket(packetState);
        if (playerInfoPacket == null || !this.channelInjectionService.writeBypass(channel, playerInfoPacket)) {
            LOGGER.warn("Failed to initialize far player profile {}", state.uuid());
            return;
        }

        trackedFarPlayers.put(state.uuid(), farEntityId);
        usedFarEntityIds.add(farEntityId);

        ChannelPromise spawnPromise = this.channelInjectionService.writeBypassFuture(
            channel,
            this.backend.createSpawnPacket(packetState)
        );

        if (state.metadata() != null && !state.metadata().isEmpty()) {
            this.channelInjectionService.writeBypass(channel, this.backend.createMetadataPacket(farEntityId, state.metadata()));
        }

        this.channelInjectionService.writeBypass(channel, this.backend.createRotateHeadPacket(farEntityId, state.headYaw()));

        if (state.equipment() != null && !state.equipment().isEmpty()) {
            this.channelInjectionService.writeBypass(channel, this.backend.createEquipmentPacket(farEntityId, state.equipment()));
        }

        if (spawnPromise != null) {
            spawnPromise.addListener(future -> {
                if (!future.isSuccess()) {
                    usedFarEntityIds.remove(farEntityId);
                    trackedFarPlayers.remove(state.uuid(), farEntityId);
                    LOGGER.warn("Failed to spawn far player {}", state.uuid(), future.cause());
                }
            });
        }
    }

    private void moveAndSync(Channel channel, int trackedEntityId, FarPlayerState state, boolean syncMove, boolean syncEquip) {
        if (syncMove) {
            FarPlayerState packetState = this.withEntityId(state, trackedEntityId);
            this.channelInjectionService.writeBypass(channel, this.backend.createMovePacket(packetState));
            this.channelInjectionService.writeBypass(channel, this.backend.createRotateHeadPacket(trackedEntityId, state.headYaw()));

            if (state.metadata() != null && !state.metadata().isEmpty()) {
                this.channelInjectionService.writeBypass(channel, this.backend.createMetadataPacket(trackedEntityId, state.metadata()));
            }
        }

        if (syncEquip && state.equipment() != null && !state.equipment().isEmpty()) {
            this.channelInjectionService.writeBypass(channel, this.backend.createEquipmentPacket(trackedEntityId, state.equipment()));
        }
    }

    private void despawnAndRemove(
        Channel channel,
        Map<UUID, Integer> trackedFarPlayers,
        Set<Integer> usedFarEntityIds,
        UUID uuid,
        int entityId
    ) {
        this.despawn(channel, entityId);
        trackedFarPlayers.remove(uuid, entityId);
        usedFarEntityIds.remove(entityId);
    }

    private void despawn(Channel channel, int entityId) {
        if (channel != null && channel.isActive()) {
            this.channelInjectionService.writeBypass(channel, this.backend.createDespawnPacket(entityId));
        }
    }

    public void clearTracked(Channel channel, PlayerSession session) {
        if (channel == null || session == null) {
            return;
        }
        Map<UUID, Integer> tracked = session.trackedFarPlayers();
        if (!tracked.isEmpty()) {
            if (channel.isActive()) {
                for (int entityId : tracked.values()) {
                    this.despawn(channel, entityId);
                }
            }
            tracked.clear();
        }
        session.trackingBuffer().clear();
        session.usedFarEntityIdBuffer().clear();
    }

    private int allocateFarEntityId(Set<Integer> usedFarEntityIds, UUID targetUuid, int realEntityId) {
        int idRange = FAR_ENTITY_ID_RANGE_END - FAR_ENTITY_ID_RANGE_START;
        int candidate = FAR_ENTITY_ID_RANGE_START + Math.floorMod(targetUuid.hashCode(), idRange);
        for (int attempts = 0; attempts < FAR_ENTITY_ID_ALLOCATION_ATTEMPTS; attempts++) {
            if (candidate != realEntityId && !usedFarEntityIds.contains(candidate)) {
                return candidate;
            }
            candidate++;
            if (candidate >= FAR_ENTITY_ID_RANGE_END) {
                candidate = FAR_ENTITY_ID_RANGE_START;
            }
        }
        return ALLOCATION_FAILED;
    }

    private FarPlayerState withEntityId(FarPlayerState state, int entityId) {
        if (state.entityId() == entityId) {
            return state;
        }
        return new FarPlayerState(
            entityId,
            state.uuid(),
            state.worldId(),
            state.playerInfo(),
            state.x(),
            state.y(),
            state.z(),
            state.yaw(),
            state.pitch(),
            state.headYaw(),
            state.equipment(),
            state.metadata()
        );
    }
}
