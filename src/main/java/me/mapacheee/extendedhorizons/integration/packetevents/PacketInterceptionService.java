package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewDistance;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.google.inject.Inject;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkPacketInterceptor;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
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

    private Logger logger;
    private final ViewDistanceService viewDistanceService;
    private final PacketChunkCacheService chunkCache;
    private final ChunkPacketInterceptor chunkPacketInterceptor;
    private static final boolean DEBUG = false;

    @Inject
    public PacketInterceptionService(
            ViewDistanceService viewDistanceService,
            PacketChunkCacheService chunkCache,
            ChunkPacketInterceptor chunkPacketInterceptor) {
        this.viewDistanceService = viewDistanceService;
        this.chunkCache = chunkCache;
        this.chunkPacketInterceptor = chunkPacketInterceptor;
    }

    @OnEnable
    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(@NotNull PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
                    Player player = (Player) event.getPlayer();
                    if (player == null) return;

                    var view = viewDistanceService.getPlayerView(player.getUniqueId());
                    if (view == null) return;

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
                    if (player == null) return;
                    var view = viewDistanceService.getPlayerView(player.getUniqueId());
                    if (view == null) return;

                    WrapperPlayServerUpdateViewDistance wrapper = new WrapperPlayServerUpdateViewDistance(event);
                    int serverRadius = wrapper.getViewDistance();
                    int target = view.getTargetDistance();
                    if (serverRadius < target) {
                        event.setCancelled(true);
                        Bukkit.getScheduler().runTask(ExtendedHorizonsPlugin.getPlugin(ExtendedHorizonsPlugin.class), () -> {
                            ((CraftPlayer) player).getHandle().connection.send(new ClientboundSetChunkCacheRadiusPacket(target));
                        });
                    }
                } else if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
                    try {
                        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
                        Column column = wrapper.getColumn();

                        if (column == null) return;

                        chunkCache.put(column.getX(), column.getZ(), column);

                        if (DEBUG && chunkCache.size() % 100 == 0) {
                            logger.info("[EH] Cached {} real chunks", chunkCache.size());
                        }
                    } catch (Throwable ex) {
                        if (DEBUG) {
                            logger.warn("[EH] Error intercepting chunk: {}", ex.getMessage());
                        }
                    }
                }
            }
        });

        if (DEBUG) {
            logger.info("[EH] Packet interception system registered with fake chunk support");
        }
    }
}
