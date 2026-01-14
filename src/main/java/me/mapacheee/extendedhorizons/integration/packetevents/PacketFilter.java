package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import me.mapacheee.extendedhorizons.shared.config.MainConfig;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;

public class PacketFilter extends PacketListenerAbstract {

    private final ConfigService configService;

    public PacketFilter(ConfigService configService) {
        super(PacketListenerPriority.LOWEST);
        this.configService = configService;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        MainConfig.BandwidthSaverConfig config = configService.get().bandwidthSaver();
        if (config == null || !config.enabled() || !config.skipRedundantPackets()) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            WrapperPlayServerEntityRelativeMove wrapper = new WrapperPlayServerEntityRelativeMove(event);
            if (wrapper.getDeltaX() == 0 && wrapper.getDeltaY() == 0 && wrapper.getDeltaZ() == 0) {
                event.setCancelled(true);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            WrapperPlayServerEntityRelativeMoveAndRotation wrapper = new WrapperPlayServerEntityRelativeMoveAndRotation(
                    event);
            if (wrapper.getDeltaX() == 0 && wrapper.getDeltaY() == 0 && wrapper.getDeltaZ() == 0) {
                WrapperPlayServerEntityRotation rotationPacket = new WrapperPlayServerEntityRotation(
                        wrapper.getEntityId(),
                        wrapper.getYaw(),
                        wrapper.getPitch(),
                        wrapper.isOnGround());
                event.setCancelled(true);
                PacketEvents.getAPI().getPlayerManager().sendPacket(event.getPlayer(), rotationPacket);
            }
        }
    }
}
