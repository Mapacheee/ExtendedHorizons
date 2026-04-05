package me.mapacheee.extendedhorizons.chunk.backend;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

public class PaperChunkStorageProbe {

  public CompletableFuture<Boolean> hasChunkOnDisk(World world, int chunkX, int chunkZ) {
    return readChunkTag(world, chunkX, chunkZ).thenApply(tag -> tag != null && tag.isPresent());
  }

  public CompletableFuture<Optional<CompoundTag>> readChunkTag(World world, int chunkX, int chunkZ) {
    if (world == null) return CompletableFuture.completedFuture(Optional.empty());
    try {
      ServerLevel level = ((CraftWorld) world).getHandle();
      ChunkPos pos = new ChunkPos(chunkX, chunkZ);
      return level
          .chunkSource
          .chunkMap
          .read(pos)
          .thenApply(tag -> tag == null ? Optional.<CompoundTag>empty() : tag)
          .exceptionally(e -> Optional.<CompoundTag>empty());
    } catch (Throwable ignored) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
  }
}
