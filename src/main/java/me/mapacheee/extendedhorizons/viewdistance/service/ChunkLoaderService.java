package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.event.FakeChunkLoadEvent;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.storage.PacketCacheStorageService;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.viewdistance.service.bandwidth.BandwidthController;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import org.bukkit.Bukkit;

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
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import me.mapacheee.extendedhorizons.viewdistance.service.cache.ChunkCacheKey;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ChunkLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(ChunkLoaderService.class);
    private static final boolean DEBUG = false;

    private final ConfigService configService;
    private final PlayerStateManager playerStateManager;
    private final BandwidthController bandwidthController;
    private final NMSChunkAccess nmsChunkAccess;
    private final NMSPacketAccess nmsPacketAccess;
    private final PacketCacheStorageService packetCacheStorage;

    private final Map<Long, Long> generatingChunks = new ConcurrentHashMap<>();
    private final ExecutorService chunkProcessor;
    private final Map<ChunkCacheKey, Object> chunkMemoryCache;
    private final Map<ChunkCacheKey, Object> packetCache;

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
            NMSPacketAccess nmsPacketAccess,
            PacketCacheStorageService packetCacheStorage) {
        this.configService = configService;
        this.playerStateManager = playerStateManager;
        this.bandwidthController = bandwidthController;
        this.nmsChunkAccess = nmsChunkAccess;
        this.nmsPacketAccess = nmsPacketAccess;
        this.packetCacheStorage = packetCacheStorage;

        this.maxGenerationsPerTick = 1;
        this.maxDiskLoadsPerTick = 20;
        int maxCacheSize = 1000;

        this.chunkMemoryCache = Collections.synchronizedMap(
                new LinkedHashMap<ChunkCacheKey, Object>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<ChunkCacheKey, Object> eldest) {
                        return size() > maxCacheSize;
                    }
                });

        int maxPacketCacheSize = configService.get().performance().fakeChunks().maxCachedPackets();
        this.packetCache = Collections.synchronizedMap(
                new LinkedHashMap<ChunkCacheKey, Object>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<ChunkCacheKey, Object> eldest) {
                        return size() > maxPacketCacheSize;
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
                        Object memoryChunk = getChunkFromMemoryCache(w.getUID(), chunkX, chunkZ);
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
                logger.info("[EH] Disk load limit hit, deferring chunk {},{}", chunkX, chunkZ);
            generatingChunks.remove(key);
            PlayerChunkState limitState = playerStateManager.getOrCreate(player.getUniqueId());
            limitState.getChunkQueue().addFirst(key);
            limitState.getQueuedChunksSet().add(key);
            return;
        }

        chunksLoadedFromDiskThisTick.incrementAndGet();
        UUID playerId = player.getUniqueId();

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
                        logger.info("[EH] Generation limit hit, deferring chunk {},{}", chunkX, chunkZ);
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
                diskLoads.incrementAndGet();

                try {
                    Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);
                    if (nmsChunk != null) {
                        sendChunkPacket(p, nmsChunk, key, sentTracker, FakeChunkLoadEvent.LoadSource.DISK,
                                borderCenterX, borderCenterZ, borderSize);
                    } else {
                        fallbackToGeneration(p, world.getName(), chunkX, chunkZ, key, sentTracker,
                                borderCenterX, borderCenterZ, borderSize);
                    }
                } catch (Exception e) {
                    logger.warn("[EH] Exception getting NMS chunk for {},{}: {}", chunkX, chunkZ, e.getMessage(), e);
                    generatingChunks.remove(key);
                }
            }
        }, chunkProcessor);
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
                    cacheChunkInMemory(chunk.getWorld().getUID(), chunkKey, nmsChunk);
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

    private Object getChunkFromMemoryCache(UUID worldId, int chunkX, int chunkZ) {
        if (!configService.get().performance().fakeChunks().enableMemoryCache()) {
            return null;
        }
        long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);
        ChunkCacheKey cacheKey = new ChunkCacheKey(worldId, chunkKey);

        synchronized (chunkMemoryCache) {
            Object cached = chunkMemoryCache.get(cacheKey);
            if (cached != null) {
                memoryCacheHits.incrementAndGet();
                return cached;
            }
        }

        World world = Bukkit.getWorld(worldId);
        if (world == null)
            return null;

        try {
            Object chunk = nmsChunkAccess.getChunkIfLoaded(world, chunkX, chunkZ);
            if (chunk != null) {
                cacheChunkInMemory(worldId, chunkKey, chunk);
                memoryCacheHits.incrementAndGet();
                return chunk;
            }
        } catch (Exception e) {
            if (DEBUG)
                logger.info("[EH] Memory cache lookup failed for {},{}: {}", chunkX, chunkZ, e.getMessage());
        }
        memoryCacheMisses.incrementAndGet();
        return null;
    }

    private void cacheChunkInMemory(UUID worldId, long chunkKey, Object chunk) {
        if (!configService.get().performance().fakeChunks().enableMemoryCache())
            return;
        ChunkCacheKey key = new ChunkCacheKey(worldId, chunkKey);
        synchronized (chunkMemoryCache) {
            chunkMemoryCache.put(key, chunk);
        }
    }

    private void sendChunkPacket(Player player, Object chunk, long key, Set<Long> sentTracker,
            FakeChunkLoadEvent.LoadSource source, final double borderCenterX, final double borderCenterZ,
            final double borderSize) {

        int chunkX = ChunkUtils.unpackX(key);
        int chunkZ = ChunkUtils.unpackZ(key);

        boolean isWithinBorder = ChunkUtils.isChunkWithinWorldBorder(borderCenterX, borderCenterZ, borderSize, chunkX,
                chunkZ);

        if (!isWithinBorder) {
            generatingChunks.remove(key);
            sentTracker.add(key);
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID worldId = player.getWorld().getUID();
        ChunkCacheKey cacheKey = new ChunkCacheKey(worldId, key);

        // 1. RAM Cache Check (Fast)
        Object cachedPacket = packetCache.get(cacheKey);
        if (cachedPacket != null) {
            scheduleSend(playerId, key, cachedPacket, source, chunkX, chunkZ, sentTracker);
            return;
        }

        // 2. Disk Cache Check (Async)
        packetCacheStorage.getCachedPacket(worldId, chunkX, chunkZ).thenAcceptAsync(diskBytes -> {
            Player pCheck = Bukkit.getPlayer(playerId);
            if (pCheck == null || !pCheck.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            if (diskBytes != null) {
                try {
                    Object packet = nmsPacketAccess.deserializeChunkPacket(diskBytes);
                    if (packet != null) {
                        packetCache.put(cacheKey, packet);
                        scheduleSend(playerId, key, packet, source, chunkX, chunkZ, sentTracker);
                        return;
                    }
                } catch (Exception e) {
                }
            }

            // 3. Fallback: Generate Packet
            try {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) {
                    generatingChunks.remove(key);
                    return;
                }

                PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
                if (state == null) {
                    generatingChunks.remove(key);
                    return;
                }

                Object chunkToSend = chunk;
                Object packet = null;
                try {
                    boolean surfaceOnlyMode = configService.get().performance().fakeChunks().surfaceOnlyMode();
                    if (surfaceOnlyMode) {
                        int depth = configService.get().performance().fakeChunks().depthBelowSurface();
                        packet = nmsPacketAccess.createSurfaceOnlyChunkPacket(chunkToSend, depth);
                    } else {
                        packet = nmsPacketAccess.createChunkPacket(chunkToSend);
                    }
                } catch (Throwable t) {
                    if (DEBUG)
                        logger.warn("[EH] Failed to create chunk packet for {},{}: {}", chunkX, chunkZ, t.getMessage());
                    generatingChunks.remove(key);
                    return;
                }

                if (packet == null) {
                    generatingChunks.remove(key);
                    return;
                }

                packetCache.put(cacheKey, packet);

                final Object packetFinal = packet;
                try {
                    byte[] data = nmsPacketAccess.serializeChunkPacket(packetFinal);
                    if (data != null) {
                        packetCacheStorage.saveCachedPacket(worldId, chunkX, chunkZ, data);
                    }
                } catch (Exception e) {
                }

                sendPacketOnMainThread(p, packet, key, source, chunkX, chunkZ, sentTracker, state);

            } catch (Exception e) {
                handleGenerationFailure(playerId, key, e);
            }
        }, chunkProcessor);
    }

    private void handleGenerationFailure(UUID playerId, long key, Throwable t) {
        generatingChunks.remove(key);
        if (DEBUG) {
            logger.warn("[EH] Async chunk generation failed for chunk key {} (Player: {}), requeueing...", key,
                    playerId, t);
        }

        playerStateManager.get(playerId).ifPresent(state -> {
            if (!state.getFakeChunks().contains(key)) {
                state.getChunkQueue().addFirst(key);
                state.getQueuedChunksSet().add(key);
            }
        });
    }

    private final java.util.concurrent.atomic.AtomicInteger pendingSends = new java.util.concurrent.atomic.AtomicInteger(
            0);

    public int getPendingSends() {
        return pendingSends.get();
    }

    private void sendPacketOnMainThread(Player p, Object packet, long key, FakeChunkLoadEvent.LoadSource source,
            int chunkX,
            int chunkZ, Set<Long> sentTracker, PlayerChunkState state) {

        if (!p.isOnline()) {
            generatingChunks.remove(key);
            return;
        }

        pendingSends.incrementAndGet();
        try {
            p.getScheduler().run(ExtendedHorizonsPlugin.getInstance(), (ScheduledTask task) -> {
                try {
                    sendPacketAndFinish(p, packet, key, source, chunkX, chunkZ, sentTracker, state);
                } finally {
                    pendingSends.decrementAndGet();
                }
            }, null);
        } catch (Exception e) {
            pendingSends.decrementAndGet();
            generatingChunks.remove(key);
        }
    }

    private void scheduleSend(UUID playerId, long key, Object packet, FakeChunkLoadEvent.LoadSource source, int chunkX,
            int chunkZ, Set<Long> sentTracker) {
        Player p = Bukkit.getPlayer(playerId);
        if (p == null || !p.isOnline()) {
            generatingChunks.remove(key);
            return;
        }

        PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
        if (state == null) {
            generatingChunks.remove(key);
            return;
        }

        p.getScheduler().run(ExtendedHorizonsPlugin.getInstance(), (ScheduledTask task) -> {
            sendPacketAndFinish(p, packet, key, source, chunkX, chunkZ, sentTracker, state);
        }, null);
    }

    private void sendPacketAndFinish(Player p, Object packet, long key, FakeChunkLoadEvent.LoadSource source,
            int chunkX, int chunkZ, Set<Long> sentTracker, PlayerChunkState state) {
        try {
            nmsPacketAccess.sendPacket(p, packet);

            state.getFakeChunks().add(key);
            sentTracker.add(key);
            generatingChunks.remove(key);

            long size = nmsPacketAccess.getPacketSize(packet);
            bandwidthController.recordDataSent(p.getUniqueId(), size);

            if (DEBUG)
                logger.info("[EH] Sent fake chunk packet for {},{} to {} (source: {})", chunkX, chunkZ, p.getName(),
                        source);

            FakeChunkLoadEvent event = new FakeChunkLoadEvent(p, chunkX, chunkZ, p.getWorld(), source);
            Bukkit.getPluginManager().callEvent(event);
        } catch (Exception e) {
            generatingChunks.remove(key);
        }
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
        packetCache.clear();
    }

    public void invalidateRamCache(UUID worldId, int chunkX, int chunkZ) {
        long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);
        ChunkCacheKey key = new ChunkCacheKey(worldId, chunkKey);
        packetCache.remove(key);

        packetCacheStorage.invalidate(worldId, chunkX, chunkZ);

        if (DEBUG) {
            logger.info("[EH] Invalidated cache for {},{}", chunkX, chunkZ);
        }
    }

    public int getMemoryCacheSize() {
        return chunkMemoryCache.size();
    }

    public void invalidateChunk(UUID worldId, long chunkKey) {
        ChunkCacheKey key = new ChunkCacheKey(worldId, chunkKey);
        if (chunkMemoryCache.containsKey(key)) {
            chunkMemoryCache.remove(key);
            if (DEBUG) {
                int x = ChunkUtils.unpackX(chunkKey);
                int z = ChunkUtils.unpackZ(chunkKey);
                logger.info("[EH] Invalidated cache for chunk {},{}", x, z);
            }
        }
    }

    public void invalidateWorld(UUID worldId) {
        int initialSize = chunkMemoryCache.size();
        synchronized (chunkMemoryCache) {
            chunkMemoryCache.keySet().removeIf(key -> key.worldId().equals(worldId));
        }
        int removed = initialSize - chunkMemoryCache.size();
        if (removed > 0) {
            logger.info("[EH] Invalidated {} chunks for world {}", removed, worldId);
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
