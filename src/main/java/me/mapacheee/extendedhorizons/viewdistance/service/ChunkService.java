package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/*
 *   Hybrid chunk management system with LRU cache and aggressive cleanup
 *   Maintains chunks near players with a configurable margin
 *   Implements LRU eviction when cache limit is reached
*/
@Service
public class ChunkService {

    private Logger logger;
    private final ConfigService configService;
    private final Plugin plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    private PacketService packetService;

    private final Map<UUID, Set<Long>> playerChunks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> chunkRefCount = new ConcurrentHashMap<>();
    private final Map<Long, Long> chunkLastAccess = new ConcurrentHashMap<>();
    private final Map<Long, String> chunkWorlds = new ConcurrentHashMap<>();

    private int cleanupTaskId = -1;

    @Inject
    public ChunkService(ConfigService configService) {
        this.configService = configService;
        startPeriodicCleanup();
    }

    @Inject
    public void setPacketService(PacketService packetService) {
        this.packetService = packetService;
    }

    @OnDisable
    public void cleanup() {
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
        }

        for (Map.Entry<Long, String> entry : chunkWorlds.entrySet()) {
            long key = entry.getKey();
            String worldName = entry.getValue();
            World world = Bukkit.getWorld(worldName);

            if (world != null) {
                int x = (int) (key & 0xFFFFFFFFL);
                int z = (int) (key >> 32);
                world.setChunkForceLoaded(x, z, false);
            }
        }

        playerChunks.clear();
        chunkRefCount.clear();
        chunkLastAccess.clear();
        chunkWorlds.clear();
    }

    private void startPeriodicCleanup() {
        int interval = configService.get().performance().cleanupInterval();

        cleanupTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            cleanupChunks();
        }, interval, interval).getTaskId();
    }

    /**
     * Cleanup system
     */
    private void cleanupChunks() {
        int unloadMargin = configService.get().performance().unloadMargin();
        int maxCached = configService.get().performance().maxCachedChunks();

        Set<Long> chunksToKeep = new HashSet<>();

        // Phase 1: Identify chunks that should be kept (within margin of any player)
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            Set<Long> playerChunkSet = playerChunks.get(playerId);

            if (playerChunkSet == null) continue;

            PlayerView playerView = getPlayerView(player);
            if (playerView == null) continue;

            int radius = playerView.getTargetDistance() + unloadMargin;
            int px = player.getLocation().getBlockX() >> 4;
            int pz = player.getLocation().getBlockZ() >> 4;

            // Keep all chunks within radius + margin
            for (long key : playerChunkSet) {
                int x = (int) (key & 0xFFFFFFFFL);
                int z = (int) (key >> 32);
                int dist = Math.max(Math.abs(x - px), Math.abs(z - pz));

                if (dist <= radius) {
                    chunksToKeep.add(key);
                }
            }
        }

        // Phase 2: Unload chunks outside margins
        Set<Long> toUnload = new HashSet<>(chunkRefCount.keySet());
        toUnload.removeAll(chunksToKeep);

        for (long key : toUnload) {
            unforceChunkCompletely(key);
        }

        // Phase 3: LRU eviction if cache is too large
        int currentSize = chunkRefCount.size();
        if (currentSize > maxCached) {
            int toEvict = currentSize - maxCached;

            // Sort by last access time (oldest first)
            List<Map.Entry<Long, Long>> entries = new ArrayList<>(chunkLastAccess.entrySet());
            entries.sort(Map.Entry.comparingByValue());

            for (int i = 0; i < Math.min(toEvict, entries.size()); i++) {
                long key = entries.get(i).getKey();
                if (!chunksToKeep.contains(key)) {
                    unforceChunkCompletely(key);
                }
            }
        }
    }

    private PlayerView getPlayerView(Player player) {
        Set<Long> chunks = playerChunks.get(player.getUniqueId());
        if (chunks == null || chunks.isEmpty()) return null;

        int estimatedRadius = (int) Math.sqrt(chunks.size()) / 2;
        return new PlayerView(player, estimatedRadius);
    }

    /**
     * Force-loads chunks and sends them to the player
     */
    public CompletableFuture<Set<Long>> loadAndKeepChunks(Player player, Set<Long> keys) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptySet());
        }

        UUID playerId = player.getUniqueId();
        World world = player.getWorld();
        String worldName = world.getName();

        Set<Long> playerChunkSet = playerChunks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        Set<Long> newChunks = new HashSet<>(keys);
        newChunks.removeAll(playerChunkSet);

        long now = System.currentTimeMillis();
        for (long key : keys) {
            chunkLastAccess.put(key, now);
        }

        if (newChunks.isEmpty()) {
            return CompletableFuture.completedFuture(keys);
        }

        Set<Long> loaded = ConcurrentHashMap.newKeySet();
        int batchSize = configService.get().performance().maxChunksPerTick();
        List<Long> newChunksList = new ArrayList<>(newChunks);

        CompletableFuture<Void> allLoads = CompletableFuture.completedFuture(null);

        for (int i = 0; i < newChunksList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, newChunksList.size());
            List<Long> batch = newChunksList.subList(i, end);

            allLoads = allLoads.thenCompose(v -> {
                CompletableFuture<Void> batchFuture = new CompletableFuture<>();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Set<Chunk> chunksToSend = new HashSet<>();

                    try {
                        for (long key : batch) {
                            if (!player.isOnline()) break;

                            int x = (int) (key & 0xFFFFFFFFL);
                            int z = (int) (key >> 32);

                            world.setChunkForceLoaded(x, z, true);
                            chunkWorlds.put(key, worldName);

                            Chunk chunk = world.getChunkAt(x, z);
                            if (chunk != null) {
                                chunksToSend.add(chunk);

                                chunkRefCount.merge(key, 1, Integer::sum);
                                playerChunkSet.add(key);
                                loaded.add(key);
                                chunkLastAccess.put(key, System.currentTimeMillis());
                            }
                        }

                        if (!chunksToSend.isEmpty() && packetService != null) {
                            packetService.sendChunks(player, chunksToSend).thenRun(() -> {
                                batchFuture.complete(null);
                            });
                        } else {
                            batchFuture.complete(null);
                        }

                    } catch (Exception e) {
                        logger.warn("[EH] Error loading chunks: {}", e.getMessage());
                        batchFuture.complete(null);
                    }
                });

                return batchFuture.thenCompose(v2 -> {
                    CompletableFuture<Void> delay = new CompletableFuture<>();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> delay.complete(null), 2L);
                    return delay;
                });
            });
        }

        return allLoads.thenApply(v -> loaded);
    }

    /**
     * Removes force-loading for chunks a player no longer needs
     */
    public void unloadPlayerChunks(Player player, Set<Long> keysToRemove) {
        UUID playerId = player.getUniqueId();
        Set<Long> playerChunkSet = playerChunks.get(playerId);

        if (playerChunkSet == null || keysToRemove.isEmpty()) {
            return;
        }

        for (long key : keysToRemove) {
            if (playerChunkSet.remove(key)) {
                Integer refCount = chunkRefCount.compute(key, (k, count) -> {
                    if (count == null || count <= 1) return null;
                    return count - 1;
                });

                if (refCount == null) {
                    unforceChunkCompletely(key);
                }
            }
        }
    }

    /**
     * Clears all chunks for a player when they log out
     */
    public void clearPlayerChunks(Player player) {
        UUID playerId = player.getUniqueId();
        Set<Long> playerChunkSet = playerChunks.remove(playerId);

        if (playerChunkSet == null || playerChunkSet.isEmpty()) {
            return;
        }

        for (long key : playerChunkSet) {
            Integer refCount = chunkRefCount.compute(key, (k, count) -> {
                if (count == null || count <= 1) return null;
                return count - 1;
            });

            if (refCount == null) {
                unforceChunkCompletely(key);
            }
        }
    }

    /**
     * Completely unloads a chunk from server memory
     */
    private void unforceChunkCompletely(long key) {
        String worldName = chunkWorlds.remove(key);
        if (worldName == null) return;

        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        int x = (int) (key & 0xFFFFFFFFL);
        int z = (int) (key >> 32);

        try {
            world.setChunkForceLoaded(x, z, false);
            chunkRefCount.remove(key);
            chunkLastAccess.remove(key);
        } catch (Exception ignored) {
        }
    }

    public Set<Long> computeSquareKeys(Player player, int radius) {
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;

        Set<Long> keys = new LinkedHashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                long key = ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
                keys.add(key);
            }
        }
        return keys;
    }

    public Set<Long> getPlayerChunks(UUID playerId) {
        Set<Long> chunks = playerChunks.get(playerId);
        return chunks != null ? new HashSet<>(chunks) : Collections.emptySet();
    }

    public int getCachedChunkCount() {
        return chunkRefCount.size();
    }

    private static class PlayerView {
        private final Player player;
        private int targetDistance;

        public PlayerView(Player player, int targetDistance) {
            this.player = player;
            this.targetDistance = targetDistance;
        }

        public int getTargetDistance() {
            return targetDistance;
        }
    }
}
