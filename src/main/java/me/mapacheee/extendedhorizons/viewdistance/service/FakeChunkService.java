package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.event.FakeChunkUnloadEvent;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.config.MainConfig;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.mapacheee.extendedhorizons.viewdistance.service.bandwidth.BandwidthController;
import me.mapacheee.extendedhorizons.viewdistance.service.event.ChunkEventDispatcher;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import me.mapacheee.extendedhorizons.viewdistance.service.player.WarmupManager;
import me.mapacheee.extendedhorizons.viewdistance.service.strategy.ChunkLoadStrategy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

/*
 *   Manages fake chunks (chunks beyond server view-distance).
 *   Orchestrates the loading process by delegating to ChunkLoaderService.
 */
@Service
public class FakeChunkService {

    private static final Logger logger = LoggerFactory.getLogger(FakeChunkService.class);
    private final ConfigService configService;
    private final PlayerStateManager playerStateManager;
    private final BandwidthController bandwidthController;
    private final ChunkEventDispatcher chunkEventDispatcher;
    private final ChunkLoadStrategy chunkLoadStrategy;
    private final NMSPacketAccess nmsPacketAccess;
    private final WarmupManager warmupManager;
    private final ChunkLoaderService chunkLoaderService;

    private ScheduledTask progressiveLoadingTask;
    private static final boolean DEBUG = false;

    @Inject
    public FakeChunkService(
            ConfigService configService,
            ChunkLoadStrategy chunkLoadStrategy,
            ChunkEventDispatcher chunkEventDispatcher,
            PlayerStateManager playerStateManager,
            BandwidthController bandwidthController,
            NMSPacketAccess nmsPacketAccess,
            WarmupManager warmupManager,
            ChunkLoaderService chunkLoaderService) {
        this.configService = configService;
        this.chunkLoadStrategy = chunkLoadStrategy;
        this.chunkEventDispatcher = chunkEventDispatcher;
        this.playerStateManager = playerStateManager;
        this.bandwidthController = bandwidthController;
        this.nmsPacketAccess = nmsPacketAccess;
        this.warmupManager = warmupManager;
        this.chunkLoaderService = chunkLoaderService;
    }

    public void onPlayerJoin(Player player) {
        PlayerChunkState state = playerStateManager.getOrCreate(player);
        warmupManager.startWarmup(state);
    }

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
        return chunkLoaderService.getStats();
    }

    @OnEnable
    public void onEnable() {
        startProgressiveLoadingTask();
    }

    @OnDisable
    public void onDisable() {
        if (progressiveLoadingTask != null) {
            progressiveLoadingTask.cancel();
        }
    }

    private void startProgressiveLoadingTask() {
        this.progressiveLoadingTask = Bukkit.getAsyncScheduler()
                .runAtFixedRate(ExtendedHorizonsPlugin.getInstance(), (task) -> {
                    try {
                        chunkLoaderService.resetTickCounters();
                        playerStateManager.resetTickCounters();

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

                        ThreadPoolExecutor executor = (ThreadPoolExecutor) chunkLoaderService.getExecutor();
                        int activeTasks = executor.getActiveCount();
                        int queueSize = executor.getQueue().size();
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
                            if (state == null)
                                continue;

                            Deque<Long> queue = state.getChunkQueue();
                            if (queue == null || queue.isEmpty())
                                continue;

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

    private void processChunkQueue(Player player, Deque<Long> queue) {
        if (!player.isOnline() || queue.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PlayerChunkState state = playerStateManager.getOrCreate(uuid);
        Set<Long> sentTracker = state.getFakeChunks();

        int maxChunks = configService.get().bandwidthSaver().maxFakeChunksPerTick();
        List<Long> batch = new ArrayList<>();
        Set<Long> generatingChunks = chunkLoaderService.getGeneratingChunks();

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
            chunkLoaderService.processChunkBatch(player, batch, sentTracker);

            if (DEBUG) {
                logger.info("[EH] Processed {} chunks for {} ({} remaining in queue)",
                        batch.size(), player.getName(), queue.size());
            }
        }
    }

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

                if (isWithinServerDistance(player, chunkX, chunkZ)) {
                    if (DEBUG) {
                        logger.info("[EH] Chunk {},{} became real (server view), stopping tracking without unload",
                                chunkX, chunkZ);
                    }
                } else {
                    sendUnloadPacket(player, chunkX, chunkZ);
                    chunkEventDispatcher.fireUnloadEvent(player, chunkX, chunkZ, player.getWorld(),
                            FakeChunkUnloadEvent.UnloadReason.DISTANCE);
                }
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

        Set<Long> generatingChunks = chunkLoaderService.getGeneratingChunks();
        for (long key : chunkKeys) {
            if (playerSentChunks.contains(key)) {
                continue;
            }

            if (isWithinServerDistance(player, ChunkUtils.unpackX(key), ChunkUtils.unpackZ(key))) {
                continue;
            }

            if (!generatingChunks.contains(key)) {
                toGenerate.add(key);
            }
        }

        if (!toSend.isEmpty()) {
            toGenerate.addAll(toSend);
        }

        if (!toGenerate.isEmpty()) {
            chunkLoadStrategy.processQueue(player, state, toGenerate, generatingChunks);
            processChunkQueue(player, state.getChunkQueue());
        }

        result.complete(0);
        return result;
    }

    public void clearPlayerFakeChunks(Player player, boolean sendPackets,
            FakeChunkUnloadEvent.UnloadReason reason) {
        UUID playerId = player.getUniqueId();

        try {
            PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
            if (state == null) {
                return;
            }

            Set<Long> fakeChunks = state.getFakeChunks();

            if (sendPackets && !fakeChunks.isEmpty()) {
                Set<Long> chunksCopy = new java.util.HashSet<>(fakeChunks);
                for (Long key : chunksCopy) {
                    try {
                        int chunkX = ChunkUtils.unpackX(key);
                        int chunkZ = ChunkUtils.unpackZ(key);

                        chunkEventDispatcher.fireUnloadEvent(player, chunkX, chunkZ, player.getWorld(), reason);
                        sendUnloadPacket(player, chunkX, chunkZ);
                    } catch (Exception e) {
                        logger.warn("[EH] Error unloading fake chunk for {}: {}", player.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[EH] Error during player cleanup for {}", player.getName(), e);
        } finally {
            playerStateManager.remove(playerId);
        }
    }

    private void sendUnloadPacket(Player player, int chunkX, int chunkZ) {
        try {
            Object packet = nmsPacketAccess.createUnloadPacket(chunkX, chunkZ);
            nmsPacketAccess.sendPacket(player, packet);
        } catch (Exception e) {
        }
    }

    public boolean isFakeChunksEnabledForWorld(World world) {
        if (world == null)
            return false;

        String worldName = world.getName();
        Map<String, MainConfig.WorldConfig> worldSettings = configService.get().worldSettings();

        if (worldSettings != null && worldSettings.containsKey(worldName)) {
            return worldSettings.get(worldName).enabled();
        }

        return true;
    }

    public void shutdown() {
        chunkLoaderService.shutdown();
    }

    public void clearPlayerFakeChunks(Player player) {
        if (player == null)
            return;
        cleanupPlayer(player, true, FakeChunkUnloadEvent.UnloadReason.MANUAL);
    }

    public void clearPlayerFakeChunks(Player player, boolean sendPackets) {
        if (player == null)
            return;
        cleanupPlayer(player, sendPackets, FakeChunkUnloadEvent.UnloadReason.MANUAL);
    }

    public int getCacheSize() {
        return chunkLoaderService.getMemoryCacheSize();
    }

    public int getMemoryCacheSize() {
        return chunkLoaderService.getMemoryCacheSize();
    }

    public double getCacheHitRate() {
        return chunkLoaderService.getCacheHitRate();
    }

    public double getEstimatedMemoryUsageMB() {
        return chunkLoaderService.getEstimatedMemoryUsageMB();
    }

    public int getServerViewDistance() {
        return Bukkit.getViewDistance();
    }

    public Set<Long> getFakeChunksForPlayer(UUID playerId) {
        return playerStateManager.get(playerId)
                .map(PlayerChunkState::getFakeChunks)
                .orElse(java.util.Collections.emptySet());
    }

    public int getFakeChunkCount(UUID playerId) {
        return playerStateManager.get(playerId)
                .map(s -> s.getFakeChunks().size())
                .orElse(0);
    }

    public boolean isFakeChunk(UUID playerId, long chunkKey) {
        return playerStateManager.get(playerId)
                .map(s -> s.getFakeChunks().contains(chunkKey))
                .orElse(false);
    }

    public void refreshChunks(World world, Set<Long> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty() || world == null) {
            return;
        }

        for (Long key : chunkKeys) {
            chunkLoaderService.invalidateChunk(key);
        }

        double borderCenterX = world.getWorldBorder().getCenter().getX();
        double borderCenterZ = world.getWorldBorder().getCenter().getZ();
        double borderSize = world.getWorldBorder().getSize();

        for (UUID playerId : playerStateManager.getAllPlayerIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !player.getWorld().getUID().equals(world.getUID())) {
                continue;
            }

            PlayerChunkState state = playerStateManager.getOrCreate(playerId);
            Set<Long> fakeChunks = state.getFakeChunks();

            Set<Long> toRefresh = new HashSet<>();
            for (Long key : chunkKeys) {
                if (fakeChunks.contains(key)) {
                    fakeChunks.remove(key);
                    toRefresh.add(key);
                }
            }

            if (!toRefresh.isEmpty()) {
                sendFakeChunks(player, toRefresh, borderCenterX, borderCenterZ, borderSize);
            }
        }
    }

    private boolean isWithinServerDistance(Player player, int chunkX, int chunkZ) {
        int viewDistance = player.getViewDistance();
        if (viewDistance <= 0) {
            viewDistance = Bukkit.getViewDistance();
        }

        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        int dist = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));

        return dist <= viewDistance;
    }
}
