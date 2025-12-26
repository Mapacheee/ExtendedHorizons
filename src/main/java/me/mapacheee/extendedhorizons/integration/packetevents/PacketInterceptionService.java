package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewDistance;

import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.google.inject.Inject;
import com.google.inject.Provider;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.entity.Player;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/*
 *   Intercepts packets and manages fake chunk system
 *   - Caches chunk packets for reuse as fake chunks
 *   - Prevents client from unloading extended chunks
 *   - Maintains proper view distance for client
 */
@Service
public class PacketInterceptionService {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PacketInterceptionService.class);
    private final Provider<ViewDistanceService> viewDistanceServiceProvider;
    private final NMSPacketAccess nmsPacketAccess;
    private static final boolean DEBUG = false;

    @Inject
    public PacketInterceptionService(
            Provider<ViewDistanceService> viewDistanceServiceProvider,
            NMSPacketAccess nmsPacketAccess) {
        this.viewDistanceServiceProvider = viewDistanceServiceProvider;
        this.nmsPacketAccess = nmsPacketAccess;
    }

    @OnEnable
    public void register() {
        PacketEvents.getAPI().getEventManager()
                .registerListener(new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
                    @Override
                    public void onPacketSend(@NotNull PacketSendEvent event) {
                        if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
                            Player player = (Player) event.getPlayer();
                            if (player == null)
                                return;

                            var view = viewDistanceServiceProvider.get().getPlayerView(player.getUniqueId());
                            if (view == null)
                                return;

                            WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
                            int chunkX = wrapper.getChunkX();
                            int chunkZ = wrapper.getChunkZ();

                            int playerChunkX = player.getLocation().getBlockX() >> 4;
                            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
                            int dx = Math.abs(chunkX - playerChunkX);
                            int dz = Math.abs(chunkZ - playerChunkZ);
                            int chebyshev = Math.max(dx, dz);

                            int margin = 1;
                            if (chebyshev <= view.getTargetDistance() + margin) {
                                event.setCancelled(true);
                            }
                        } else if (event.getPacketType() == PacketType.Play.Server.UPDATE_VIEW_DISTANCE) {
                            Player player = event.getPlayer();
                            if (player == null)
                                return;

                            if (player.getTicksLived() < 100)
                                return;

                            var view = viewDistanceServiceProvider.get().getPlayerView(player.getUniqueId());
                            if (view == null)
                                return;

                            WrapperPlayServerUpdateViewDistance wrapper = new WrapperPlayServerUpdateViewDistance(
                                    event);
                            int serverRadius = wrapper.getViewDistance();
                            int target = view.getTargetDistance();
                            if (serverRadius < target) {
                                event.setCancelled(true);
                                player.getScheduler().run(
                                        ExtendedHorizonsPlugin.getInstance(), (task) -> {
                                            Object packet = nmsPacketAccess.createChunkCacheRadiusPacket(target);
                                            nmsPacketAccess.sendPacket(player, packet);
                                        }, null);
                            }
                        }
                    }
                });

        if (DEBUG) {
            logger.info("[EH] Packet interception system registered with fake chunk support");
        }
    }

}
