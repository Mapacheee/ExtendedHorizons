package me.mapacheee.extendedhorizons.fakechunks.farplayers;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public final class FarPlayerTrackingService {

    private static final int FAR_ENTITY_ID_RANGE_START = 1_000_000_000;
    private static final int FAR_ENTITY_ID_RANGE_END   = 1_900_000_000;
    private static final int FAR_ENTITY_ID_ALLOCATION_ATTEMPTS = 10_000;
    private static final double FAR_RADIUS_PADDING = 0.35d;

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
        UUID worldId,
        long viewerChunkKey,
        PlayerSession session,
        Channel channel,
        int targetDistance
    ) {
        int viewerChunkX = ChunkKeyCodec.x(viewerChunkKey);
        int viewerChunkZ = ChunkKeyCodec.z(viewerChunkKey);

        int tick = session.incrementTrackingTicker();
        EhConfig config = this.configContainer.get();
        boolean syncMove = Math.floorMod(tick, config.farPlayerMoveTicks()) == 0;
        boolean syncEquip = Math.floorMod(tick, config.farPlayerEquipTicks()) == 0;

        double farLimit = targetDistance + FAR_RADIUS_PADDING;
        double farLimitSq = farLimit * farLimit;

        Collection<FarPlayerState> candidates = this.cacheService.getNearbyPlayers(
            worldId, viewerChunkX, viewerChunkZ, targetDistance
        );

        Set<UUID> newlyRetained = session.trackingBuffer();
        newlyRetained.clear();

        for (FarPlayerState state : candidates) {
            if (state.uuid().equals(viewerId)) {
                continue;
            }

            Integer trackedEntityId = session.trackedFarPlayers().get(state.uuid());
            boolean alreadyTracked = trackedEntityId != null;

            if (session.isServerTrackingEntity(state.entityId())) {
                if (alreadyTracked) {
                    this.despawnAndRemove(channel, session, state.uuid(), trackedEntityId);
                }
                continue;
            }

            int stateChunkX = (int) Math.floor(state.x()) >> 4;
            int stateChunkZ = (int) Math.floor(state.z()) >> 4;
            int relChunkX = stateChunkX - viewerChunkX;
            int relChunkZ = stateChunkZ - viewerChunkZ;
            double distSq = (double) relChunkX * relChunkX + (double) relChunkZ * relChunkZ;

            if (distSq > farLimitSq) {
                if (alreadyTracked) {
                    this.despawnAndRemove(channel, session, state.uuid(), trackedEntityId);
                }
                continue;
            }

            newlyRetained.add(state.uuid());
            if (!alreadyTracked) {
                int farEntityId = this.allocateFarEntityId(session, state.uuid(), state.entityId());
                this.spawn(channel, session, state, farEntityId);
            } else {
                this.moveAndSync(channel, trackedEntityId, state, syncMove, syncEquip);
            }
        }

        for (Map.Entry<UUID, Integer> entry : session.trackedFarPlayers().entrySet()) {
            if (!newlyRetained.contains(entry.getKey()) && this.despawn(channel, entry.getValue())) {
                session.trackedFarPlayers().remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void spawn(Channel channel, PlayerSession session, FarPlayerState state, int farEntityId) {
        FarPlayerState packetState = this.withEntityId(state, farEntityId);
        if (!this.channelInjectionService.writeBypass(channel, this.backend.createSpawnPacket(packetState))) {
            return;
        }

        if (state.metadata() != null && !state.metadata().isEmpty()) {
            this.channelInjectionService.writeBypass(channel, this.backend.createMetadataPacket(farEntityId, state.metadata()));
        }

        if (state.equipment() != null && !state.equipment().isEmpty()) {
            this.channelInjectionService.writeBypass(channel, this.backend.createEquipmentPacket(farEntityId, state.equipment()));
        }

        session.trackedFarPlayers().put(state.uuid(), farEntityId);
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

    private void despawnAndRemove(Channel channel, PlayerSession session, UUID uuid, int entityId) {
        if (this.despawn(channel, entityId)) {
            session.trackedFarPlayers().remove(uuid, entityId);
        }
    }

    private boolean despawn(Channel channel, int entityId) {
        return this.channelInjectionService.writeBypass(channel, this.backend.createDespawnPacket(entityId));
    }

    public void clearTracked(Channel channel, PlayerSession session) {
        if (channel == null || session == null) {
            return;
        }
        for (Map.Entry<UUID, Integer> entry : session.trackedFarPlayers().entrySet()) {
            if (this.despawn(channel, entry.getValue())) {
                session.trackedFarPlayers().remove(entry.getKey(), entry.getValue());
            }
        }
        session.trackingBuffer().clear();
    }

    private int allocateFarEntityId(PlayerSession session, UUID targetUuid, int realEntityId) {
        Map<Integer, UUID> reverseLookup = buildReverseLookup(session.trackedFarPlayers());
        int candidate = FAR_ENTITY_ID_RANGE_START + Math.floorMod(targetUuid.hashCode(), FAR_ENTITY_ID_RANGE_END - FAR_ENTITY_ID_RANGE_START);
        for (int attempts = 0; attempts < FAR_ENTITY_ID_ALLOCATION_ATTEMPTS; attempts++) {
            if (candidate != realEntityId && !reverseLookup.containsKey(candidate)) {
                return candidate;
            }
            candidate++;
            if (candidate >= FAR_ENTITY_ID_RANGE_END) {
                candidate = FAR_ENTITY_ID_RANGE_START;
            }
        }
        return realEntityId;
    }

    private static Map<Integer, UUID> buildReverseLookup(Map<UUID, Integer> trackedFarPlayers) {
        Map<Integer, UUID> reverse = new HashMap<>(trackedFarPlayers.size() * 2);
        for (Map.Entry<UUID, Integer> entry : trackedFarPlayers.entrySet()) {
            reverse.put(entry.getValue(), entry.getKey());
        }
        return reverse;
    }

    private FarPlayerState withEntityId(FarPlayerState state, int entityId) {
        if (state.entityId() == entityId) {
            return state;
        }
        return new FarPlayerState(
            entityId,
            state.uuid(),
            state.worldId(),
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
