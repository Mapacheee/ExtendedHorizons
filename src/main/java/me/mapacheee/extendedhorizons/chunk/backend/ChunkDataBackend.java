package me.mapacheee.extendedhorizons.chunk.backend;

import io.netty.buffer.ByteBuf;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import org.bukkit.World;

public interface ChunkDataBackend {

  @FunctionalInterface
  interface ChunkScheduler {
    boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable);
  }

  CompletableFuture<ChunkBuildResult> loadOrBuildPacket(
      World world, int chunkX, int chunkZ, boolean generateMissingChunks, ChunkScheduler scheduler);

  default void invalidate(UUID worldId, long chunkKey) {}

  default void clearCaches() {}

  record ChunkBuildResult(ByteBuf serializedPayload, ChunkDataSource source) {}

  enum ChunkDataSource {
    LOADED,
    DISK,
    GENERATED
  }
}
