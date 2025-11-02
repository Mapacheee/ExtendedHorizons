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
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 *   Manages fake chunks (chunks beyond server view-distance)
 *   Generates chunks proactively and sends them from cache
 *   Uses 100% async processing to avoid blocking the main thread
 */
@Service
public class FakeChunkService {

    private Logger logger;
    private final ConfigService configService;
    private final PacketChunkCacheService columnCache;
    private final Plugin plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    private final Map<UUID, Set<Long>> playerFakeChunks = new ConcurrentHashMap<>();
    private final Set<Long> generatingChunks = ConcurrentHashMap.newKeySet();

    private static final boolean DEBUG = false;

    @Inject
    public FakeChunkService(ConfigService configService, PacketChunkCacheService columnCache) {
        this.configService = configService;
        this.columnCache = columnCache;
    }

    /**
     * Gets the server's actual view distance from server.properties
     */
    public int getServerViewDistance() {
        return Bukkit.getViewDistance();
    }

    /**
     * Sends fake chunks to a player
     */
    public CompletableFuture<Integer> sendFakeChunks(Player player, Set<Long> chunkKeys) {
        if (!configService.get().performance().fakeChunks().enabled() || chunkKeys.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<Integer> result = new CompletableFuture<>();
        UUID playerId = player.getUniqueId();
        Set<Long> playerSentChunks = playerFakeChunks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        Set<Long> toRemove = new HashSet<>(playerSentChunks);
        toRemove.removeAll(chunkKeys);
        playerSentChunks.removeAll(toRemove);

        List<Long> toSend = new ArrayList<>();
        List<Long> toGenerate = new ArrayList<>();

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
            generateAndSendChunks(player, toGenerate, playerSentChunks);
        }

        result.complete(sent);
        return result;
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
                        logger.warn("[EH] Column null or invalid for {},{}, will regenerate", chunkX, chunkZ);
                    }
                    List<Long> toRegenerate = new ArrayList<>();
                    toRegenerate.add(key);
                    generateAndSendChunks(player, toRegenerate, sentTracker);
                }
            }
        }

        if (DEBUG && sent > 0) {
            logger.info("[EH] Sent " + sent + " cached fake chunks to " + player.getName());
        }

        return sent;
    }

    /**
     * Generates chunks and sends them to the player
     */
    private void generateAndSendChunks(Player player, List<Long> keys, Set<Long> sentTracker) {
        World world = player.getWorld();

        if (DEBUG) {
            logger.info("[EH] Generating {} fake chunks for {}", keys.size(), player.getName());
        }

        for (long key : keys) {
            if (!player.isOnline()) break;

            generatingChunks.add(key);

            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);

            world.getChunkAtAsync(chunkX, chunkZ, true).thenAcceptAsync(chunk -> {
                if (!player.isOnline()) {
                    generatingChunks.remove(key);
                    return;
                }

                try {
                    org.bukkit.craftbukkit.CraftChunk craftChunk = (org.bukkit.craftbukkit.CraftChunk) chunk;
                    net.minecraft.world.level.chunk.LevelChunk nmsChunk =
                            (net.minecraft.world.level.chunk.LevelChunk) craftChunk.getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);

                    net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                    nmsChunk,
                                    nmsChunk.getLevel().getLightEngine(),
                                    null,
                                    null
                            );

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            generatingChunks.remove(key);
                            return;
                        }

                        try {
                            org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = (org.bukkit.craftbukkit.entity.CraftPlayer) player;
                            net.minecraft.server.level.ServerPlayer nmsPlayer = craftPlayer.getHandle();

                            nmsPlayer.connection.send(packet);

                            sentTracker.add(key);
                            generatingChunks.remove(key);

                            if (DEBUG) {
                                logger.info("[EH] Sent fake chunk " + chunkX + "," + chunkZ + " to " + player.getName());
                            }
                        } catch (Exception e) {
                            generatingChunks.remove(key);
                            if (DEBUG) {
                                logger.warn("[EH] Failed to send chunk packet: " + e.getMessage());
                            }
                        }
                    });

                } catch (Exception e) {
                    generatingChunks.remove(key);
                    if (DEBUG) {
                        logger.warn("[EH] Failed to create chunk packet " + chunkX + "," + chunkZ + ": " + e.getMessage());
                    }
                }
            }, chunkProcessor)
            .exceptionally(throwable -> {
                generatingChunks.remove(key);
                logger.warn("[EH] Failed to generate chunk " + chunkX + "," + chunkZ + ": " + throwable.getMessage());
                return null;
            });
        }
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
        playerFakeChunks.remove(player.getUniqueId());
    }

    /**
     * Shutdown the async executor
     */
    public void shutdown() {
        chunkProcessor.shutdown();
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

    private final ExecutorService chunkProcessor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        r -> {
            Thread t = new Thread(r, "EH-ChunkProcessor");
            t.setDaemon(true);
            return t;
        }
    );
}
