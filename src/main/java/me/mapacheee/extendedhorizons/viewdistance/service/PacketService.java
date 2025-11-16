package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/*
 *   Manages client chunk radius and sends chunks manually using NMS
 *   Sends chunks beyond server view-distance directly to client
*/
@Service
public class PacketService {

    private static final Logger logger = LoggerFactory.getLogger(PacketService.class);
    private final Plugin plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);

    private static final boolean DEBUG = false;

    @Inject
    public PacketService() {}

    /**
     * Ensures client has correct chunk cache radius
     */
    public void ensureClientRadius(Player player, int radius) {
        if (radius < 2) return;

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        nmsPlayer.connection.send(new ClientboundSetChunkCacheRadiusPacket(radius));
    }

    /**
     * Updates client chunk cache center to player's current position
     */
    public void ensureClientCenter(Player player) {
        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;
        nmsPlayer.connection.send(new ClientboundSetChunkCacheCenterPacket(cx, cz));
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
                ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
                net.minecraft.world.level.chunk.ChunkAccess chunkAccess = ((CraftChunk) chunk).getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);

                if (!(chunkAccess instanceof LevelChunk)) {
                    future.complete(null);
                    return;
                }

                LevelChunk nmsChunk = (LevelChunk) chunkAccess;

                @SuppressWarnings("deprecation")
                ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    nmsChunk,
                    nmsChunk.getLevel().getLightEngine(),
                    null,
                    null
                );

                nmsPlayer.connection.send(packet);

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
                if (!player.isOnline()) break;

                try {
                    ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
                    net.minecraft.world.level.chunk.ChunkAccess chunkAccess = ((CraftChunk) chunk).getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);

                    if (!(chunkAccess instanceof LevelChunk)) continue;

                    LevelChunk nmsChunk = (LevelChunk) chunkAccess;

                    // Note: Constructor is deprecated but no alternative available in current Paper version
                    @SuppressWarnings("deprecation")
                    ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                        nmsChunk,
                        nmsChunk.getLevel().getLightEngine(),
                        null,
                        null
                    );

                    nmsPlayer.connection.send(packet);
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
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();

            org.bukkit.World world = player.getWorld();
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);

            if (chunk.isLoaded()) {
                net.minecraft.world.level.chunk.ChunkAccess chunkAccess = ((CraftChunk) chunk).getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);

                if (chunkAccess instanceof LevelChunk) {
                    LevelChunk nmsChunk = (LevelChunk) chunkAccess;

                    // Note: Constructor is deprecated but no alternative available in current Paper version
                    @SuppressWarnings("deprecation")
                    ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                        nmsChunk,
                        nmsChunk.getLevel().getLightEngine(),
                        null,
                        null
                    );

                    nmsPlayer.connection.send(packet);
                }
            }

        } catch (Exception e) {
            if (DEBUG) {
                logger.warn("[EH] Error sending raw packet for chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }
    }
}
