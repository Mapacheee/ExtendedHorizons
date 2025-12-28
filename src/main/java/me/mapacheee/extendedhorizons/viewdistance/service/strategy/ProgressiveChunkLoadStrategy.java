package me.mapacheee.extendedhorizons.viewdistance.service.strategy;

import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.TeleportDetector;
import me.mapacheee.extendedhorizons.viewdistance.service.player.WarmupManager;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Standard implementation of ChunkLoadStrategy that loads fake chunks
 * progressively based on distance from the player.
 * 
 * Features:
 * - Teleport detection via distance threshold
 * - Warmup period for teleports (clears queue, prioritizes close chunks)
 * - Intelligent queue management (clears old chunks if player moves too far)
 * - Distance-based sorting (spiral/closest-first)
 */
@Service
public class ProgressiveChunkLoadStrategy implements ChunkLoadStrategy {

    private static final Logger logger = LoggerFactory.getLogger(ProgressiveChunkLoadStrategy.class);
    private static final boolean DEBUG = false;

    private static final int QUEUE_CLEAR_DISTANCE_THRESHOLD = 8;
    private static final int QUEUE_CLEAR_FAR_DISTANCE = 15;

    private final TeleportDetector teleportDetector;
    private final WarmupManager warmupManager;

    @Inject
    public ProgressiveChunkLoadStrategy(
            TeleportDetector teleportDetector,
            WarmupManager warmupManager) {
        this.teleportDetector = teleportDetector;
        this.warmupManager = warmupManager;
    }

    @Override
    public void onPlayerUpdate(Player player, PlayerChunkState state) {
        boolean isTeleport = teleportDetector.hasMovedSignificantly(player, state);

        if (isTeleport) {
            warmupManager.startWarmup(state);
            if (DEBUG) {
                logger.info("[EH] Teleport detected for {}, starting warmup", player.getName());
            }
        }
    }

    @Override
    public boolean isWarmupActive(Player player, PlayerChunkState state) {
        return warmupManager.isWarmupActive(state);
    }

    @Override
    public void processWarmup(Player player, PlayerChunkState state, Set<Long> allVisibleChunks) {
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        List<Long> sortedKeys = new ArrayList<>(allVisibleChunks);
        sortedKeys.sort((key1, key2) -> compareDistance(key1, key2, playerChunkX, playerChunkZ));

        Queue<Long> queue = state.getChunkQueue();
        Set<Long> queuedSet = state.getQueuedChunksSet();

        for (long key : sortedKeys) {
            if (!queuedSet.contains(key)) {
                queue.add(key);
                queuedSet.add(key);
            }
        }

        if (DEBUG) {
            logger.info("[EH] Warmup active for {}, queued {} chunks (sorted by distance)",
                    player.getName(), sortedKeys.size());
        }
    }

    @Override
    public void processQueue(Player player, PlayerChunkState state, List<Long> newChunksToLoad,
            Set<Long> globalGeneratingSet) {
        if (newChunksToLoad.isEmpty()) {
            return;
        }

        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        newChunksToLoad.sort((key1, key2) -> compareDistance(key1, key2, playerChunkX, playerChunkZ));

        Queue<Long> queue = state.getChunkQueue();

        if (!queue.isEmpty()) {
            if (shouldClearQueue(queue, playerChunkX, playerChunkZ)) {
                if (DEBUG) {
                    logger.info("[EH] Clearing old chunk queue for {} due to significant movement ({} chunks)",
                            player.getName(), queue.size());
                }
                queue.clear();
                state.getQueuedChunksSet().clear();

                globalGeneratingSet.removeIf(key -> {
                    int chunkX = ChunkUtils.unpackX(key);
                    int chunkZ = ChunkUtils.unpackZ(key);
                    double dist = Math.sqrt((chunkX - playerChunkX) * (chunkX - playerChunkX) +
                            (chunkZ - playerChunkZ) * (chunkZ - playerChunkZ));
                    return dist > QUEUE_CLEAR_FAR_DISTANCE;
                });
            } else {
                if (DEBUG) {
                    logger.info("[EH] Keeping existing queue for {} (chunks still relevant)", player.getName());
                }
            }
        }

        Set<Long> queuedSet = state.getQueuedChunksSet();
        for (Long key : newChunksToLoad) {
            if (!queuedSet.contains(key)) {
                queue.add(key);
                queuedSet.add(key);
            }
        }
    }

    /**
     * Checks if the queue should be cleared due to significant player movement.
     * Only clears if all sampled chunks are far away.
     */
    private boolean shouldClearQueue(Queue<Long> queue, int playerChunkX, int playerChunkZ) {
        if (queue.isEmpty()) {
            return false;
        }

        int samples = Math.min(10, queue.size());
        Iterator<Long> it = queue.iterator();
        int farChunks = 0;
        int totalSamples = 0;

        for (int i = 0; i < samples && it.hasNext(); i++) {
            long key = it.next();
            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);

            double dist = Math.sqrt((chunkX - playerChunkX) * (chunkX - playerChunkX) +
                    (chunkZ - playerChunkZ) * (chunkZ - playerChunkZ));

            if (dist > QUEUE_CLEAR_DISTANCE_THRESHOLD) {
                farChunks++;
            }
            totalSamples++;
        }

        return totalSamples > 0 && farChunks == totalSamples;
    }

    private int compareDistance(long key1, long key2, int originX, int originZ) {
        int x1 = ChunkUtils.unpackX(key1);
        int z1 = ChunkUtils.unpackZ(key1);
        int x2 = ChunkUtils.unpackX(key2);
        int z2 = ChunkUtils.unpackZ(key2);

        int dx1 = x1 - originX;
        int dz1 = z1 - originZ;
        int dx2 = x2 - originX;
        int dz2 = z2 - originZ;

        long dist1Squared = (long) dx1 * dx1 + (long) dz1 * dz1;
        long dist2Squared = (long) dx2 * dx2 + (long) dz2 * dz2;

        return Long.compare(dist1Squared, dist2Squared);
    }
}
