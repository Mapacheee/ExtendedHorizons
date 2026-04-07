package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

public interface ChunkBackend {

    @FunctionalInterface
    interface ChunkScheduler {
        boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable);
    }

    CompletableFuture<ByteBuf> buildChunkPayload(World world, int chunkX, int chunkZ, boolean generateMissingChunks, ChunkScheduler scheduler);
}
