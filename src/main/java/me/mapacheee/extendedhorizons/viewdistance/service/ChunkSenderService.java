package me.mapacheee.extendedhorizons.viewdistance.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap;
import me.mapacheee.extendedhorizons.viewdistance.entity.ChunkRegion;
import me.mapacheee.extendedhorizons.optimization.service.CacheService;
import me.mapacheee.extendedhorizons.integration.service.IPacketEventsService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/* Chunk Sender Service - Handles optimized sending of real and fake chunks to players
 * Integrates with PacketEvents for efficient chunk packet management
 */

@Service
public class ChunkSenderService implements IChunkSenderService {

    private final Logger logger;
    private final ConfigService configService;
    private final PlayerViewService playerViewService;
    private final CacheService cacheService;
    private final IPacketEventsService packetEventsService;

    private final ExecutorService chunkProcessingExecutor;
    private final ConcurrentHashMap<Player, Set<ViewMap.ChunkCoordinate>> sentChunks;
    private final AtomicInteger globalChunksPerTick;

    @Inject
    public ChunkSenderService(
            Logger logger,
            ConfigService configService,
            PlayerViewService playerViewService,
            CacheService cacheService,
            IPacketEventsService packetEventsService
    ) {
        this.logger = logger;
        this.configService = configService;
        this.playerViewService = playerViewService;
        this.cacheService = cacheService;
        this.packetEventsService = packetEventsService;

        int threadCount = Runtime.getRuntime().availableProcessors();

        this.chunkProcessingExecutor = Executors.newFixedThreadPool(threadCount);
        this.sentChunks = new ConcurrentHashMap<>();
        this.globalChunksPerTick = new AtomicInteger(0);
    }

    public void updatePlayerChunks(Player player, PlayerView playerView) {
        if (!configService.isWorldEnabled(player.getWorld().getName())) return;

        CompletableFuture<PlayerViewService.ViewMapUpdate> updateFuture =
            playerViewService.calculateViewUpdate(player, playerView);

        updateFuture.thenAcceptAsync(update -> processChunkUpdate(player, playerView, update), chunkProcessingExecutor);
    }

    private void processChunkUpdate(Player player, PlayerView playerView, PlayerViewService.ViewMapUpdate update) {
        Set<ViewMap.ChunkCoordinate> currentSent = sentChunks.getOrDefault(player, ConcurrentHashMap.newKeySet());

        int chunksPerTick = playerViewService.calculateOptimalChunksPerTick(playerView);
        int maxGenerationPerTick = configService.getMaxChunksPerTick();

        if (globalChunksPerTick.get() >= maxGenerationPerTick) {
            playerView.setWaitingForChunks(true);
            return;
        }

        unloadRemovedChunks(player, update.removedChunks(), currentSent);

        sendNewChunks(player, playerView, update.newChunks(), update.fakeChunks(), chunksPerTick);

        sentChunks.put(player, update.newChunks());
    }

    private void unloadRemovedChunks(Player player, Set<ViewMap.ChunkCoordinate> removedChunks,
                                   Set<ViewMap.ChunkCoordinate> currentSent) {
        for (ViewMap.ChunkCoordinate coord : removedChunks) {
            if (currentSent.contains(coord)) {
                unloadChunk(player, coord.x(), coord.z());
                currentSent.remove(coord);
            }
        }
    }

    private void sendNewChunks(Player player, PlayerView playerView, Set<ViewMap.ChunkCoordinate> newChunks,
                             Set<ViewMap.ChunkCoordinate> fakeChunks, int chunksPerTick) {
        int sentThisTick = 0;

        for (ViewMap.ChunkCoordinate coord : newChunks) {
            if (sentThisTick >= chunksPerTick) break;
            if (globalChunksPerTick.incrementAndGet() > configService.getMaxChunksPerTick()) {
                break;
            }

            if (fakeChunks.contains(coord)) {
                sendFakeChunk(player, playerView, coord);
            } else {
                sendRealChunk(player, playerView, coord);
            }

            sentThisTick++;
        }
    }

    private void sendRealChunk(Player player, PlayerView playerView, ViewMap.ChunkCoordinate coord) {
        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("ExtendedHorizons"), () -> {
            try {
                Chunk chunk = player.getWorld().getChunkAt(coord.x(), coord.z());

                if (chunk.isLoaded()) {
                    sendChunkPacket(player, chunk);
                    playerView.incrementChunksSent();

                    long chunkSize = estimateChunkSize(chunk);
                    playerView.addNetworkBytesUsed(chunkSize);
                } else {
                    chunk.load(true);
                    sendChunkPacket(player, chunk);
                    playerView.incrementChunksSent();
                }

            } catch (Exception e) {
                logger.error("Failed to send real chunk at {}, {} to player {}",
                           coord.x(), coord.z(), player.getName(), e);
            }
        });
    }

    private void sendFakeChunk(Player player, PlayerView playerView, ViewMap.ChunkCoordinate coord) {
        chunkProcessingExecutor.execute(() -> {
            try {
                ChunkRegion region = cacheService.getOrCreateRegion(coord.x() >> 5, coord.z() >> 5, player.getWorld());
                ChunkRegion.FakeChunkData fakeData = region.getFakeChunk(coord.x(), coord.z());

                byte[] chunkPacketData = generateFakeChunkPacket(fakeData);

                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("ExtendedHorizons"), () -> {
                    sendFakeChunkPacket(player, coord.x(), coord.z(), chunkPacketData);
                    playerView.incrementFakeChunksSent();
                    playerView.addNetworkBytesUsed(chunkPacketData.length);
                });

            } catch (Exception e) {
                logger.error("Failed to send fake chunk at {}, {} to player {}",
                           coord.x(), coord.z(), player.getName(), e);
            }
        });
    }

    private byte[] generateFakeChunkPacket(ChunkRegion.FakeChunkData fakeData) {
        boolean simulateTerrain = configService.areFakeChunksEnabled();

        if (!simulateTerrain) {
            return generateEmptyChunkData();
        }

        return generateTerrainChunkData(fakeData);
    }

    private byte[] generateEmptyChunkData() {
        return new byte[1024];
    }

    private byte[] generateTerrainChunkData(ChunkRegion.FakeChunkData fakeData) {
        byte[] chunkData = new byte[8192];

        int surfaceHeight = fakeData.surfaceHeight();
        byte blockId = getBlockIdFromMaterial(fakeData.surfaceMaterial());

        for (int y = 0; y <= surfaceHeight && y < 384; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int index = calculateBlockIndex(x, y, z);
                    if (index < chunkData.length) {
                        if (y == surfaceHeight) {
                            chunkData[index] = blockId;
                        } else {
                            chunkData[index] = y < 0 ? (byte) 7 : (byte) 1;
                        }
                    }
                }
            }
        }

        return chunkData;
    }

    private byte getBlockIdFromMaterial(org.bukkit.Material material) {
        return switch (material) {
            case GRASS_BLOCK -> (byte) 2;
            case STONE -> (byte) 1;
            case SAND -> (byte) 12;
            case WATER -> (byte) 9;
            case SNOW_BLOCK -> (byte) 80;
            default -> (byte) 2;
        };
    }

    private int calculateBlockIndex(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    private void sendChunkPacket(Player player, Chunk chunk) {
        var chunkData = packetEventsService.createChunkData(chunk);
        packetEventsService.sendChunkPacket(player, chunkData);
    }

    private void sendFakeChunkPacket(Player player, int chunkX, int chunkZ, byte[] chunkData) {
        packetEventsService.sendFakeChunkPacket(player, chunkX, chunkZ);
    }

    private void unloadChunk(Player player, int chunkX, int chunkZ) {
        WrapperPlayServerUnloadChunk unloadPacket = new WrapperPlayServerUnloadChunk(chunkX, chunkZ);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, unloadPacket);
    }

    public void unloadAllChunks(Player player) {
        Set<ViewMap.ChunkCoordinate> playerChunks = sentChunks.remove(player);
        if (playerChunks != null) {
            for (ViewMap.ChunkCoordinate coord : playerChunks) {
                unloadChunk(player, coord.x(), coord.z());
            }
        }
    }

    private long estimateChunkSize(Chunk chunk) {
        return 2048L + (chunk.getTileEntities().length * 64L);
    }

    public void resetGlobalChunkCounter() {
        globalChunksPerTick.set(0);
    }

    public int getCurrentGlobalChunksPerTick() {
        return globalChunksPerTick.get();
    }

    @Override
    public CompletableFuture<Void> sendChunks(Player player, PlayerView playerView) {
        return CompletableFuture.runAsync(() -> updatePlayerChunks(player, playerView), chunkProcessingExecutor);
    }

    @Override
    public CompletableFuture<Void> sendFakeChunks(Player player, PlayerView playerView) {
        return CompletableFuture.runAsync(() -> {
            if (playerView.areFakeChunksEnabled()) {
                int distance = playerView.getCurrentDistance();
                int fakeStartDistance = configService.getFakeChunksStartDistance();

                for (int x = -distance; x <= distance; x++) {
                    for (int z = -distance; z <= distance; z++) {
                        double chunkDistance = Math.sqrt(x * x + z * z);
                        if (chunkDistance > fakeStartDistance && chunkDistance <= distance) {
                            ViewMap.ChunkCoordinate coord = new ViewMap.ChunkCoordinate(
                                (int) player.getLocation().getX() / 16 + x,
                                (int) player.getLocation().getZ() / 16 + z
                            );
                            sendFakeChunk(player, playerView, coord);
                        }
                    }
                }
            }
        }, chunkProcessingExecutor);
    }

    @Override
    public CompletableFuture<Void> sendChunk(Player player, ViewMap.ChunkCoordinate coordinate, boolean fake) {
        return CompletableFuture.runAsync(() -> {
            PlayerView playerView = new PlayerView(player); // Temporary view
            if (fake) {
                sendFakeChunk(player, playerView, coordinate);
            } else {
                sendRealChunk(player, playerView, coordinate);
            }
        }, chunkProcessingExecutor);
    }

    @Override
    public CompletableFuture<Void> unloadChunk(Player player, ViewMap.ChunkCoordinate coordinate) {
        return CompletableFuture.runAsync(() -> {
            unloadChunk(player, coordinate.x(), coordinate.z());
        });
    }

    @Override
    public boolean hasChunkBeenSent(Player player, ViewMap.ChunkCoordinate coordinate) {
        Set<ViewMap.ChunkCoordinate> playerChunks = sentChunks.get(player);
        return playerChunks != null && playerChunks.contains(coordinate);
    }

    @Override
    public void markChunkAsSent(Player player, ViewMap.ChunkCoordinate coordinate) {
        sentChunks.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(coordinate);
    }

    @Override
    public void unmarkChunk(Player player, ViewMap.ChunkCoordinate coordinate) {
        Set<ViewMap.ChunkCoordinate> playerChunks = sentChunks.get(player);
        if (playerChunks != null) {
            playerChunks.remove(coordinate);
        }
    }

    @Override
    public int getChunksPerTick() {
        return globalChunksPerTick.get();
    }

    @Override
    public void setChunksPerTick(int chunksPerTick) {
        globalChunksPerTick.set(chunksPerTick);
    }

    @Override
    public void cleanup() {
        chunkProcessingExecutor.shutdown();
        sentChunks.clear();
    }
}
