package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class WorldEditInvalidationExtent extends AbstractDelegateExtent {
    private static final int FLUSH_THRESHOLD = 16_384;
    private final UUID worldId;
    private final BulkChunkInvalidationService bulkService;
    private final Set<Long> pending = new HashSet<>();

    WorldEditInvalidationExtent(Extent extent, UUID worldId, BulkChunkInvalidationService bulkService) {
        super(extent);
        this.worldId = worldId;
        this.bulkService = bulkService;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
        boolean changed = super.setBlock(location, block);
        if (changed) record(location);
        return changed;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException {
        return setBlock(BlockVector3.at(x, y, z), block);
    }

    @Override
    public boolean setBiome(BlockVector3 location, BiomeType biome) {
        boolean changed = super.setBiome(location, biome);
        if (changed) record(location);
        return changed;
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome) {
        return setBiome(BlockVector3.at(x, y, z), biome);
    }

    private synchronized void record(BlockVector3 location) {
        pending.add(ChunkKeyCodec.pack(location.x() >> 4, location.z() >> 4));
        if (pending.size() >= FLUSH_THRESHOLD) flush();
    }

    private synchronized void flush() {
        for (long key : pending) {
            bulkService.queueInvalidationWithNeighbors(worldId, ChunkKeyCodec.x(key), ChunkKeyCodec.z(key));
        }
        pending.clear();
    }

    @Override
    public Operation commit() {
        Operation delegate = super.commit();
        return new Operation() {
            private Operation remaining = delegate;
            @Override
            public Operation resume(RunContext run) throws WorldEditException {
                try {
                    if (remaining != null) remaining = remaining.resume(run);
                    if (remaining != null) return this;
                    flush();
                    return null;
                } catch (Exception exception) {
                    flush(); // Publish changes already applied by a partially failed operation.
                    throw exception;
                }
            }
            @Override
            public void cancel() {
                try {
                    if (remaining != null) remaining.cancel();
                } finally {
                    flush();
                }
            }
        };
    }
}
