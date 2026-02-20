package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;

import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/*
 *   Manages client chunk radius and sends chunks manually using NMS
 *   Sends chunks beyond server view-distance directly to client
*/
@Service
public class PacketService {

    private static final Logger logger = LoggerFactory.getLogger(PacketService.class);
    private final Plugin plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);

    private final Map<UUID, Integer> lastSentChunkRadius = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSentSimulationDistance = new ConcurrentHashMap<>();

    private static final boolean DEBUG = false;

    private final NMSPacketAccess nmsPacketAccess;
    private final NMSChunkAccess nmsChunkAccess;

    @Inject
    public PacketService(NMSPacketAccess nmsPacketAccess, NMSChunkAccess nmsChunkAccess) {
        this.nmsPacketAccess = nmsPacketAccess;
        this.nmsChunkAccess = nmsChunkAccess;
    }

    /**
     * Ensures client has correct chunk cache radius
     */
    public void ensureClientRadius(Player player, int radius) {
        if (radius < 2)
            return;

        UUID uuid = player.getUniqueId();
        Integer lastRadius = lastSentChunkRadius.get(uuid);

        if (lastRadius != null && lastRadius == radius) {
            return;
        }

        lastSentChunkRadius.put(uuid, radius);
        lastSentChunkRadius.put(uuid, radius);

        Object packet = nmsPacketAccess.createChunkCacheRadiusPacket(radius);
        nmsPacketAccess.sendPacket(player, packet);
    }

    /**
     * Updates client chunk cache center to player's current position
     */
    public void ensureClientCenter(Player player) {
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;

        Object packet = nmsPacketAccess.createChunkCacheCenterPacket(cx, cz);
        nmsPacketAccess.sendPacket(player, packet);
    }

    /**
     * Ensures client has correct simulation distance (pushes back fog)
     */
    public void ensureClientSimulationDistance(Player player, int distance) {
        if (distance < 2)
            return;

        UUID uuid = player.getUniqueId();
        Integer lastDistance = lastSentSimulationDistance.get(uuid);

        if (lastDistance != null && lastDistance == distance) {
            return;
        }

        lastSentSimulationDistance.put(uuid, distance);
        lastSentSimulationDistance.put(uuid, distance);

        Object packet = nmsPacketAccess.createSimulationDistancePacket(distance);
        nmsPacketAccess.sendPacket(player, packet);
    }

    /**
     * Sends a chunk directly to the player using NMS
     */
    public CompletableFuture<Void> sendChunk(Player player, Chunk chunk) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        if (chunk == null || !player.isOnline()) {
            future.complete(null);
            return future;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);
                if (nmsChunk == null) {
                    future.complete(null);
                    return;
                }

                Object packet = nmsPacketAccess.createChunkPacket(nmsChunk);
                nmsPacketAccess.sendPacket(player, packet);

                if (DEBUG) {
                    logger.info("[EH] Sent chunk {},{} to {}", chunk.getX(), chunk.getZ(), player.getName());
                }

                future.complete(null);
            } catch (Exception e) {
                if (DEBUG) {
                    logger.warn("[EH] Error sending chunk: {}", e.getMessage());
                }
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Sends multiple chunks to a player
     */
    public CompletableFuture<Integer> sendChunks(Player player, Set<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty() || !player.isOnline()) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<Integer> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            int sent = 0;

            for (Chunk chunk : chunks) {
                if (!player.isOnline())
                    break;

                try {
                    Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);
                    if (nmsChunk == null)
                        continue;

                    Object packet = nmsPacketAccess.createChunkPacket(nmsChunk);
                    nmsPacketAccess.sendPacket(player, packet);
                    sent++;

                } catch (Exception e) {
                    if (DEBUG) {
                        logger.warn("[EH] Error sending chunk {},{}: {}", chunk.getX(), chunk.getZ(), e.getMessage());
                    }
                }
            }

            if (DEBUG) {
                logger.info("[EH] Sent {} chunks to {}", sent, player.getName());
            }

            result.complete(sent);
        });

        return result;
    }

    /**
     * Sends a raw cached packet to the player
     * Used for fake chunks from the packet cache
     */
    public void sendRawChunkPacket(Player player, int chunkX, int chunkZ, byte[] packetData) {
        if (!player.isOnline() || packetData == null) {
            return;
        }

        try {
            org.bukkit.World world = player.getWorld();
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);

            if (chunk.isLoaded()) {
                Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);
                if (nmsChunk != null) {
                    Object packet = nmsPacketAccess.createChunkPacket(nmsChunk);
                    nmsPacketAccess.sendPacket(player, packet);
                }
            }

        } catch (Exception e) {
            if (DEBUG) {
                logger.warn("[EH] Error sending raw packet for chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }
    }

    /**
     * Cleans up all player data on quit
     */
    public void cleanupPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        lastSentChunkRadius.remove(uuid);
        lastSentSimulationDistance.remove(uuid);
    }

    /**
     * Resets cache for a player
     */
    public void resetPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        lastSentChunkRadius.remove(uuid);
        lastSentSimulationDistance.remove(uuid);
    }
}
