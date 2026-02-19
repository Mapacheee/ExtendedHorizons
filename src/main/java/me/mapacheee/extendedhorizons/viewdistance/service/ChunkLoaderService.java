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
    private final Map<ChunkCacheKey, byte[]> packetCache;

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

        int configuredCacheSize = configService.get().performance().fakeChunks().maxMemoryCacheSize();
        final int maxCacheSize = (configuredCacheSize <= 0) ? 500 : Math.min(500, configuredCacheSize);

        this.chunkMemoryCache = Collections.synchronizedMap(
                new LinkedHashMap<ChunkCacheKey, Object>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<ChunkCacheKey, Object> eldest) {
                        return size() > maxCacheSize;
                    }
                });

        int maxPacketCacheSize = configService.get().performance().fakeChunks().maxCachedPackets();
        if (maxPacketCacheSize <= 0 || maxPacketCacheSize > 2000) {
            maxPacketCacheSize = 1000;
        }
        final int finalPacketCacheSize = maxPacketCacheSize;

        this.packetCache = Collections.synchronizedMap(
                new LinkedHashMap<ChunkCacheKey, byte[]>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<ChunkCacheKey, byte[]> eldest) {
                        return size() > finalPacketCacheSize;
                    }
                });

        int configuredThreads = configService.get().performance().chunkProcessorThreads();
        int threadCount = configuredThreads > 0
                ? Math.min(configuredThreads, 8)
                : Math.min(4, Runtime.getRuntime().availableProcessors());

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
        UUID worldId = world.getUID();

        for (long key : batch) {
            if (!player.isOnline())
                break;

            if (sentTracker.contains(key)) {
                continue;
            }

            if (generatingChunks.containsKey(key)) {
                continue;
            }

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
            ChunkCacheKey cacheKey = new ChunkCacheKey(worldId, key);

            try {
                byte[] cachedBytes = packetCache.get(cacheKey);
                if (cachedBytes != null) {
                    chunkProcessor.execute(() -> {
                        Player p = Bukkit.getPlayer(playerId);
                        if (p == null || !p.isOnline()) {
                            generatingChunks.remove(key);
                            return;
                        }
                        Object packet = nmsPacketAccess.deserializeChunkPacket(cachedBytes);
                        if (packet != null) {
                            scheduleSend(playerId, key, packet, FakeChunkLoadEvent.LoadSource.MEMORY_CACHE, chunkX,
                                    chunkZ, sentTracker);
                        } else {
                            generatingChunks.remove(key);
                        }
                    });
                    continue;
                }

                packetCacheStorage.getCachedPacket(worldId, chunkX, chunkZ).thenAcceptAsync(diskBytes -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) {
                        generatingChunks.remove(key);
                        return;
                    }

                    if (diskBytes != null) {
                        try {
                            Object packet = nmsPacketAccess.deserializeChunkPacket(diskBytes);
                            if (packet != null) {
                                packetCache.put(cacheKey, diskBytes);
                                scheduleSend(playerId, key, packet, FakeChunkLoadEvent.LoadSource.DISK_CACHE, chunkX,
                                        chunkZ, sentTracker);
                                return;
                            }
                        } catch (Exception e) {
                        }
                    }

                    loadChunkFromDiskAndSend(p, world, chunkX, chunkZ, key, sentTracker, borderCenterX, borderCenterZ,
                            borderSize);

                }, chunkProcessor).exceptionally(ex -> {
                    generatingChunks.remove(key);
                    return null;
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

        if (isServerLagging()) {
            deferChunk(player, key, "High MSPT");
            return;
        }

        if (chunksLoadedFromDiskThisTick.get() >= maxDiskLoadsPerTick) {
            deferChunk(player, key, "Disk load limit hit");
            return;
        }

        chunksLoadedFromDiskThisTick.incrementAndGet();
        UUID playerId = player.getUniqueId();

        generatingChunks.put(key, System.currentTimeMillis());

        world.getChunkAtAsync(chunkX, chunkZ, false).thenAccept(chunk -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            if (chunk == null || !chunk.isLoaded()) {
                if (chunksGeneratedThisTick.get() >= maxGenerationsPerTick) {
                    deferChunk(p, key, "Generation limit hit");
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
                    Object packet = null;
                    if (nmsChunk != null) {
                        try {
                            boolean surfaceOnlyMode = configService.get().performance().fakeChunks().surfaceOnlyMode();
                            if (surfaceOnlyMode) {
                                int depth = configService.get().performance().fakeChunks().depthBelowSurface();
                                packet = nmsPacketAccess.createSurfaceOnlyChunkPacket(nmsChunk, depth);
                            } else {
                                packet = nmsPacketAccess.createChunkPacket(nmsChunk);
                            }
                        } catch (Throwable t) {
                            logger.warn("[EH] Failed to create packet from disk chunk {},{}: {}", chunkX, chunkZ,
                                    t.getMessage());
                        }
                    } else {
                        fallbackToGeneration(p, world.getName(), chunkX, chunkZ, key, sentTracker,
                                borderCenterX, borderCenterZ, borderSize);
                        return;
                    }

                    if (packet != null) {
                        final Object packetFinal = packet;
                        chunkProcessor.execute(() -> {
                            try {
                                byte[] data = nmsPacketAccess.serializeChunkPacket(packetFinal);
                                if (data != null) {
                                    packetCacheStorage.saveCachedPacket(world.getUID(), chunkX, chunkZ, data);
                                    ChunkCacheKey cacheKey = new ChunkCacheKey(world.getUID(), key);
                                    packetCache.put(cacheKey, data);
                                }

                                sendPacketOnMainThread(p, packetFinal, key, FakeChunkLoadEvent.LoadSource.DISK,
                                        chunkX, chunkZ, sentTracker, null);

                            } catch (Exception e) {
                                generatingChunks.remove(key);
                            }
                        });
                    } else {
                        generatingChunks.remove(key);
                    }

                } catch (Exception e) {
                    logger.warn("[EH] Exception processing disk chunk for {},{}: {}", chunkX, chunkZ, e.getMessage(),
                            e);
                    generatingChunks.remove(key);
                }
            }
        }).exceptionally(throwable -> {
            generatingChunks.remove(key);
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
            deferChunk(p, key, "Generation limit hit (Fallback)");
        }
    }

    private void generateChunkAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker, final double borderCenterX, final double borderCenterZ,
            final double borderSize) {

        if (isServerLagging()) {
            deferChunk(player, key, "High MSPT (Generation)");
            return;
        }

        UUID playerId = player.getUniqueId();
        String worldName = world.getName();
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            generatingChunks.remove(key);
            return;
        }

        w.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                Object nmsChunk = nmsChunkAccess.getNMSChunk(chunk);
                Object packet = null;

                if (nmsChunk != null) {
                    if (DEBUG)
                        logger.info("[EH] Generated chunk {},{}", chunkX, chunkZ);
                    try {
                        boolean surfaceOnlyMode = configService.get().performance().fakeChunks().surfaceOnlyMode();
                        if (surfaceOnlyMode) {
                            int depth = configService.get().performance().fakeChunks().depthBelowSurface();
                            packet = nmsPacketAccess.createSurfaceOnlyChunkPacket(nmsChunk, depth);
                        } else {
                            packet = nmsPacketAccess.createChunkPacket(nmsChunk);
                        }
                    } catch (Throwable t) {
                        logger.warn("[EH] Failed to create packet object for {},{}: {}", chunkX, chunkZ,
                                t.getMessage());
                    }
                }

                if (packet == null) {
                    generatingChunks.remove(key);
                    return;
                }

                final Object packetFinal = packet;
                chunkProcessor.execute(() -> {
                    try {
                        byte[] data = nmsPacketAccess.serializeChunkPacket(packetFinal);
                        if (data != null) {
                            packetCacheStorage.saveCachedPacket(w.getUID(), chunkX, chunkZ, data);
                            ChunkCacheKey cacheKey = new ChunkCacheKey(w.getUID(), key);
                            packetCache.put(cacheKey, data);
                        }

                        sendPacketOnMainThread(p, packetFinal, key, FakeChunkLoadEvent.LoadSource.GENERATED,
                                chunkX, chunkZ, sentTracker, null);

                    } catch (Exception e) {
                        handleGenerationFailure(playerId, key, e);
                    }
                });

            } catch (Exception e) {
                generatingChunks.remove(key);
                logger.warn("[EH] Failed to process generated chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }).exceptionally(throwable -> {
            generatingChunks.remove(key);
            logger.warn("[EH] Failed to generate chunk {},{}: {}", chunkX, chunkZ, throwable.getMessage());
            return null;
        });
    }

    private boolean isServerLagging() {
        try {
            double mspt = Bukkit.getAverageTickTime();
            double maxMspt = configService.get().performance().maxMsptForLoading();
            return maxMspt > 0 && mspt > maxMspt;
        } catch (Exception e) {
            return false;
        }
    }

    private void deferChunk(Player player, long key, String reason) {
        if (DEBUG) {
            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);
            logger.info("[EH] {}, deferring chunk {},{} (Key: {})", reason, chunkX, chunkZ, key);
        }
        generatingChunks.remove(key);
        PlayerChunkState limitState = playerStateManager.getOrCreate(player.getUniqueId());
        if (!limitState.getFakeChunks().contains(key)) {
            limitState.getChunkQueue().addFirst(key);
            limitState.getQueuedChunksSet().add(key);
        }
    }

    private void handleGenerationFailure(UUID playerId, long key, Throwable t) {
        generatingChunks.remove(key);
        if (DEBUG) {
            logger.warn("[EH] Async chunk generation failed for chunk key {} (Player: {}), requeueing...", key,
                    playerId, t);
        }
        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
            deferChunk(p, key, "Generation Failure");
        }
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

        // Check if chunk was already sent (race condition protection from cache)
        if (state.getFakeChunks().contains(key)) {
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
            // Final check: skip if already sent (race condition protection)
            if (state.getFakeChunks().contains(key)) {
                generatingChunks.remove(key);
                return;
            }

            if (!state.canAddMoreFakeChunks()) {
                generatingChunks.remove(key);
                if (DEBUG)
                    logger.info("[EH] Player {} reached fake chunk limit, skipping {},{}",
                            p.getName(), chunkX, chunkZ);
                return;
            }

            nmsPacketAccess.sendPacket(p, packet);

            state.addFakeChunk(key);
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
