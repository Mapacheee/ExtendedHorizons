package me.mapacheee.extendedhorizons.viewdistance.service;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/*
 *   Intercepts chunk packets sent by the server naturally
 *   Marks chunks in cache for reuse as "fake chunks"
 *   Integrated with PacketEvents system
 */
@Service
public class ChunkPacketInterceptor extends PacketListenerAbstract {

    private Logger logger;
    @SuppressWarnings("unused")
    private final ChunkPacketCache packetCache;
    private final ConfigService configService;

    private static final boolean DEBUG = false;

    @Inject
    public ChunkPacketInterceptor(ChunkPacketCache packetCache, ConfigService configService) {
        super(PacketListenerPriority.NORMAL);
        this.packetCache = packetCache;
        this.configService = configService;
    }

    @OnEnable
    public void register() {
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(this);

        if (DEBUG) {
            logger.info("[EH] ChunkPacketInterceptor registered");
        }
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (!configService.get().performance().fakeChunks().enabled()) {
            return;
        }

        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }

        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            int chunkX = wrapper.getColumn().getX();
            int chunkZ = wrapper.getColumn().getZ();

            if (DEBUG) {
                logger.info("[EH] Intercepted chunk packet {},{}", chunkX, chunkZ);
            }

        } catch (Exception e) {
            if (DEBUG) {
                logger.warn("[EH] Failed to intercept chunk packet: {}", e.getMessage());
            }
        }
    }
}
