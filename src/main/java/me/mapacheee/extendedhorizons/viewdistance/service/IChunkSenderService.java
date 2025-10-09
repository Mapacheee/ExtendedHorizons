package me.mapacheee.extendedhorizons.viewdistance.service;

import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for Chunk Sender Service - Handles optimized sending of real and fake chunks to players
 * Integrates with PacketEvents for efficient chunk packet management
 */
public interface IChunkSenderService {

    CompletableFuture<Void> sendChunks(Player player, PlayerView playerView);

    CompletableFuture<Void> sendFakeChunks(Player player, PlayerView playerView);

    CompletableFuture<Void> sendChunk(Player player, ViewMap.ChunkCoordinate coordinate, boolean fake);

    CompletableFuture<Void> unloadChunk(Player player, ViewMap.ChunkCoordinate coordinate);

    void unloadAllChunks(Player player);

    boolean hasChunkBeenSent(Player player, ViewMap.ChunkCoordinate coordinate);

    void markChunkAsSent(Player player, ViewMap.ChunkCoordinate coordinate);

    void unmarkChunk(Player player, ViewMap.ChunkCoordinate coordinate);

    int getChunksPerTick();

    void setChunksPerTick(int chunksPerTick);

    void cleanup();
}
