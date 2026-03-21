package me.mapacheee.extendedhorizons.chunk.pipeline;

import com.google.inject.ImplementedBy;
import me.mapacheee.extendedhorizons.chunk.pipeline.impl.ChunkPipelineServiceImpl;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.World;
import java.util.concurrent.CompletableFuture;

/*
 * Service responsible for converting raw NBT data into network packets.
 * Handles optimization like surface-only filtering.
 */
@ImplementedBy(ChunkPipelineServiceImpl.class)
public interface ChunkPipelineService {

    /**
     * Converts raw chunk NBT data into a packet ready to be sent to the client.
     * Applies surface-only filtering if enabled.
     */
    CompletableFuture<ChunkPackets> createPacket(World world, int chunkX, int chunkZ, CompoundTag chunkData);
}
