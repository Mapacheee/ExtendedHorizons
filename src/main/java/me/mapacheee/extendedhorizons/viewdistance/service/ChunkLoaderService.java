package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.event.FakeChunkLoadEvent;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.viewdistance.service.bandwidth.BandwidthController;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.mapacheee.extendedhorizons.shared.config.MainConfig;
import net.minecraft.world.level.chunk.LevelChunk;

@Service
public class ChunkLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(ChunkLoaderService.class);
    private static final boolean DEBUG = false;

    private final ConfigService configService;
    private final PlayerStateManager playerStateManager;
    private final BandwidthController bandwidthController;
    private final NMSChunkAccess nmsChunkAccess;
    private final NMSPacketAccess nmsPacketAccess;

    private final Map<Long, Long> generatingChunks = new ConcurrentHashMap<>();
    private final ExecutorService chunkProcessor;
    private final Map<Long, Object> chunkMemoryCache;

    private final AtomicLong memoryCacheHits = new AtomicLong(0);
    private final AtomicLong memoryCacheMisses = new AtomicLong(0);
    private final AtomicLong diskLoads = new AtomicLong(0);
    private final AtomicLong chunkGenerations = new AtomicLong(0);

    private final AtomicInteger chunksGeneratedThisTick = new AtomicInteger(0);
    private final AtomicInteger chunksLoadedFromDiskThisTick = new AtomicInteger(0);

    private int maxGenerationsPerTick = 1;
    private int maxDiskLoadsPerTick = 20;

    @Inject
    public ChunkLoaderService(
            ConfigService configService,
            PlayerStateManager playerStateManager,
            BandwidthController bandwidthController,
            NMSChunkAccess nmsChunkAccess,
            NMSPacketAccess nmsPacketAccess) {
        this.configService = configService;
        this.playerStateManager = playerStateManager;
        this.bandwidthController = bandwidthController;
        this.nmsChunkAccess = nmsChunkAccess;
        this.nmsPacketAccess = nmsPacketAccess;

        this.maxGenerationsPerTick = configService.get().performance().maxGenerationsPerTick();
        this.maxDiskLoadsPerTick = configService.get().performance().maxDiskLoadsPerTick();
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
                    Thread t = new Thread(r, "EH-ChunkLoader");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
    }

    public void resetTickCounters() {
        chunksGeneratedThisTick.set(0);
        chunksLoadedFromDiskThisTick.set(0);
        this.maxGenerationsPerTick = configService.get().performance().maxGenerationsPerTick();
        this.maxDiskLoadsPerTick = configService.get().performance().maxDiskLoadsPerTick();
        if (maxGenerationsPerTick <= 0)
            maxGenerationsPerTick = 1;
        if (maxDiskLoadsPerTick <= 0)
            maxDiskLoadsPerTick = 20;

        cleanupStaleGenerations();
    }

    public void cleanupStaleGenerations() {
        long now = System.currentTimeMillis();
        generatingChunks.entrySet().removeIf(entry -> (now - entry.getValue()) > 30000); // 30s timeout
    }

    public ExecutorService getExecutor() {
        return chunkProcessor;
    }

    public Set<Long> getGeneratingChunks() {
        return generatingChunks.keySet();
    }

    public void processChunkBatch(Player player, List<Long> batch, Set<Long> sentTracker) {
        World world = player.getWorld();
        UUID uuid = player.getUniqueId();
        PlayerChunkState state = playerStateManager.getOrCreate(uuid);

        double borderCenterX = state.getBorderCenterX();
        double borderCenterZ = state.getBorderCenterZ();
        double borderSize = state.getBorderSize();

        for (long key : batch) {
            if (!player.isOnline())
                break;

            generatingChunks.put(key, System.currentTimeMillis());

            long estimatedChunkSize = configService.get().bandwidthSaver().estimatedPacketSize();
            if (!bandwidthController.canSendData(uuid, estimatedChunkSize)) {
                generatingChunks.remove(key);
                state.getChunkQueue().addFirst(key);
                continue;
            }

            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);
            UUID playerId = player.getUniqueId();
            String worldName = world.getName();

            try {
                chunkProcessor.execute(() -> {
                    try {
                        Player p = Bukkit.getPlayer(playerId);
                        if (p == null || !p.isOnline()) {
                            generatingChunks.remove(key);
                            return;
                        }

                        World w = Bukkit.getWorld(worldName);
                        if (w == null) {
                            generatingChunks.remove(key);
                            return;
                        }

                        // Strategy 1: Memory cache
                        Object memoryChunk = getChunkFromMemoryCache(w, chunkX, chunkZ);
                        if (memoryChunk != null) {
                            if (DEBUG) {
                                logger.info("[EH] Loaded chunk {},{} from memory cache", chunkX, chunkZ);
                            }
                            sendChunkPacket(p, memoryChunk, key, sentTracker,
                                    FakeChunkLoadEvent.LoadSource.MEMORY_CACHE, borderCenterX, borderCenterZ,
                                    borderSize);
                            return;
                        }

                        // Strategy 2: Disk NBT
                        loadChunkFromDiskAndSend(p, w, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ,
                                borderSize);

                    } catch (Exception e) {
                        generatingChunks.remove(key);
                        logger.warn("[EH] Error loading chunk {},{}: {}", chunkX, chunkZ, e.getMessage(), e);
                    }
                });
            } catch (Exception e) {
                generatingChunks.remove(key);
                logger.warn("[EH] Failed to submit chunk {},{} for processing: {}", chunkX, chunkZ, e.getMessage());
            }
        }
    }

    private void loadChunkFromDiskAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker, final double borderCenterX, final double borderCenterZ,
            final double borderSize) {

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
        UUID playerId = player.getUniqueId();

        // Refresh timestamp to prevent premature cleanup during disk I/O
        generatingChunks.put(key, System.currentTimeMillis());

        world.getChunkAtAsync(chunkX, chunkZ, false).thenAcceptAsync(chunk -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            if (chunk == null || !chunk.isLoaded()) {
                if (chunksGeneratedThisTick.get() >= maxGenerationsPerTick) {
                    if (DEBUG)
                        logger.debug("[EH] Generation limit hit, deferring chunk {},{}", chunkX, chunkZ);
                    generatingChunks.remove(key);
                    PlayerChunkState limitState = playerStateManager.getOrCreate(playerId);
                    limitState.getChunkQueue().addFirst(key);
                    limitState.getQueuedChunksSet().add(key);
                    return;
                }

                chunksGeneratedThisTick.incrementAndGet();
                if (DEBUG)
                    logger.info("[EH] Chunk {},{} not found on disk, generating", chunkX, chunkZ);
                chunkGenerations.incrementAndGet();
                World w = Bukkit.getWorld(world.getName());
                if (w != null) {
                    generateChunkAndSend(p, w, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ,
                            borderSize);
                } else {
                    generatingChunks.remove(key);
                }
            } else {
                if (DEBUG)
                    logger.info("[EH] Chunk {},{} loaded from disk", chunkX, chunkZ);
                diskLoads.incrementAndGet();

                UUID playerId2 = playerId;
                String worldName = world.getName();
                World w2 = Bukkit.getWorld(worldName);
                if (w2 == null) {
                    generatingChunks.remove(key);
                    return;
                }

                Location chunkLocation = new Location(w2, chunkX * 16 + 8, 64, chunkZ * 16 + 8);

                Bukkit.getRegionScheduler().run(ExtendedHorizonsPlugin.getInstance(),
                        chunkLocation, (task) -> {
                            Player p2 = Bukkit.getPlayer(playerId2);
                            if (p2 == null || !p2.isOnline()) {
                                generatingChunks.remove(key);
                                return;
                            }

                            World w3 = Bukkit.getWorld(worldName);
                            if (w3 == null) {
                                generatingChunks.remove(key);
                                return;
                            }

                            w3.getChunkAtAsync(chunkX, chunkZ, false).thenAccept(regionChunk -> {
                                Player p3 = Bukkit.getPlayer(playerId2);
                                if (p3 == null || !p3.isOnline()) {
                                    generatingChunks.remove(key);
                                    return;
                                }

                                try {
                                    if (regionChunk != null && regionChunk.isLoaded()) {
                                        Object nmsChunk = nmsChunkAccess.getNMSChunk(regionChunk);
                                        if (nmsChunk != null) {
                                            sendChunkPacket(p3, nmsChunk, key,
                                                    sentTracker, FakeChunkLoadEvent.LoadSource.DISK, borderCenterX,
                                                    borderCenterZ, borderSize);
                                        } else {
                                            fallbackToGeneration(p3, worldName, chunkX, chunkZ, key, sentTracker,
                                                    borderCenterX, borderCenterZ, borderSize);
                                        }
                                    } else {
                                        fallbackToGeneration(p3, worldName, chunkX, chunkZ, key, sentTracker,
                                                borderCenterX, borderCenterZ, borderSize);
                                    }
                                } catch (Exception e) {
                                    logger.warn("[EH] Exception getting NMS chunk for {},{}: {}", chunkX, chunkZ,
                                            e.getMessage(), e);
                                    fallbackToGeneration(p3, worldName, chunkX, chunkZ, key, sentTracker, borderCenterX,
                                            borderCenterZ, borderSize);
                                }
                            }).exceptionally(ex -> {
                                logger.warn("[EH] Failed to load chunk {},{} in region context: {}", chunkX, chunkZ,
                                        ex.getMessage(), ex);
                                Player p4 = Bukkit.getPlayer(playerId2);
                                if (p4 != null && p4.isOnline()) {
                                    fallbackToGeneration(p4, worldName, chunkX, chunkZ, key, sentTracker, borderCenterX,
                                            borderCenterZ, borderSize);
                                } else {
                                    generatingChunks.remove(key);
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
            if (DEBUG)
                logger.warn("[EH] Failed to process disk chunk {},{}, falling back to generation: {}", chunkX, chunkZ,
                        throwable.getMessage());
            chunkGenerations.incrementAndGet();
            generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ,
                    borderSize);
            return null;
        });
    }

    private void fallbackToGeneration(Player p, String worldName, int chunkX, int chunkZ, long key,
            Set<Long> sentTracker, double borderCenterX, double borderCenterZ, double borderSize) {
        if (chunksGeneratedThisTick.get() < maxGenerationsPerTick) {
            chunksGeneratedThisTick.incrementAndGet();
            chunkGenerations.incrementAndGet();
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                generateChunkAndSend(p, w, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ, borderSize);
            } else {
                generatingChunks.remove(key);
            }
        } else {
            generatingChunks.remove(key);
            PlayerChunkState limitState = playerStateManager.getOrCreate(p.getUniqueId());
            limitState.getChunkQueue().addFirst(key);
            limitState.getQueuedChunksSet().add(key);
        }
    }

    private void generateChunkAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker, final double borderCenterX, final double borderCenterZ,
            final double borderSize) {
        UUID playerId = player.getUniqueId();
        String worldName = world.getName();
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            generatingChunks.remove(key);
            return;
        }

        w.getChunkAtAsync(chunkX, chunkZ, true).thenAcceptAsync(chunk -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);
                if (nmsChunk != null) {
                    if (DEBUG)
                        logger.info("[EH] Generated chunk {},{}", chunkX, chunkZ);
                    long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);
                    cacheChunkInMemory(chunkKey, nmsChunk);
                    sendChunkPacket(p, nmsChunk, key, sentTracker,
                            FakeChunkLoadEvent.LoadSource.GENERATED, borderCenterX, borderCenterZ, borderSize);
                } else {
                    generatingChunks.remove(key);
                    if (DEBUG)
                        logger.warn("[EH] Generated chunk {},{} is null", chunkX, chunkZ);
                }
            } catch (Exception e) {
                generatingChunks.remove(key);
                if (DEBUG)
                    logger.warn("[EH] Failed to process generated chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }, chunkProcessor).exceptionally(throwable -> {
            generatingChunks.remove(key);
            logger.warn("[EH] Failed to generate chunk {},{}: {}", chunkX, chunkZ, throwable.getMessage());
            return null;
        });
    }

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
            if (DEBUG)
                logger.debug("[EH] Memory cache lookup failed for {},{}: {}", chunkX, chunkZ, e.getMessage());
        }
        memoryCacheMisses.incrementAndGet();
        return null;
    }

    private void cacheChunkInMemory(long chunkKey, Object chunk) {
        if (!configService.get().performance().fakeChunks().enableMemoryCache())
            return;
        synchronized (chunkMemoryCache) {
            chunkMemoryCache.put(chunkKey, chunk);
        }
    }

    private void sendChunkPacket(Player player, Object chunk, long key, Set<Long> sentTracker,
            FakeChunkLoadEvent.LoadSource source, final double borderCenterX, final double borderCenterZ,
            final double borderSize) {

        int chunkX = ChunkUtils.unpackX(key);
        int chunkZ = ChunkUtils.unpackZ(key);

        boolean isWithinBorder = ChunkUtils.isChunkWithinWorldBorder(borderCenterX, borderCenterZ, borderSize, chunkX,
                chunkZ);

        if (DEBUG) {
            logger.info("[EH-BORDER] Chunk {},{} | Within border: {} | Border: center=({},{}), size={}",
                    chunkX, chunkZ, isWithinBorder, borderCenterX, borderCenterZ, borderSize);
        }

        if (!isWithinBorder) {
            generatingChunks.remove(key);
            sentTracker.add(key);
            logger.info("[EH-BORDER] Skipped chunk {},{} - outside world border (marked as sent)", chunkX, chunkZ);
            return;
        }

        UUID playerId = player.getUniqueId();

        CompletableFuture<Object> packetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline())
                    return null;

                Object chunkToSend = chunk;
                MainConfig.PerformanceConfig.FakeChunksConfig.AntiXrayConfig antiXray = configService.get()
                        .performance().fakeChunks().antiXray();

                if (antiXray != null && antiXray.enabled()) {
                    CompletableFuture<Object> cloneFuture = new CompletableFuture<>();
                    final Object sourceChunk = chunkToSend;

                    Bukkit.getScheduler().runTask(ExtendedHorizonsPlugin.getInstance(), () -> {
                        try {
                            cloneFuture.complete(nmsChunkAccess.cloneChunk(sourceChunk));
                        } catch (Exception ex) {
                            cloneFuture.completeExceptionally(ex);
                        }
                    });

                    try {
                        chunkToSend = cloneFuture.get();
                    } catch (Exception ex) {
                        if (DEBUG)
                            logger.warn("[EH] Failed to clone chunk on main thread: {}", ex.getMessage());
                        chunkToSend = chunk;
                    }

                    if (chunkToSend != null && chunkToSend != chunk) {
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
            Player p = Bukkit.getPlayer(playerId);
            if (packet == null || p == null || !p.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {

                World currentWorld = p.getWorld();
                World chunkWorld = null;
                if (chunk instanceof LevelChunk) {
                    chunkWorld = ((LevelChunk) chunk).getLevel().getWorld();
                }

                if (chunkWorld != null && !currentWorld.getUID().equals(chunkWorld.getUID())) {
                    if (DEBUG)
                        logger.debug("[EH] Discarding packet for {},{}: World mismatch (Player: {}, Chunk: {})",
                                chunkX, chunkZ, currentWorld.getName(), chunkWorld.getName());
                    generatingChunks.remove(key);
                    return;
                }
            } catch (Exception e) {
            }

            PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
            if (state == null) {
                generatingChunks.remove(key);
                return;
            }

            nmsPacketAccess.sendPacket(p, packet);

            state.getFakeChunks().add(key);
            sentTracker.add(key);
            generatingChunks.remove(key);

            long size = nmsPacketAccess.getPacketSize(packet);
            bandwidthController.recordDataSent(player.getUniqueId(), size);

            if (DEBUG)
                logger.debug("[EH] Sent chunk packet for {},{} to {} (source: {})", chunkX, chunkZ, p.getName(),
                        source);

            FakeChunkLoadEvent event = new FakeChunkLoadEvent(p, chunkX, chunkZ, p.getWorld(), source);
            Bukkit.getPluginManager().callEvent(event);
        });

    }

    public Map<String, Long> getStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("memory_hits", memoryCacheHits.get());
        stats.put("memory_misses", memoryCacheMisses.get());
        stats.put("disk_loads", diskLoads.get());
        stats.put("generations", chunkGenerations.get());
        return stats;
    }

    public void shutdown() {
        chunkProcessor.shutdown();
        chunkMemoryCache.clear();
    }

    public void clearMemoryCache() {
        chunkMemoryCache.clear();
    }

    public int getMemoryCacheSize() {
        return chunkMemoryCache.size();
    }

    public void invalidateChunk(long chunkKey) {
        if (chunkMemoryCache.containsKey(chunkKey)) {
            chunkMemoryCache.remove(chunkKey);
            if (DEBUG) {
                int x = ChunkUtils.unpackX(chunkKey);
                int z = ChunkUtils.unpackZ(chunkKey);
                logger.debug("[EH] Invalidated cache for chunk {},{}", x, z);
            }
        }
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
}
