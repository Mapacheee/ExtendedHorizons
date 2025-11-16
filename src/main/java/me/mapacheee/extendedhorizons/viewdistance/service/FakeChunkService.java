package me.mapacheee.extendedhorizons.viewdistance.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.integration.packetevents.PacketChunkCacheService;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 *   Manages fake chunks (chunks beyond server view-distance)
 *   Optimized loading strategy:
 *   1. Memory cache (fastest) - chunks already loaded in server memory
 *   2. Disk NBT (fast) - loads existing chunks from disk without generation
 *   3. Generation (slowest) - generates new chunks only as last resort
 */
@Service
public class FakeChunkService {

    private static final Logger logger = LoggerFactory.getLogger(FakeChunkService.class);
    private final ConfigService configService;
    private final PacketChunkCacheService columnCache;
    private final Plugin plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    private final Map<UUID, Set<Long>> playerFakeChunks = new ConcurrentHashMap<>();
    private final Set<Long> generatingChunks = ConcurrentHashMap.newKeySet();
    
    /**
     * Queue of chunks pending to be loaded per player
     * Used for progressive loading instead of loading all chunks at once
     */
    private final Map<UUID, Queue<Long>> playerChunkQueues = new ConcurrentHashMap<>();
    
    /**
     * Tracks how many chunks have been processed this tick per player
     * Used for throttling
     */
    private final Map<UUID, Integer> playerChunksProcessedThisTick = new ConcurrentHashMap<>();
    
    /**
     * Tracks last chunk position per player to detect teleports
     */
    private final Map<UUID, Long> lastChunkPosition = new ConcurrentHashMap<>();

    private static final boolean DEBUG = false;
    
    // Constants for teleport detection and queue management
    private static final int TELEPORT_DETECTION_THRESHOLD = 3; // chunks
    private static final int QUEUE_CLEAR_DISTANCE_THRESHOLD = 8; // chunks
    private static final int QUEUE_CLEAR_FAR_DISTANCE = 15; // chunks
    private static final int MAX_SCHEDULED_PROCESSING_DELAY = 10; // ticks

    @Inject
    public FakeChunkService(ConfigService configService, PacketChunkCacheService columnCache) {
        this.configService = configService;
        this.columnCache = columnCache;
        
        int configuredThreads = configService.get().performance().chunkProcessorThreads();
        int threadCount = configuredThreads > 0 
            ? configuredThreads 
            : Math.max(4, Runtime.getRuntime().availableProcessors());
        
        this.chunkProcessor = Executors.newFixedThreadPool(
            threadCount,
            r -> {
                Thread t = new Thread(r, "EH-ChunkProcessor");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        );
        
        startProgressiveLoadingTask();
    }
    
    /**
     * Starts a task that progressively loads chunks in batches
     */
    private void startProgressiveLoadingTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            playerChunksProcessedThisTick.clear();
            
            List<Map.Entry<UUID, Queue<Long>>> entries = new ArrayList<>(playerChunkQueues.entrySet());
            
            for (Map.Entry<UUID, Queue<Long>> entry : entries) {
                UUID playerId = entry.getKey();
                Queue<Long> queue = entry.getValue();
                
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {

                    playerChunkQueues.remove(playerId);
                    playerChunksProcessedThisTick.remove(playerId);
                    continue;
                }
                
                processChunkQueue(player, queue);
            }
            
        }, 1L, 1L);
    }
    
    /**
     * Processes a chunk queue for a player, loading chunks in batches.
     * Respects the max-chunks-per-tick limit to prevent server overload.
     * Can be called immediately or from the periodic task.
     * 
     * @param player The player to process chunks for (must not be null and must be online)
     * @param queue The queue of chunk keys to process (must not be null)
     */
    private void processChunkQueue(Player player, Queue<Long> queue) {
        if (player == null || !player.isOnline() || queue == null || queue.isEmpty()) {
            return;
        }
        
        UUID uuid = player.getUniqueId();
        int maxChunksPerTick = configService.get().performance().maxChunksPerTick();
        int processed = playerChunksProcessedThisTick.getOrDefault(uuid, 0);
        int remaining = maxChunksPerTick - processed;
        
        if (remaining <= 0) { return; }
        
        List<Long> batch = new ArrayList<>();
        while (!queue.isEmpty() && batch.size() < remaining) {
            Long key = queue.poll();
            if (key != null && !generatingChunks.contains(key)) {
                batch.add(key);
            }
        }
        
        if (!batch.isEmpty()) {
            Set<Long> sentTracker = playerFakeChunks.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            processChunkBatch(player, batch, sentTracker);
            
            playerChunksProcessedThisTick.put(uuid, processed + batch.size());
            
            if (DEBUG) {
                logger.info("[EH] Processed batch of {} chunks for {} ({} remaining in queue)", 
                        batch.size(), player.getName(), queue.size());
            }
        }
        
    }
    
    /**
     * Processes a batch of chunks using a three-tiered loading strategy:
     * 1. Memory cache (fastest) - checks servers internal chunk cache
     * 2. Disk NBT (fast) - loads chunk data directly from disk
     * 3. Generation (slowest) - generates new chunk if not found
     * 
     * @param player The player to send chunks to
     * @param batch The list of chunk keys to process
     * @param sentTracker Set tracking which chunks have already been sent to this player
     */
    private void processChunkBatch(Player player, List<Long> batch, Set<Long> sentTracker) {
        World world = player.getWorld();
        
        for (long key : batch) {
            if (!player.isOnline()) break;
            
            generatingChunks.add(key);
            
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);
            
            // Process asynchronously with optimized loading strategy
            chunkProcessor.execute(() -> {
                try {
                    // Strategy 1: Try to get chunk from servers memory cache
                    LevelChunk memoryChunk = getChunkFromMemoryCache(world, chunkX, chunkZ);
                    if (memoryChunk != null) {
                        if (DEBUG) {
                            logger.info("[EH] Loaded chunk {},{} from memory cache", chunkX, chunkZ);
                        }
                        sendChunkPacket(player, memoryChunk, key, sentTracker);
                        return;
                    }
                    
                    // Strategy 2: Try to load chunk NBT from disk
                    CompoundTag chunkNBT = getChunkNBTFromDisk(world, chunkX, chunkZ);
                    if (chunkNBT != null) {
                        String statusStr = chunkNBT.getString("Status").orElse("");
                        ChunkStatus status = statusStr.isEmpty() ? null : ChunkStatus.byName(statusStr);
                        if (status != null && status.isOrAfter(ChunkStatus.FULL)) {
                            if (DEBUG) {
                                logger.info("[EH] Loaded chunk {},{} from disk NBT", chunkX, chunkZ);
                            }
                            loadChunkFromNBTAndSend(player, world, chunkX, chunkZ, chunkNBT, key, sentTracker);
                            return;
                        }
                    }
                    
                    // Strategy 3: Generate chunk
                    if (DEBUG) {
                        logger.info("[EH] Generating chunk {},{} (not in cache or disk)", chunkX, chunkZ);
                    }
                    generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
                    
                } catch (Exception e) {
                    generatingChunks.remove(key);
                    logger.warn("[EH] Error loading chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    /**
     * Gets the servers actual view distance from server.properties
     */
    public int getServerViewDistance() {
        return Bukkit.getViewDistance();
    }

    /**
     * Sends fake chunks to a player
     * Chunks are prioritized by distance (closer chunks first)
     * Uses progressive loading to avoid overwhelming the server
     */
    public CompletableFuture<Integer> sendFakeChunks(Player player, Set<Long> chunkKeys) {
        if (!configService.get().performance().fakeChunks().enabled()) {
            return CompletableFuture.completedFuture(0);
        }
        
        if (chunkKeys.isEmpty()) {
            if (DEBUG) {
                logger.info("[EH] No fake chunks to send for {}", player.getName());
            }
            return CompletableFuture.completedFuture(0);
        }
        
        if (DEBUG) {
            logger.info("[EH] sendFakeChunks called for {} with {} chunks", player.getName(), chunkKeys.size());
        }

        CompletableFuture<Integer> result = new CompletableFuture<>();
        UUID uuid = player.getUniqueId();
        Set<Long> playerSentChunks = playerFakeChunks.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());

        Set<Long> toRemove = new HashSet<>(playerSentChunks);
        toRemove.removeAll(chunkKeys);
        playerSentChunks.removeAll(toRemove);

        List<Long> toSend = new ArrayList<>();
        List<Long> toGenerate = new ArrayList<>();

        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        for (long key : chunkKeys) {
            if (playerSentChunks.contains(key)) {
                continue;
            }

            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);

            if (columnCache.get(chunkX, chunkZ) != null) {
                toSend.add(key);
            } else if (!generatingChunks.contains(key)) {
                toGenerate.add(key);
            }
        }

        int sent = sendCachedChunks(player, toSend, playerSentChunks);

        if (!toGenerate.isEmpty()) {
            toGenerate.sort((key1, key2) -> {
                int x1 = (int) (key1 & 0xFFFFFFFFL);
                int z1 = (int) (key1 >> 32);
                int x2 = (int) (key2 & 0xFFFFFFFFL);
                int z2 = (int) (key2 >> 32);
                
                int dx1 = x1 - playerChunkX;
                int dz1 = z1 - playerChunkZ;
                int dx2 = x2 - playerChunkX;
                int dz2 = z2 - playerChunkZ;
                long dist1Squared = (long) dx1 * dx1 + (long) dz1 * dz1;
                long dist2Squared = (long) dx2 * dx2 + (long) dz2 * dz2;
                
                return Long.compare(dist1Squared, dist2Squared);
            });
            
            Queue<Long> queue = playerChunkQueues.computeIfAbsent(uuid, k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
            
            if (!queue.isEmpty()) {
                int currentChunkX = player.getLocation().getBlockX() >> 4;
                int currentChunkZ = player.getLocation().getBlockZ() >> 4;
                
                if (shouldClearQueue(queue, currentChunkX, currentChunkZ)) {
                    if (DEBUG) {
                        logger.info("[EH] Clearing old chunk queue for {} due to significant movement ({} chunks)", 
                                player.getName(), queue.size());
                    }
                    queue.clear();

                    generatingChunks.removeIf(key -> {
                        int chunkX = (int) (key & 0xFFFFFFFFL);
                        int chunkZ = (int) (key >> 32);
                        double dist = Math.sqrt((chunkX - currentChunkX) * (chunkX - currentChunkX) + 
                                                (chunkZ - currentChunkZ) * (chunkZ - currentChunkZ));
                        return dist > QUEUE_CLEAR_FAR_DISTANCE;
                    });
                } else {
                    if (DEBUG) {
                        logger.info("[EH] Keeping existing queue for {} (chunks still relevant)", player.getName());
                    }
                }
            }
            
            long currentChunkPos = ((long) playerChunkZ << 32) | (playerChunkX & 0xFFFFFFFFL);
            Long lastPos = lastChunkPosition.get(uuid);
            boolean isTeleport = false;
            if (lastPos != null && lastPos != currentChunkPos) {
                int lastChunkX = (int) (lastPos & 0xFFFFFFFFL);
                int lastChunkZ = (int) (lastPos >> 32);
                int dx = Math.abs(lastChunkX - playerChunkX);
                int dz = Math.abs(lastChunkZ - playerChunkZ);
                isTeleport = (dx > TELEPORT_DETECTION_THRESHOLD || dz > TELEPORT_DETECTION_THRESHOLD);
            }
            lastChunkPosition.put(uuid, currentChunkPos);
            
            queue.addAll(toGenerate);
            
            if (DEBUG) {
                logger.info("[EH] Added {} chunks to queue for {} (total in queue: {}, isTeleport: {})", 
                        toGenerate.size(), player.getName(), queue.size(), isTeleport);
            }
            
            playerChunksProcessedThisTick.remove(uuid);
            processChunkQueue(player, queue);
            
            if (isTeleport || queue.size() > configService.get().performance().maxChunksPerTick()) {

                for (int delay = 2; delay <= MAX_SCHEDULED_PROCESSING_DELAY; delay += 2) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            Queue<Long> currentQueue = playerChunkQueues.get(uuid);
                            if (currentQueue != null && !currentQueue.isEmpty()) {
                                playerChunksProcessedThisTick.remove(uuid);
                                processChunkQueue(player, currentQueue);
                            }
                        }
                    }, delay);
                }
            }
        }

        result.complete(sent);
        return result;
    }
    
    /**
     * Checks if the queue should be cleared due to significant player movement.
     * Only clears if all sampled chunks are far away (like teleporting).
     * 
     * @param queue The queue of chunk keys to check
     * @param playerChunkX The player's current chunk X coordinate
     * @param playerChunkZ The player's current chunk Z coordinate
     * @return true if the queue should be cleared (all chunks are far away)
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
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);
            double dist = Math.sqrt((chunkX - playerChunkX) * (chunkX - playerChunkX) + 
                                    (chunkZ - playerChunkZ) * (chunkZ - playerChunkZ));
            
            totalSamples++;

            if (dist > QUEUE_CLEAR_DISTANCE_THRESHOLD) {
                farChunks++;
            }
        }
        
        return totalSamples > 0 && farChunks == totalSamples;
    }

    /**
     * Sends chunks that are already in cache
     */
    private int sendCachedChunks(Player player, List<Long> keys, Set<Long> sentTracker) {
        int sent = 0;

        for (long key : keys) {
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);

            Column column = columnCache.get(chunkX, chunkZ);
            if (column != null) {
                if (sendColumnToPlayer(player, column)) {
                    sentTracker.add(key);
                    sent++;
                } else {
                    if (DEBUG) {
                        logger.warn("[EH] Column null or invalid for {},{}, will add to queue", chunkX, chunkZ);
                    }

                    UUID uuid = player.getUniqueId();
                    Queue<Long> queue = playerChunkQueues.computeIfAbsent(uuid, k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
                    queue.add(key);
                }
            }
        }

        if (DEBUG) {
            logger.info("[EH] Sent {} cached fake chunks to {}", sent, player.getName());
        }

        return sent;
    }


    /**
     * Attempts to get a chunk from the servers memory cache or our own cache
     */
    private LevelChunk getChunkFromMemoryCache(World world, int chunkX, int chunkZ) {
        long chunkKey = packChunkKey(chunkX, chunkZ);
        
        // First check our own memory cache
        LevelChunk cached = chunkMemoryCache.get(chunkKey);
        if (cached != null) {
            return cached;
        }
        
        // Then check servers memory cache
        try {
            ServerLevel serverLevel = ((CraftWorld) world).getHandle();
            
            ChunkHolder chunkHolder = serverLevel.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunkKey);
            
            if (chunkHolder != null) {
                LevelChunk chunk = chunkHolder.getFullChunkNow();
                if (chunk != null && !(chunk instanceof EmptyLevelChunk)) {
                    cacheChunkInMemory(chunkKey, chunk);
                    return chunk;
                }
            }
        } catch (NoSuchMethodError | NoClassDefFoundError | Exception e) {
            if (DEBUG) {
                logger.debug("[EH] Memory cache lookup failed for {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Caches a chunk in memory for reuse
     */
    private void cacheChunkInMemory(long chunkKey, LevelChunk chunk) {
        if (chunkMemoryCache.size() >= MAX_MEMORY_CACHE_SIZE) {
            if (chunkMemoryCache.size() >= MAX_MEMORY_CACHE_SIZE * 1.2) {
                int toRemove = MAX_MEMORY_CACHE_SIZE / 5;
                Iterator<Long> it = chunkMemoryCache.keySet().iterator();
                for (int i = 0; i < toRemove && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }
        }
        chunkMemoryCache.put(chunkKey, chunk);
    }
    
    /**
     * Packs chunk coordinates into a long key
     */
    private static long packChunkKey(int x, int z) {
        return ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
    }

    /**
     * Attempts to load chunk NBT data from disk
     */
    private CompoundTag getChunkNBTFromDisk(World world, int chunkX, int chunkZ) {
        try {
            ServerLevel serverLevel = ((CraftWorld) world).getHandle();
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            
            CompletableFuture<Optional<CompoundTag>> futureNBT = serverLevel.getChunkSource()
                    .chunkMap.read(chunkPos);
            
            Optional<CompoundTag> optionalNBT = futureNBT.get();
            return optionalNBT.orElse(null);
            
        } catch (InterruptedException | ExecutionException e) {
            if (DEBUG) {
                logger.debug("[EH] Disk NBT read failed for {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
            return null;
        } catch (NoSuchMethodError | NoClassDefFoundError | Exception e) {
            // Silently fail - method might not exist in all versions
            if (DEBUG) {
                logger.debug("[EH] Disk NBT lookup not available for {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
            return null;
        }
    }

    /**
     * Loads a chunk from NBT data and sends it to the player
     * This is faster than generating but requires the chunk to exist on disk
     */
    private void loadChunkFromNBTAndSend(Player player, World world, int chunkX, int chunkZ, 
                                         CompoundTag chunkNBT, long key, Set<Long> sentTracker) {
        try {
            // Use Paper's async chunk loading - it will use the NBT data from disk
            // Passing false means don't generate if it doesn't exist (it should exist from NBT)
            world.getChunkAtAsync(chunkX, chunkZ, false).thenAcceptAsync(chunk -> {
                if (!player.isOnline()) {
                    generatingChunks.remove(key);
                    return;
                }

                try {
                    org.bukkit.craftbukkit.CraftChunk craftChunk = (org.bukkit.craftbukkit.CraftChunk) chunk;
                    LevelChunk nmsChunk = (LevelChunk) craftChunk.getHandle(ChunkStatus.FULL);
                    
                    if (nmsChunk != null) {
                        // Cache the loaded chunk for future use
                        long chunkKey = packChunkKey(chunkX, chunkZ);
                        cacheChunkInMemory(chunkKey, nmsChunk);
                        sendChunkPacket(player, nmsChunk, key, sentTracker);
                    } else {
                        // Fallback to generation if loading failed
                        generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
                    }
                } catch (Exception e) {
                    if (DEBUG) {
                        logger.warn("[EH] Failed to process NBT chunk {},{}, falling back to generation: {}", 
                                chunkX, chunkZ, e.getMessage());
                    }
                    generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
                }
            }, chunkProcessor).exceptionally(throwable -> {
                if (DEBUG) {
                    logger.warn("[EH] Failed to load chunk from NBT {},{}, falling back to generation: {}", 
                            chunkX, chunkZ, throwable.getMessage());
                }
                generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
                return null;
            });
            
        } catch (Exception e) {
            if (DEBUG) {
                logger.warn("[EH] Error loading chunk from NBT {},{}, falling back to generation: {}", 
                        chunkX, chunkZ, e.getMessage());
            }
            generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
        }
    }

    /**
     * Generates a new chunk and sends it to the player
     * This is the slowest method and should be the last resort
     */
    private void generateChunkAndSend(Player player, World world, int chunkX, int chunkZ, 
                                      long key, Set<Long> sentTracker) {
        world.getChunkAtAsync(chunkX, chunkZ, true).thenAcceptAsync(chunk -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                org.bukkit.craftbukkit.CraftChunk craftChunk = (org.bukkit.craftbukkit.CraftChunk) chunk;
                LevelChunk nmsChunk = (LevelChunk) craftChunk.getHandle(ChunkStatus.FULL);

                if (nmsChunk != null) {
                    // Cache the generated chunk for future use
                    long chunkKey = packChunkKey(chunkX, chunkZ);
                    cacheChunkInMemory(chunkKey, nmsChunk);
                    sendChunkPacket(player, nmsChunk, key, sentTracker);
                } else {
                    generatingChunks.remove(key);
                    if (DEBUG) {
                        logger.warn("[EH] Generated chunk {},{} is null", chunkX, chunkZ);
                    }
                }
            } catch (Exception e) {
                generatingChunks.remove(key);
                if (DEBUG) {
                    logger.warn("[EH] Failed to process generated chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
                }
            }
        }, chunkProcessor).exceptionally(throwable -> {
            generatingChunks.remove(key);
            logger.warn("[EH] Failed to generate chunk {},{}: {}", chunkX, chunkZ, throwable.getMessage());
            return null;
        });
    }

    /**
     * Sends a chunk packet to the player
     * This is the common method used by all loading strategies
     */
    private void sendChunkPacket(Player player, LevelChunk nmsChunk, long key, Set<Long> sentTracker) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = 
                        (org.bukkit.craftbukkit.entity.CraftPlayer) player;
                net.minecraft.server.level.ServerPlayer nmsPlayer = craftPlayer.getHandle();

                // Note: Constructor is deprecated but no alternative available in current Paper version
                @SuppressWarnings("deprecation")
                net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                        new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                nmsChunk,
                                nmsChunk.getLevel().getLightEngine(),
                                null,
                                null
                        );

                nmsPlayer.connection.send(packet);

                sentTracker.add(key);
                generatingChunks.remove(key);

                if (DEBUG) {
                    int chunkX = (int) (key & 0xFFFFFFFFL);
                    int chunkZ = (int) (key >> 32);
                    logger.info("[EH] Sent fake chunk {},{} to {}", chunkX, chunkZ, player.getName());
                }
            } catch (Exception e) {
                generatingChunks.remove(key);
                if (DEBUG) {
                    logger.warn("[EH] Failed to send chunk packet: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Sends a Column directly to the player using PacketEvents
     * Returns true if successful, false if Column is null or invalid
     */
    private boolean sendColumnToPlayer(Player player, Column column) {
        if (column == null) {
            if (DEBUG) {
                logger.warn("[EH] Attempted to send null column to " + player.getName());
            }
            return false;
        }

        try {
            WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(column);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            return true;
        } catch (Exception e) {
            if (DEBUG) {
                logger.warn("[EH] Failed to send column to " + player.getName() + ": " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Clears fake chunks for a player
     */
    public void clearPlayerFakeChunks(Player player) {
        UUID playerId = player.getUniqueId();
        playerFakeChunks.remove(playerId);
        playerChunkQueues.remove(playerId);
        playerChunksProcessedThisTick.remove(playerId);
        lastChunkPosition.remove(playerId);
    }

    /**
     * Shutdown the async executor
     */
    public void shutdown() {
        chunkProcessor.shutdown();
        chunkMemoryCache.clear();
    }
    
    /**
     * Clears the memory cache (useful for memory management)
     */
    public void clearMemoryCache() {
        chunkMemoryCache.clear();
    }
    
    /**
     * Gets the current size of the memory cache
     */
    public int getMemoryCacheSize() {
        return chunkMemoryCache.size();
    }


    public int getCacheSize() {
        return columnCache.size();
    }

    public double getCacheHitRate() {
        long hits = 0;
        long total = 0;
        for (Set<Long> chunks : playerFakeChunks.values()) {
            total += chunks.size();
            for (long key : chunks) {
                int chunkX = (int) (key & 0xFFFFFFFFL);
                int chunkZ = (int) (key >> 32);
                if (columnCache.get(chunkX, chunkZ) != null) {
                    hits++;
                }
            }
        }
        return total > 0 ? (hits * 100.0 / total) : 0.0;
    }

    public double getEstimatedMemoryUsageMB() {
        return columnCache.size() * 0.04;
    }

    /**
     * Thread pool for parallel chunk processing
     * Size is configurable via config.yml (performance.chunk-processor-threads)
     * Defaults to max(4, availableProcessors) if set to 0
     * Similar to FartherViewDistance's asyncThreadAmount approach
     */
    private final ExecutorService chunkProcessor;
    
    /**
     * Cache for NMS chunks already loaded in memory
     * Reuses chunks without regenerating them
     */
    private final Map<Long, LevelChunk> chunkMemoryCache = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_CACHE_SIZE = 1000;
}
