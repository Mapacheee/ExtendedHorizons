package me.mapacheee.extendedhorizons.fakechunks.disk;

import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayProcessor;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

/**
 * High-level orchestrator that reads a chunk directly from disk and produces
 * a ready-to-send network packet. This is the primary entry point for the
 * direct disk reader pipeline.
 * <p>
 * Pipeline flow
 * 1. RegionFileReader  → raw compressed bytes from .mca
 * 2. (decompression)   → raw NBT bytes
 * 3. DiskChunkSerializer → ByteBuf (network packet)
 * Uses Minecraft's own PalettedContainer codec to parse block_states/biomes
 * from NBT, creating real LevelChunkSection objects, then serializing
 * them to wire format via PalettedContainer.write().
 * <p>
 * This entire pipeline runs on a worker thread (no region thread involvement),
 * making it safe to call from any thread without blocking Folia's region ticks.
 */
public final class DiskChunkReader {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkReader.class);
  private static final int UNAVAILABLE_CHUNK_CACHE_SIZE = 4096;
  private static final Cache<ChunkKey, Boolean> UNAVAILABLE_CHUNKS = Caffeine.newBuilder()
    .maximumSize(UNAVAILABLE_CHUNK_CACHE_SIZE)
    .expireAfterWrite(Duration.ofSeconds(30))
    .build();

  /**
   * The classloader that loaded this class (the plugin's classloader).
   * Used to temporarily swap the thread's context classloader when running
   * on Netty threads, which don't have access to NMS classes by default.
   */
  private static final ClassLoader PLUGIN_CLASSLOADER = DiskChunkReader.class.getClassLoader();

  private DiskChunkReader() {
  }

  /**
   * Reads a chunk from disk and serializes it to a ready-to-send network packet.
   *
   * @param world  the Bukkit world (used to resolve the world folder and environment)
   * @param chunkX the chunk X coordinate
   * @param chunkZ the chunk Z coordinate
   * @return a ByteBuf containing the serialized packet, or null if the chunk cannot be read.
   * The caller is responsible for releasing the returned ByteBuf.
   */
  public static ByteBuf readAndSerialize(World world, int chunkX, int chunkZ) {
    return readAndSerialize(world, chunkX, chunkZ, null);
  }

  public static ByteBuf readAndSerialize(World world, int chunkX, int chunkZ, AntiXrayProcessor antiXray) {
    if (world == null) {
      LOGGER.warn("Cannot read chunk [{}, {}]: world is null", chunkX, chunkZ);
      return null;
    }
    if (!shouldAttemptDirectRead(world, chunkX, chunkZ)) {
      return null;
    }

    File worldFolder = world.getWorldFolder();
    if (!worldFolder.exists()) {
      LOGGER.warn("Cannot read chunk [{}, {}]: world folder does not exist for world '{}'",
        chunkX, chunkZ, world.getName());
      return null;
    }

    Thread currentThread = Thread.currentThread();
    ClassLoader originalClassLoader = currentThread.getContextClassLoader();
    boolean swapped = false;
    if (originalClassLoader != PLUGIN_CLASSLOADER) {
      currentThread.setContextClassLoader(PLUGIN_CLASSLOADER);
      swapped = true;
    }

    try {
      return readAndSerializeInternal(world, chunkX, chunkZ, worldFolder, antiXray);
    } finally {
      if (swapped) {
        currentThread.setContextClassLoader(originalClassLoader);
      }
    }
  }

  private static ByteBuf readAndSerializeInternal(World world, int chunkX, int chunkZ, File worldFolder,
    AntiXrayProcessor antiXray) {
    byte[] nbtBytes = RegionFileReader.readChunkBytes(worldFolder, chunkX, chunkZ);
    if (nbtBytes == null) {
      LOGGER.debug("Chunk [{}, {}] not found on disk for world '{}'",
        chunkX, chunkZ, world.getName());
      return null;
    }

    ServerLevel level = ((CraftWorld) world).getHandle();
    boolean hasSky = level.dimensionType().hasSkyLight();

    ByteBuf packet = DiskChunkSerializer.serialize(nbtBytes, level, chunkX, chunkZ, hasSky, antiXray);
    if (packet == null) {
      if (markChunkUnavailable(world.getUID(), chunkX, chunkZ)) {
        LOGGER.debug(
          "Direct disk serialization unavailable for chunk [{}, {}] in world '{}'; using Paper fallback",
          chunkX,
          chunkZ,
          world.getName()
        );
      }
      return null;
    }

    return packet;
  }

  public static boolean shouldAttemptDirectRead(World world, int chunkX, int chunkZ) {
    return world != null && shouldAttemptDirectRead(world.getUID(), chunkX, chunkZ);
  }

  static boolean shouldAttemptDirectRead(UUID worldId, int chunkX, int chunkZ) {
    return UNAVAILABLE_CHUNKS.getIfPresent(new ChunkKey(worldId, chunkX, chunkZ)) == null;
  }

  static boolean markChunkUnavailable(UUID worldId, int chunkX, int chunkZ) {
    return UNAVAILABLE_CHUNKS.asMap().putIfAbsent(new ChunkKey(worldId, chunkX, chunkZ), Boolean.TRUE) == null;
  }

  static void clearUnavailableChunks() {
    UNAVAILABLE_CHUNKS.invalidateAll();
  }

  private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
  }
}
