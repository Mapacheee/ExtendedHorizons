package me.mapacheee.extendedhorizons.integration.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.viewdistance.service.IViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PacketEvents Service - Direct chunk streaming approach
 * Intercepts real chunk packets and reuses them for extended view distances
 */

@Service
public class PacketEventsService extends PacketListenerAbstract implements IPacketEventsService {

    private final Logger logger;
    private IViewDistanceService viewDistanceService;
    private final Plugin plugin;

    private final ConcurrentMap<String, WrapperPlayServerChunkData> chunkPacketCache;
    private static final int MAX_CACHE_SIZE = 2000;

    @Inject
    public PacketEventsService(Logger logger, ExtendedHorizonsPlugin plugin) {
        super(PacketListenerPriority.HIGH);
        this.logger = logger;
        this.viewDistanceService = null;
        this.plugin = plugin;
        this.chunkPacketCache = new ConcurrentHashMap<>();

        registerListener();
    }

    @Inject
    public void setViewDistanceService(IViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
    }

    private void registerListener() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        logger.info("PacketEvents listener registered successfully");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION ||
            event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            handlePlayerMovement(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkDataPacket(event);
        }
    }

    private void handlePlayerMovement(PacketReceiveEvent event) {
        Player player = event.getPlayer();
        if (viewDistanceService != null) {
            Bukkit.getScheduler().runTask(plugin, () -> viewDistanceService.updatePlayerView(player));
        }
    }

    private void handleChunkDataPacket(PacketSendEvent event) {
        Player player = event.getPlayer();

        WrapperPlayServerChunkData wrapperFromEvent = new WrapperPlayServerChunkData(event);
        Column column = wrapperFromEvent.getColumn();
        LightData lightData = wrapperFromEvent.getLightData();

        WrapperPlayServerChunkData detached = new WrapperPlayServerChunkData(column, lightData, true);

        String cacheKey = getChunkKey(player.getWorld(), column.getX(), column.getZ());

        if (chunkPacketCache.size() >= MAX_CACHE_SIZE) {
            chunkPacketCache.entrySet().removeIf(entry -> Math.random() < 0.1);
        }

        chunkPacketCache.put(cacheKey, detached);

        if (viewDistanceService != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                var playerView = viewDistanceService.getPlayerView(player.getUniqueId());
                if (playerView != null) {
                    playerView.incrementChunksSent();
                    playerView.addNetworkBytesUsed(8192L);
                }
            });
        }
    }

    /**
     * Sends a chunk by using cached real packets when available. If not cached, we ensure the
     * chunk is loaded and let Paper send it to player.
     */
    public void sendFakeChunk(Player player, int chunkX, int chunkZ) {
        try {
            World world = player.getWorld();
            String cacheKey = getChunkKey(world, chunkX, chunkZ);

            WrapperPlayServerChunkData cachedPacket = chunkPacketCache.get(cacheKey);
            if (cachedPacket != null) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, cachedPacket);
                updatePlayerStats(player);
            }

        } catch (Exception e) {
            logger.error("Failed to send fake chunk ({},{}) to player {}", chunkX, chunkZ, player.getName(), e);
        }
    }

    public void sendUnloadChunk(Player player, int chunkX, int chunkZ) {
        try {
            WrapperPlayServerUnloadChunk unloadPacket = new WrapperPlayServerUnloadChunk(chunkX, chunkZ);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, unloadPacket);

            String chunkKey = getChunkKey(player.getWorld(), chunkX, chunkZ);
            chunkPacketCache.remove(chunkKey);
        } catch (Exception e) {
            logger.error("Failed to send unload chunk to player {}", player.getName(), e);
        }
    }

    private void updatePlayerStats(Player player) {
        if (viewDistanceService != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                var playerView = viewDistanceService.getPlayerView(player.getUniqueId());
                if (playerView != null) {
                    playerView.incrementChunksSent();
                    playerView.addNetworkBytesUsed(8192L);
                }
            });
        }
    }

    private String getChunkKey(World world, int x, int z) {
        return world.getUID() + ":" + x + "," + z;
    }

    @Override
    public void sendChunkPacket(Player player, WrapperPlayServerChunkData chunkData) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, chunkData);
        } catch (Exception e) {
            logger.error("Failed to send chunk packet to player {}", player.getName(), e);
        }
    }

    @Override
    public void sendFakeChunkPacket(Player player, int chunkX, int chunkZ) {
        sendFakeChunk(player, chunkX, chunkZ);
    }

    @Override
    public void sendUnloadChunkPacket(Player player, int chunkX, int chunkZ) {
        sendUnloadChunk(player, chunkX, chunkZ);
    }

    @Override
    public WrapperPlayServerChunkData createChunkData(Chunk chunk) {
        throw new UnsupportedOperationException("Direct chunk data creation not supported");
    }

    @Override
    public WrapperPlayServerChunkData createFakeChunkData(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("Use sendFakeChunk() instead - fake chunks are now real chunks loaded temporarily");
    }

    @Override
    public void unregisterListener() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        logger.info("PacketEvents listener unregistered");
    }
}
