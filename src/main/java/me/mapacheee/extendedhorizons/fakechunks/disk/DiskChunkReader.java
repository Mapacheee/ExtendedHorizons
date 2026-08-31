package me.mapacheee.extendedhorizons.fakechunks.disk;

import io.netty.buffer.ByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * High-level orchestrator that reads a chunk directly from disk and produces
 * a ready-to-send network packet. This is the primary entry point for the
 * direct disk reader pipeline.
 *
 * Pipeline flow
 *  1. RegionFileReader  → raw compressed bytes from .mca
 *  2. (decompression)   → raw NBT bytes
 *  3. DiskChunkSerializer → ByteBuf (network packet)
 *     Uses Minecraft's own PalettedContainer codec to parse block_states/biomes
 *     from NBT, creating real LevelChunkSection objects, then serializing
 *     them to wire format via PalettedContainer.write().
 *
 * This entire pipeline runs on a worker thread (no region thread involvement),
 * making it safe to call from any thread without blocking Folia's region ticks.
 */
public final class DiskChunkReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkReader.class);

    /**
     * The classloader that loaded this class (the plugin's classloader).
     * Used to temporarily swap the thread's context classloader when running
     * on Netty threads, which don't have access to NMS classes by default.
     */
    private static final ClassLoader PLUGIN_CLASSLOADER = DiskChunkReader.class.getClassLoader();

    private DiskChunkReader() {}

    /**
     * Reads a chunk from disk and serializes it to a ready-to-send network packet.
     *
     * @param world     the Bukkit world (used to resolve the world folder and environment)
     * @param chunkX    the chunk X coordinate
     * @param chunkZ    the chunk Z coordinate
     * @return a ByteBuf containing the serialized packet, or null if the chunk cannot be read.
     *         The caller is responsible for releasing the returned ByteBuf.
     */
    public static ByteBuf readAndSerialize(World world, int chunkX, int chunkZ) {
        if (world == null) {
            LOGGER.warn("Cannot read chunk [{}, {}]: world is null", chunkX, chunkZ);
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
            return readAndSerializeInternal(world, chunkX, chunkZ, worldFolder);
        } finally {
            if (swapped) {
                currentThread.setContextClassLoader(originalClassLoader);
            }
        }
    }

    private static ByteBuf readAndSerializeInternal(World world, int chunkX, int chunkZ, File worldFolder) {
        byte[] nbtBytes = RegionFileReader.readChunkBytes(worldFolder, chunkX, chunkZ);
        if (nbtBytes == null) {
            LOGGER.debug("Chunk [{}, {}] not found on disk for world '{}'",
                chunkX, chunkZ, world.getName());
            return null;
        }

        ServerLevel level = ((CraftWorld) world).getHandle();
        boolean hasSky = level.dimensionType().hasSkyLight();

        ByteBuf packet = DiskChunkSerializer.serialize(nbtBytes, level, chunkX, chunkZ, hasSky);
        if (packet == null) {
            LOGGER.warn("Failed to serialize packet for chunk [{}, {}] in world '{}'",
                chunkX, chunkZ, world.getName());
            return null;
        }

        return packet;
    }
}
