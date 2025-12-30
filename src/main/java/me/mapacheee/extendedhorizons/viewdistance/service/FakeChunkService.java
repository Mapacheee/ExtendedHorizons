package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.config.MainConfig;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import net.minecraft.world.level.chunk.LevelChunk;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;

import java.util.ArrayList;

import java.util.Collections;

import java.util.HashSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Deque;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.mapacheee.extendedhorizons.api.event.FakeChunkLoadEvent;
import me.mapacheee.extendedhorizons.api.event.FakeChunkUnloadEvent;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import me.mapacheee.extendedhorizons.viewdistance.service.bandwidth.BandwidthController;
import me.mapacheee.extendedhorizons.viewdistance.service.event.ChunkEventDispatcher;
import me.mapacheee.extendedhorizons.viewdistance.service.strategy.ChunkLoadStrategy;
import me.mapacheee.extendedhorizons.viewdistance.service.player.WarmupManager;

/*
 *   Manages fake chunks (chunks beyond server view-distance)
 *   Optimized loading strategy:
 *   1. PacketEvents cache (fastest) - chunks cached from intercepted packets
 *   2. Memory cache (fast) - chunks already loaded in server memory
 *   3. Disk loading (fast) - loads existing chunks from disk without generating
 *   4. Generation (slowest) - generates new chunks only as last resort
 */
@Service
public class FakeChunkService {

    private static final Logger logger = LoggerFactory.getLogger(FakeChunkService.class);
    private final ConfigService configService;
    private final PlayerStateManager playerStateManager;
    private final BandwidthController bandwidthController;
    private final ChunkEventDispatcher chunkEventDispatcher;
    private final ChunkLoadStrategy chunkLoadStrategy;
    private final NMSChunkAccess nmsChunkAccess;
    private final NMSPacketAccess nmsPacketAccess;
    private final WarmupManager warmupManager;
    private final Set<Long> generatingChunks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger chunksGeneratedThisTick = new AtomicInteger(0);
    private int maxGenerationsPerTick = 1;
    private ScheduledTask progressiveLoadingTask;
    private static final boolean DEBUG = true;
    private final AtomicLong memoryCacheHits = new AtomicLong(0);
    private final AtomicLong memoryCacheMisses = new AtomicLong(0);
    private final AtomicLong diskLoads = new AtomicLong(0);
    private final AtomicLong chunkGenerations = new AtomicLong(0);

    @Inject
    public FakeChunkService(
            ConfigService configService,
            ChunkLoadStrategy chunkLoadStrategy,
            ChunkEventDispatcher chunkEventDispatcher,
            PlayerStateManager playerStateManager,
            BandwidthController bandwidthController,
            NMSChunkAccess nmsChunkAccess,
            NMSPacketAccess nmsPacketAccess,
            WarmupManager warmupManager) {
        this.configService = configService;
        this.chunkLoadStrategy = chunkLoadStrategy;
        this.chunkEventDispatcher = chunkEventDispatcher;
        this.playerStateManager = playerStateManager;
        this.bandwidthController = bandwidthController;
        this.nmsChunkAccess = nmsChunkAccess;
        this.nmsPacketAccess = nmsPacketAccess;
        this.warmupManager = warmupManager;

        this.maxGenerationsPerTick = configService.get().performance().maxGenerationsPerTick();
        int maxCacheSize = configService.get().performance().fakeChunks().maxMemoryCacheSize();
        this.chunkMemoryCache = Collections.synchronizedMap(
                new LinkedHashMap<Long, Object>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
                        return size() > maxCacheSize;
                    }
                });

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
                });

    }

    public void onPlayerJoin(Player player) {
        PlayerChunkState state = playerStateManager.getOrCreate(player);
        warmupManager.startWarmup(state);
    }

    /**
     * Cleans up player data when they quit or change worlds
     * 
     * @param sendPackets If true, sends unload packets to client
     * @param reason      The reason for cleanup
     */
    public void cleanupPlayer(Player player, boolean sendPackets, FakeChunkUnloadEvent.UnloadReason reason) {
        clearPlayerFakeChunks(player, sendPackets, reason);
    }

    public void cleanupPlayer(Player player, boolean sendPackets) {
        cleanupPlayer(player, sendPackets, FakeChunkUnloadEvent.UnloadReason.DISTANCE);
    }

    public void cleanupPlayer(Player player) {
        cleanupPlayer(player, true, FakeChunkUnloadEvent.UnloadReason.DISTANCE);
    }

    public Map<String, Long> getStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("memory_hits", memoryCacheHits.get());
        stats.put("memory_misses", memoryCacheMisses.get());
        stats.put("disk_loads", diskLoads.get());
        stats.put("generations", chunkGenerations.get());
        return stats;
    }

    /**
     * Starts a task that progressively loads chunks in batches
     */
    @com.thewinterframework.service.annotation.lifecycle.OnEnable
    public void onEnable() {
        startProgressiveLoadingTask();
    }

    @OnDisable
    public void onDisable() {
        if (progressiveLoadingTask != null) {
            progressiveLoadingTask.cancel();
        }

        shutdown();
    }

    /**
     * Periodic task to load fake chunks for players with a limit
     */
    private void startProgressiveLoadingTask() {
        this.progressiveLoadingTask = Bukkit.getAsyncScheduler()
                .runAtFixedRate(ExtendedHorizonsPlugin.getInstance(), (task) -> {
                    try {
                        chunksGeneratedThisTick.set(0);
                        chunksLoadedFromDiskThisTick.set(0);
                        playerStateManager.resetTickCounters();

                        maxGenerationsPerTick = configService.get().performance().maxGenerationsPerTick();
                        if (maxGenerationsPerTick <= 0)
                            maxGenerationsPerTick = 1;

                        long bandwidthPerPlayer = configService.get().bandwidthSaver().maxBandwidthPerPlayer(); // KB/s
                        if (bandwidthPerPlayer <= 0)
                            bandwidthPerPlayer = 10000;

                        bandwidthController.updateMaxBytesPerTick((int) bandwidthPerPlayer);

                        try {
                            double mspt = Bukkit.getAverageTickTime();
                            double maxMspt = configService.get().performance().maxMsptForLoading();
                            if (maxMspt > 0 && mspt > maxMspt) {
                                if (DEBUG) {
                                    logger.warn("[EH] High MSPT ({}ms > {}ms), skipping chunk loading",
                                            String.format("%.2f", mspt), maxMspt);
                                }
                                return;
                            }
                        } catch (UnsupportedOperationException | NullPointerException ignored) {
                        }

                        int activeTasks = ((ThreadPoolExecutor) chunkProcessor).getActiveCount();
                        int queueSize = ((ThreadPoolExecutor) chunkProcessor).getQueue().size();
                        int maxTasks = configService.get().performance().maxAsyncLoadTasks();
                        int maxQueue = configService.get().performance().maxAsyncLoadQueue();

                        if (maxTasks <= 0)
                            maxTasks = 4;
                        if (maxQueue <= 0)
                            maxQueue = 10;

                        if (activeTasks > maxTasks || queueSize > maxQueue) {
                            if (DEBUG) {
                                logger.warn("[EH] High async load ({} active, {} queued), skipping batch", activeTasks,
                                        queueSize);
                            }
                            return;
                        }

                        List<UUID> playerIds = new ArrayList<>();
                        for (UUID playerId : playerStateManager.getAllPlayerIds()) {
                            PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
                            if (state == null || state.getChunkQueue().isEmpty()) {
                                continue;
                            }
                            playerIds.add(playerId);
                        }

                        for (UUID playerId : playerIds) {
                            PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
                            if (state == null) {
                                continue;
                            }
                            Deque<Long> queue = state.getChunkQueue();

                            if (queue == null || queue.isEmpty()) {
                                continue;
                            }

                            Player player = Bukkit.getPlayer(playerId);
                            if (player == null || !player.isOnline()) {
                                playerStateManager.remove(playerId);
                                continue;
                            }

                            if (warmupManager.isWarmupActive(state)) {
                                continue;
                            }

                            processChunkQueue(player, queue);
                        }
                    } catch (Throwable t) {
                        logger.error("[EH] Error in progressive loading task", t);
                    }

                }, 50L, Math.max(50L, configService.get().performance().chunkProcessInterval() * 50L),
                        TimeUnit.MILLISECONDS);

    }

    /**
     * Processes chunk queue for a player with rate limiting
     *
     * @param player The player
     * @param queue  The queue of chunks to process
     */
    private void processChunkQueue(Player player, Deque<Long> queue) {
        if (!player.isOnline() || queue.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerChunkState state = playerStateManager.getOrCreate(uuid);
        Set<Long> sentTracker = state.getFakeChunks();

        int maxChunks = configService.get().bandwidthSaver().maxFakeChunksPerTick();
        List<Long> batch = new ArrayList<>();
        while (!queue.isEmpty() && batch.size() < maxChunks) {
            Long key = queue.poll();
            if (key != null) {
                state.getQueuedChunksSet().remove(key);
                if (!generatingChunks.contains(key)) {
                    batch.add(key);
                }
            }
        }

        if (!batch.isEmpty()) {
            processChunkBatch(player, batch, sentTracker);

            if (DEBUG) {
                logger.info("[EH] Processed {} chunks for {} ({} remaining in queue)",
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
     * @param player      The player to send chunks to
     * @param batch       The list of chunk keys to process
     * @param sentTracker Set tracking which chunks have already been sent to this
     *                    player
     */
    private void processChunkBatch(Player player, List<Long> batch, Set<Long> sentTracker) {
        World world = player.getWorld();

        UUID uuid = player.getUniqueId();
        PlayerChunkState state = playerStateManager.getOrCreate(uuid);

        double borderCenterX = state.getBorderCenterX();
        double borderCenterZ = state.getBorderCenterZ();
        double borderSize = state.getBorderSize();

        for (long key : batch) {
            if (!player.isOnline())
                break;

            generatingChunks.add(key);

            long estimatedChunkSize = configService.get().bandwidthSaver().estimatedPacketSize();
            if (!bandwidthController.canSendData(uuid, estimatedChunkSize)) {
                generatingChunks.remove(key);
                state.getChunkQueue().addFirst(key);
                continue;
            }

            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);

            chunkProcessor.execute(() -> {
                try {
                    // Strategy 1: Try to get chunk from servers memory cache
                    Object memoryChunk = getChunkFromMemoryCache(world, chunkX, chunkZ);
                    if (memoryChunk != null) {
                        if (DEBUG) {
                            logger.info("[EH] Loaded chunk {},{} from memory cache", chunkX, chunkZ);
                        }
                        sendChunkPacket(player, memoryChunk, key, sentTracker,
                                FakeChunkLoadEvent.LoadSource.MEMORY_CACHE, borderCenterX, borderCenterZ, borderSize);
                        return;
                    }

                    // Strategy 3: Try to load chunk from disk
                    loadChunkFromDiskAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);

                } catch (Exception e) {
                    generatingChunks.remove(key);
                    logger.warn("[EH] Error loading chunk {},{}: {}", chunkX, chunkZ, e.getMessage(), e);
                }
            });
        }

    }

    private final AtomicInteger chunksLoadedFromDiskThisTick = new AtomicInteger(0);
    private int maxDiskLoadsPerTick = 20;

    /**
     * Attempts to load chunk from disk without generating
     */
    private void loadChunkFromDiskAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker, final double borderCenterX, final double borderCenterZ, final double borderSize) {

        if (chunksLoadedFromDiskThisTick.get() >= maxDiskLoadsPerTick) {
            if (DEBUG)
                logger.debug("[EH] Disk load limit hit, deferring chunk {},{}", chunkX, chunkZ);
            generatingChunks.remove(key);
            PlayerChunkState limitState = playerStateManager.getOrCreate(player.getUniqueId());
            limitState.getChunkQueue().addFirst(key);
            limitState.getQueuedChunksSet().add(key);
            return;
        }

        chunksLoadedFromDiskThisTick.incrementAndGet();

        world.getChunkAtAsync(chunkX, chunkZ, false).thenAcceptAsync(chunk -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            if (chunk == null || !chunk.isLoaded()) {
                if (chunksGeneratedThisTick.get() >= maxGenerationsPerTick) {
                    if (DEBUG)
                        logger.debug("[EH] Generation limit hit, deferring chunk {},{}", chunkX, chunkZ);
                    generatingChunks.remove(key);
                    PlayerChunkState limitState = playerStateManager.getOrCreate(player.getUniqueId());
                    limitState.getChunkQueue().addFirst(key);
                    limitState.getQueuedChunksSet().add(key);
                    return;
                }

                chunksGeneratedThisTick.incrementAndGet();

                if (DEBUG) {
                    logger.info("[EH] Chunk {},{} not found on disk, generating", chunkX, chunkZ);
                }
                chunkGenerations.incrementAndGet();
                generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
            } else {
                if (DEBUG) {
                    logger.info("[EH] Chunk {},{} loaded from disk", chunkX, chunkZ);
                }
                diskLoads.incrementAndGet();
                Location chunkLocation = new Location(world, chunkX * 16 + 8, 64, chunkZ * 16 + 8);

                Bukkit.getRegionScheduler().run(ExtendedHorizonsPlugin.getInstance(),
                        chunkLocation, (task) -> {
                            if (!player.isOnline()) {
                                generatingChunks.remove(key);
                                return;
                            }

                            world.getChunkAtAsync(chunkX, chunkZ, false).thenAccept(regionChunk -> {
                                if (!player.isOnline()) {
                                    generatingChunks.remove(key);
                                    return;
                                }

                                try {
                                    if (regionChunk != null && regionChunk.isLoaded()) {
                                        Object nmsChunk = nmsChunkAccess.getNMSChunk(regionChunk);
                                        if (nmsChunk != null) {
                                            sendChunkPacket(player, nmsChunk, key,
                                                    sentTracker, FakeChunkLoadEvent.LoadSource.DISK, borderCenterX, borderCenterZ, borderSize);
                                        } else {
                                            // If still null, fallback to generation
                                            if (DEBUG) {
                                                logger.warn("[EH] NMS chunk is null for {},{} even after loading", chunkX, chunkZ);
                                            }
                                            if (chunksGeneratedThisTick.get() < maxGenerationsPerTick) {
                                                chunksGeneratedThisTick.incrementAndGet();
                                                chunkGenerations.incrementAndGet();
                                                generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
                                            } else {
                                                generatingChunks.remove(key);
                                                PlayerChunkState limitState = playerStateManager
                                                        .getOrCreate(player.getUniqueId());
                                                limitState.getChunkQueue().addFirst(key);
                                                limitState.getQueuedChunksSet().add(key);
                                            }
                                        }
                                    } else {
                                        if (DEBUG) {
                                            logger.warn("[EH] Chunk {},{} not loaded after async load", chunkX, chunkZ);
                                        }
                                        if (chunksGeneratedThisTick.get() < maxGenerationsPerTick) {
                                            chunksGeneratedThisTick.incrementAndGet();
                                            chunkGenerations.incrementAndGet();
                                            generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
                                        } else {
                                            generatingChunks.remove(key);
                                            PlayerChunkState limitState = playerStateManager
                                                    .getOrCreate(player.getUniqueId());
                                            limitState.getChunkQueue().addFirst(key);
                                            limitState.getQueuedChunksSet().add(key);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.warn("[EH] Exception getting NMS chunk for {},{}: {}",
                                            chunkX, chunkZ, e.getMessage(), e);
                                    if (chunksGeneratedThisTick.get() < maxGenerationsPerTick) {
                                        chunksGeneratedThisTick.incrementAndGet();
                                        chunkGenerations.incrementAndGet();
                                        generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
                                    } else {
                                        generatingChunks.remove(key);
                                        PlayerChunkState limitState = playerStateManager
                                                .getOrCreate(player.getUniqueId());
                                        limitState.getChunkQueue().addFirst(key);
                                        limitState.getQueuedChunksSet().add(key);
                                    }
                                }
                            }).exceptionally(ex -> {
                                logger.warn("[EH] Failed to load chunk {},{} in region context: {}",
                                        chunkX, chunkZ, ex.getMessage(), ex);
                                if (chunksGeneratedThisTick.get() < maxGenerationsPerTick) {
                                    chunksGeneratedThisTick.incrementAndGet();
                                    chunkGenerations.incrementAndGet();
                                    generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
                                } else {
                                    generatingChunks.remove(key);
                                    PlayerChunkState limitState = playerStateManager
                                            .getOrCreate(player.getUniqueId());
                                    limitState.getChunkQueue().addFirst(key);
                                    limitState.getQueuedChunksSet().add(key);
                                }
                                return null;
                            });
                        });
            }
        }, chunkProcessor).exceptionally(throwable -> {
            if (chunksGeneratedThisTick.get() >= maxGenerationsPerTick) {
                generatingChunks.remove(key);
                PlayerChunkState playerState = playerStateManager.getOrCreate(player.getUniqueId());
                playerState.getChunkQueue().add(key);
                playerState.getQueuedChunksSet().add(key);
                return null;
            }
            chunksGeneratedThisTick.incrementAndGet();

            if (DEBUG) {
                logger.warn("[EH] Failed to process disk chunk {},{}, falling back to generation: {}",
                        chunkX, chunkZ, throwable.getMessage());
            }
            chunkGenerations.incrementAndGet();
            generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
            return null;
        });
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
    public CompletableFuture<Integer> sendFakeChunks(Player player, Set<Long> chunkKeys, double borderCenterX,
            double borderCenterZ, double borderSize) {

        if (!configService.get().performance().fakeChunks().enabled()) {
            return CompletableFuture.completedFuture(0);
        }

        if (chunkKeys.isEmpty()) {
            if (DEBUG)
                logger.info("[EH] No fake chunks to send for {}", player.getName());
            return CompletableFuture.completedFuture(0);
        }

        if (DEBUG)
            logger.info("[EH] sendFakeChunks called for {} with {} chunks", player.getName(), chunkKeys.size());

        if (!isFakeChunksEnabledForWorld(player.getWorld())) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<Integer> result = new CompletableFuture<>();
        UUID uuid = player.getUniqueId();
        PlayerChunkState state = playerStateManager.getOrCreate(uuid);

        state.updateWorldBorder(borderCenterX, borderCenterZ, borderSize);

        Set<Long> playerSentChunks = state.getFakeChunks();

        Set<Long> toRemove = new HashSet<>(playerSentChunks);
        toRemove.removeAll(chunkKeys);

        if (!toRemove.isEmpty()) {
            for (Long key : toRemove) {
                int chunkX = ChunkUtils.unpackX(key);
                int chunkZ = ChunkUtils.unpackZ(key);
                sendUnloadPacket(player, chunkX, chunkZ);
                chunkEventDispatcher.fireUnloadEvent(player, chunkX, chunkZ, player.getWorld(),
                        FakeChunkUnloadEvent.UnloadReason.DISTANCE);
            }
            playerSentChunks.removeAll(toRemove);
        }

        chunkLoadStrategy.onPlayerUpdate(player, state);

        if (chunkLoadStrategy.isWarmupActive(player, state)) {
            chunkLoadStrategy.processWarmup(player, state, chunkKeys);
            return CompletableFuture.completedFuture(0);
        }

        Set<Long> toSend = new HashSet<>();
        List<Long> toGenerate = new ArrayList<>();

        int alreadySent = 0;
        int generating = 0;
        int toProcess = 0;

        for (long key : chunkKeys) {
            if (playerSentChunks.contains(key)) {
                alreadySent++;
                continue;
            }

            if (!generatingChunks.contains(key)) {
                toGenerate.add(key);
                toProcess++;
            } else {
                generating++;
            }
        }

        logger.info("[EH-QUEUE] Player {} | Total chunks: {} | Already sent: {} | Currently generating: {} | To process: {}",
            player.getName(), chunkKeys.size(), alreadySent, generating, toProcess);

        if (!toSend.isEmpty()) {
            toGenerate.addAll(toSend);
        }

        if (!toGenerate.isEmpty()) {
            chunkLoadStrategy.processQueue(player, state, toGenerate, generatingChunks);
            processChunkQueue(player, state.getChunkQueue());
        } else {
            logger.info("[EH-QUEUE] No chunks to process for {} - all chunks already sent or generating", player.getName());
        }

        result.complete(0);
        return result;
    }

    /**
     * Attempts to get a chunk from the servers memory cache or our own cache
     */
    private Object getChunkFromMemoryCache(World world, int chunkX, int chunkZ) {
        if (!configService.get().performance().fakeChunks().enableMemoryCache()) {
            return null;
        }

        long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);

        synchronized (chunkMemoryCache) {
            Object cached = chunkMemoryCache.get(chunkKey);
            if (cached != null) {
                memoryCacheHits.incrementAndGet();
                return cached;
            }
        }

        try {
            Object chunk = nmsChunkAccess.getChunkIfLoaded(world, chunkX, chunkZ);

            if (chunk != null) {
                cacheChunkInMemory(chunkKey, chunk);
                memoryCacheHits.incrementAndGet();
                return chunk;
            }
        } catch (Exception e) {
            if (DEBUG) {
                logger.debug("[EH] Memory cache lookup failed for {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }
        memoryCacheMisses.incrementAndGet();
        return null;
    }

    /**
     * Caches a chunk in memory for reuse
     */
    private void cacheChunkInMemory(long chunkKey, Object chunk) {
        if (!configService.get().performance().fakeChunks().enableMemoryCache()) {
            return;
        }

        synchronized (chunkMemoryCache) {
            chunkMemoryCache.put(chunkKey, chunk);
        }
    }

    /**
     * Generates a new chunk and sends it to the player
     * This is the slowest loading method and should only be used as a last resort
     */
    private void generateChunkAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker, final double borderCenterX, final double borderCenterZ, final double borderSize) {
        world.getChunkAtAsync(chunkX, chunkZ, true).thenAcceptAsync(chunk -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);

                if (nmsChunk != null) {
                    if (DEBUG) {
                        logger.info("[EH] Generated chunk {},{}", chunkX, chunkZ);
                    }
                    long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);
                    cacheChunkInMemory(chunkKey, nmsChunk);
                    sendChunkPacket(player, nmsChunk, key, sentTracker,
                            FakeChunkLoadEvent.LoadSource.GENERATED, borderCenterX, borderCenterZ, borderSize);
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
     * Sends a chunk packet to a player
     */
    private void sendChunkPacket(Player player, Object chunk, long key, Set<Long> sentTracker,
            FakeChunkLoadEvent.LoadSource source, final double borderCenterX, final double borderCenterZ, final double borderSize) {

        int chunkX = ChunkUtils.unpackX(key);
        int chunkZ = ChunkUtils.unpackZ(key);

        boolean isWithinBorder = ChunkUtils.isChunkWithinWorldBorder(borderCenterX, borderCenterZ, borderSize, chunkX, chunkZ);

        if (DEBUG) {
            logger.info("[EH-BORDER] Chunk {},{} | Within border: {} | Border: center=({},{}), size={}",
                chunkX, chunkZ, isWithinBorder, borderCenterX, borderCenterZ, borderSize);
        }

        if (!isWithinBorder) {
            generatingChunks.remove(key);
            sentTracker.add(key); // Mark as processed so it won't be queued again
            logger.info("[EH-BORDER] Skipped chunk {},{} - outside world border (marked as sent)", chunkX, chunkZ);
            return;
        }

        CompletableFuture<Object> packetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (!player.isOnline())
                    return null;

                Object chunkToSend = chunk;
                MainConfig.PerformanceConfig.FakeChunksConfig.AntiXrayConfig antiXray = configService.get().performance().fakeChunks().antiXray();

                if (antiXray != null && antiXray.enabled()) {
                    // Clone chunk to avoid modifying original server chunk
                    chunkToSend = nmsChunkAccess.cloneChunk(chunk);
                    if (chunkToSend != null) {
                        nmsChunkAccess.obfuscateChunk(
                                chunkToSend,
                                antiXray.hideOres(),
                                antiXray.addFakeOres(),
                                antiXray.fakeOreDensity());
                    } else {
                        chunkToSend = chunk;
                    }
                }

                return nmsPacketAccess.createChunkPacket(chunkToSend);
            } catch (Exception e) {
                if (DEBUG)
                    logger.warn("[EH] Failed to create packet: {}", e.getMessage());
                return null;
            }
        }, chunkProcessor);

        packetFuture.thenAccept(packet -> {
            if (packet == null || !player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            World currentWorld = player.getWorld();
            World chunkWorld = ((LevelChunk) chunk).getLevel().getWorld();

            if (!currentWorld.getUID().equals(chunkWorld.getUID())) {
                if (DEBUG)
                    logger.debug("[EH] Discarding packet for {},{}: World mismatch (Player: {}, Chunk: {})",
                            chunkX, chunkZ, currentWorld.getName(), chunkWorld.getName());
                generatingChunks.remove(key);
                return;
            }

            player.getScheduler().run(ExtendedHorizonsPlugin.getInstance(), (task) -> {
                if (!player.isOnline()) {
                    generatingChunks.remove(key);
                    return;
                }

                int size = nmsPacketAccess.getPacketSize(packet);
                bandwidthController.recordDataSent(player.getUniqueId(), size);

                PlayerChunkState state = playerStateManager.getOrCreate(player.getUniqueId());
                state.addBytesThisSecond(size);
                state.addActualBytesSent(size);
                state.getFakeChunks().add(key);
                sentTracker.add(key);
                generatingChunks.remove(key);

                nmsPacketAccess.sendPacket(player, packet);

                chunkEventDispatcher.fireLoadEvent(player, chunkX, chunkZ, currentWorld, source);

                if (DEBUG)
                    logger.info("[EH] Packet sent for {},{} (Size: {}b)", chunkX, chunkZ, size);
            }, null);
        });

    }

    /**
     * Clears fake chunks for a player
     * 
     * @param player      The player to clear chunks for
     * @param sendPackets If true, sends unload packets to client. False if player
     *                    quit or changed worlds
     * @param reason      The reason for unloading the chunks
     */
    public void clearPlayerFakeChunks(Player player, boolean sendPackets,
            FakeChunkUnloadEvent.UnloadReason reason) {
        UUID playerId = player.getUniqueId();
        PlayerChunkState state = playerStateManager.get(playerId).orElse(null);

        if (state == null) {
            return;
        }

        Set<Long> fakeChunks = state.getFakeChunks();

        if (sendPackets && !fakeChunks.isEmpty()) {
            Set<Long> chunksCopy = new java.util.HashSet<>(fakeChunks);
            for (Long key : chunksCopy) {
                int chunkX = ChunkUtils.unpackX(key);
                int chunkZ = ChunkUtils.unpackZ(key);

                chunkEventDispatcher.fireUnloadEvent(player, chunkX, chunkZ, player.getWorld(), reason);

                sendUnloadPacket(player, chunkX, chunkZ);
            }
        }

        playerStateManager.remove(playerId);
    }

    public void clearPlayerFakeChunks(Player player, boolean sendPackets) {
        clearPlayerFakeChunks(player, sendPackets, FakeChunkUnloadEvent.UnloadReason.MANUAL);
    }

    public void clearPlayerFakeChunks(Player player) {
        clearPlayerFakeChunks(player, true, FakeChunkUnloadEvent.UnloadReason.MANUAL);
    }

    /**
     * Sends an unload packet to the client for a specific chunk
     */
    /**
     * Sends an unload packet to the client for a specific chunk
     */
    private void sendUnloadPacket(Player player, int chunkX, int chunkZ) {
        try {
            Object packet = nmsPacketAccess.createUnloadPacket(chunkX, chunkZ);
            nmsPacketAccess.sendPacket(player, packet);
        } catch (Exception e) {
        }
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
        return chunkMemoryCache.size();
    }

    public double getCacheHitRate() {
        long hits = memoryCacheHits.get();
        long misses = memoryCacheMisses.get();
        long total = hits + misses;

        if (total == 0) {
            return 0.0;
        }

        return (hits * 100.0) / total;
    }

    public double getEstimatedMemoryUsageMB() {
        int cacheSize = chunkMemoryCache.size();
        double estimatedBytes = cacheSize * 50_000.0;
        return estimatedBytes / (1024.0 * 1024.0);
    }

    /**
     * Thread pool for parallel chunk processing
     * Size is configurable via config.yml (performance.chunk-processor-threads)
     * Defaults to max(4, availableProcessors) if set to 0
     */
    private final ExecutorService chunkProcessor;

    /**
     * Cache for NMS chunks already loaded in memory
     * Reuses chunks without regenerating them
     */
    private final Map<Long, Object> chunkMemoryCache;

    /**
     * Checks if fake chunks are enabled for a specific world
     * 
     * @param world The world to check
     * @return true if fake chunks are enabled for this world
     */
    public boolean isFakeChunksEnabledForWorld(org.bukkit.World world) {
        String worldName = world.getName();
        Map<String, MainConfig.WorldConfig> worldSettings = configService
                .get().worldSettings();

        if (worldSettings == null || !worldSettings.containsKey(worldName)) {
            return configService.get().performance().fakeChunks().enabled();
        }
        return worldSettings.get(worldName).enabled();
    }

    // ========== Public API Methods ==========

    /**
     * Gets the set of fake chunk keys for a player (for API access).
     *
     * @param playerId The player UUID
     * @return Immutable set of chunk keys, or empty set if none
     */
    public Set<Long> getFakeChunksForPlayer(UUID playerId) {
        return playerStateManager.get(playerId)
                .map(state -> Set.copyOf(state.getFakeChunks()))
                .orElse(Set.of());
    }

    /**
     * Checks if a specific chunk is a fake chunk for a player (for API access).
     *
     * @param playerId The player UUID
     * @param chunkKey The chunk key
     * @return true if the chunk is a fake chunk for this player
     */
    public boolean isFakeChunk(UUID playerId, long chunkKey) {
        return playerStateManager.get(playerId)
                .map(state -> state.getFakeChunks().contains(chunkKey))
                .orElse(false);
    }

    /**
     * Gets the count of fake chunks for a player (for API access).
     *
     * @param playerId The player UUID
     * @return Number of fake chunks
     */
    public int getFakeChunkCount(UUID playerId) {
        return playerStateManager.get(playerId)
                .map(PlayerChunkState::getFakeChunkCount)
                .orElse(0);
    }
}
