package me.mapacheee.extendedhorizons.fakechunks.farplayers;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.config.ConfigFacade;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public final class FarPlayerTrackingService {

    private final ConfigFacade configFacade;
    private final FarPlayerCacheService cacheService;
    private final FarPlayerBackend backend;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public FarPlayerTrackingService(
        ConfigFacade configFacade,
        FarPlayerCacheService cacheService,
        FarPlayerBackend backend,
        ChannelInjectionService channelInjectionService
    ) {
        this.configFacade = configFacade;
        this.cacheService = cacheService;
        this.backend = backend;
        this.channelInjectionService = channelInjectionService;
    }

    public void track(
        UUID viewerId,
        UUID worldId,
        int viewerChunkX,
        int viewerChunkZ,
        PlayerSession session,
        Channel channel,
        int serverDistance,
        int targetDistance
    ) {
        Set<UUID> newlyFound = session.trackingBuffer();
        newlyFound.clear();

        int tick = session.incrementTrackingTicker();
        EhConfig config = this.configFacade.get();
        boolean syncMove = Math.floorMod(tick, config.farPlayerMoveTicks()) == 0;
        boolean syncEquip = Math.floorMod(tick, config.farPlayerEquipTicks()) == 0;

        for (FarPlayerState state : this.cacheService.getNearbyPlayers(worldId, viewerChunkX, viewerChunkZ, targetDistance)) {
            if (state.uuid().equals(viewerId)) {
                continue;
            }

            int targetChunkX = (int) Math.floor(state.x()) >> 4;
            int targetChunkZ = (int) Math.floor(state.z()) >> 4;
            int dx = Math.abs(targetChunkX - viewerChunkX);
            int dz = Math.abs(targetChunkZ - viewerChunkZ);
            int distance = Math.max(dx, dz);

            boolean inFarRange = distance > serverDistance && distance <= targetDistance;
            boolean alreadyTracked = session.trackedFarPlayers().containsKey(state.uuid());

            if (inFarRange) {
                newlyFound.add(state.uuid());
                if (!alreadyTracked) {
                    this.spawn(channel, session, state);
                } else {
                    this.moveAndSync(channel, state, syncMove, syncEquip);
                }
            }
        }

        Iterator<Map.Entry<UUID, Integer>> iterator = session.trackedFarPlayers().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            if (!newlyFound.contains(entry.getKey())) {
                this.despawn(channel, entry.getValue());
                iterator.remove();
            }
        }
    }

    private void spawn(Channel channel, PlayerSession session, FarPlayerState state) {
        Object spawnPacket = this.backend.createSpawnPacket(state);
        this.channelInjectionService.writeBypass(channel, spawnPacket);
        
        if (state.metadata() != null && !state.metadata().isEmpty()) {
            Object metaPacket = this.backend.createMetadataPacket(state.entityId(), state.metadata());
            this.channelInjectionService.writeBypass(channel, metaPacket);
        }
        
        if (state.equipment() != null && !state.equipment().isEmpty()) {
            Object equipPacket = this.backend.createEquipmentPacket(state.entityId(), state.equipment());
            this.channelInjectionService.writeBypass(channel, equipPacket);
        }

        session.trackedFarPlayers().put(state.uuid(), state.entityId());
    }

    private void moveAndSync(Channel channel, FarPlayerState state, boolean syncMove, boolean syncEquip) {
        if (syncMove) {
            Object movePacket = this.backend.createMovePacket(state);
            this.channelInjectionService.writeBypass(channel, movePacket);

            Object rotateHeadPacket = this.backend.createRotateHeadPacket(state.entityId(), state.headYaw());
            this.channelInjectionService.writeBypass(channel, rotateHeadPacket);
        }

        if (syncEquip) {
            if (state.metadata() != null && !state.metadata().isEmpty()) {
                Object metaPacket = this.backend.createMetadataPacket(state.entityId(), state.metadata());
                this.channelInjectionService.writeBypass(channel, metaPacket);
            }

            if (state.equipment() != null && !state.equipment().isEmpty()) {
                Object equipPacket = this.backend.createEquipmentPacket(state.entityId(), state.equipment());
                this.channelInjectionService.writeBypass(channel, equipPacket);
            }
        }
    }

    private void despawn(Channel channel, int entityId) {
        Object packet = this.backend.createDespawnPacket(entityId);
        this.channelInjectionService.writeBypass(channel, packet);
    }
}
