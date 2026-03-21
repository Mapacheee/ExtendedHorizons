package me.mapacheee.extendedhorizons.chunk.io;

import com.google.inject.ImplementedBy;
import me.mapacheee.extendedhorizons.chunk.io.impl.RegionFileServiceImpl;
import org.bukkit.World;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;

/*
 * Service responsible for reading chunk data directly from region files (.mca)
 * bypassing the server's chunk loading system to avoid overhead.
 */
@ImplementedBy(RegionFileServiceImpl.class)
public interface RegionFileService {

    /**
     * Reads chunk data from disk asynchronously.
     * Only reads necessary sections for surface rendering if optimized.
     */
    CompletableFuture<CompoundTag> readChunkData(World world, int chunkX, int chunkZ);

    /**
     * Checks if a chunk exists on disk without fully reading it.
     */
    boolean hasChunk(World world, int chunkX, int chunkZ);
}
