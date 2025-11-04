package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

    @Inject
    public ChunkService(ConfigService configService) {
        this.configService = configService;
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
                            Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
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
     * Computes chunk keys in a square pattern around the player
     */
    public Set<Long> computeSquareKeys(Player player, int radius) {
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;

        Set<Long> keys = new HashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                keys.add(packChunkKey(x, z));
            }
        }
        return keys;
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
