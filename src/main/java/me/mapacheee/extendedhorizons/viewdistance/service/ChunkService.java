package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/*
 *   service that only retrieves chunks without forcing them loaded
 *   The server naturally manages chunk loading/unloading based on view-distance
 *   This service just provides chunk data when needed for fake chunk packets
*/
@Service
public class ChunkService {

    private static final Logger logger = LoggerFactory.getLogger(ChunkService.class);
    private final ConfigService configService;
    private final Plugin plugin;

    @Inject
    public ChunkService(ConfigService configService) {
        this.configService = configService;
        this.plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    }

    /**
     * Retrieves chunks asynchronously without forcing them to stay loaded
     */
    public CompletableFuture<List<Chunk>> getChunksAsync(Player player, Set<Long> keys) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        World world = player.getWorld();
        List<Chunk> chunks = Collections.synchronizedList(new ArrayList<>());

        int batchSize = configService.get().performance().maxChunksPerTick();
        List<Long> keysList = new ArrayList<>(keys);

        CompletableFuture<Void> allLoads = CompletableFuture.completedFuture(null);

        for (int i = 0; i < keysList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, keysList.size());
            List<Long> batch = keysList.subList(i, end);

            allLoads = allLoads.thenCompose(v -> {
                List<CompletableFuture<Chunk>> batchFutures = new ArrayList<>();

                for (long key : batch) {
                    if (!player.isOnline()) break;

                    int x = (int) (key & 0xFFFFFFFFL);
                    int z = (int) (key >> 32);

                    CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsync(x, z)
                        .exceptionally(ex -> {
                            logger.warn("[EH] Failed to load chunk {},{}: {}", x, z, ex.getMessage());
                            return null;
                        });

                    batchFutures.add(chunkFuture);
                }

                return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> {
                        for (CompletableFuture<Chunk> cf : batchFutures) {
                            Chunk chunk = cf.join();
                            if (chunk != null) {
                                chunks.add(chunk);
                            }
                        }
                        return null;
                    })
                    .thenCompose(ignored2 -> {
                    CompletableFuture<Void> delay = new CompletableFuture<>();
                    Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> delay.complete(null),
                        2L
                    );
                        return delay;
                    });
            });
        }

        return allLoads.thenApply(v -> chunks);
    }

    /**
     * Checks if a chunk is within the world border.
     * 
     * @param player The player whose world to check
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is within the world border
     */
    private boolean isChunkWithinWorldBorder(Player player, int chunkX, int chunkZ) {
        WorldBorder border = player.getWorld().getWorldBorder();
        if (border == null) {
            return true; 
        }

        double borderSize = border.getSize();
        
        if (borderSize >= 5.9999968E7) { // mc max world size
            return true;
        }

        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderRadius = borderSize / 2.0;
        
        double chunkBlockX = (chunkX << 4) + 8;
        double chunkBlockZ = (chunkZ << 4) + 8;
        
        double dx = chunkBlockX - borderCenterX;
        double dz = chunkBlockZ - borderCenterZ;
        double distanceSquared = dx * dx + dz * dz;
        
        double maxDistanceSquared = (borderRadius + 8) * (borderRadius + 8);
        return distanceSquared <= maxDistanceSquared;
    }

    /**
     * Computes chunk keys in a circular pattern around the player.
     * Only includes chunks that are within the world border.
     */
    public Set<Long> computeCircularKeys(Player player, int radius) {
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;

        Set<Long> keys = new HashSet<>();
        double radiusSquared = (radius + 0.5) * (radius + 0.5);
        
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                int dx = x - cx;
                int dz = z - cz;
                double distanceSquared = dx * dx + dz * dz;
                
                if (distanceSquared <= radiusSquared) {
                    if (isChunkWithinWorldBorder(player, x, z)) {
                        keys.add(packChunkKey(x, z));
                    }
                }
            }
        }
        return keys;
    }

    /**
     * Computes chunk keys in a square pattern around the player
     * @deprecated Use computeCircularKeys instead to match server behavior
     */
    @Deprecated
    public Set<Long> computeSquareKeys(Player player, int radius) {
        return computeCircularKeys(player, radius);
    }

    /**
     * Packs chunk coordinates into a long key
     */
    public static long packChunkKey(int x, int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    /**
     * Unpacks X coordinate from chunk key
     */
    public static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    /**
     * Unpacks Z coordinate from chunk key
     */
    public static int unpackZ(long key) {
        return (int) (key >> 32);
    }
}
