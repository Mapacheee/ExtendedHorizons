package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class InternalWorldEditListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalWorldEditListener.class);
    private final BulkChunkInvalidationService bulkService;

    public InternalWorldEditListener(BulkChunkInvalidationService bulkService) {
        this.bulkService = bulkService;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
        LOGGER.info("Registered WorldEdit/FAWE invalidation hook.");
    }

    public void unregister() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        if (event.getWorld() == null) {
            return;
        }
        if (event.getExtent() == null || event.getExtent() instanceof InvalidationExtent) {
            return;
        }

        UUID worldId;
        try {
            worldId = BukkitAdapter.adapt(event.getWorld()).getUID();
        } catch (Throwable t) {
            LOGGER.debug("Could not resolve WorldEdit world ID.", t);
            return;
        }

        event.setExtent(new InvalidationExtent(event.getExtent(), worldId, this.bulkService));
    }

    /**
     * Extent that batches chunk invalidation keys locally instead of calling
     * {@code queueInvalidation()} per-block. The accumulated chunk keys are
     * flushed in bulk when {@link #commit()} is called by WE/FAWE at the end
     * of the operation, or when the local buffer exceeds {@link #FLUSH_THRESHOLD}
     * unique chunks as a safety valve for extremely large operations.
     * <p>
     * Because WE/FAWE guarantees that an extent is used by a single thread
     * during an operation, the local {@code HashSet} requires no synchronization.
     */
    private static class InvalidationExtent extends AbstractDelegateExtent {

        /**
         * Safety flush threshold — if an operation touches more than 16 384
         * unique chunks we flush early to cap memory usage (~130 KB for longs).
         */
        private static final int FLUSH_THRESHOLD = 16_384;

        private final UUID worldId;
        private final BulkChunkInvalidationService bulkService;
        private final Set<Long> pendingChunks = new HashSet<>();

        InvalidationExtent(Extent extent, UUID worldId, BulkChunkInvalidationService bulkService) {
            super(extent);
            this.worldId = worldId;
            this.bulkService = bulkService;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
            boolean changed = super.setBlock(location, block);
            if (changed) {
                this.pendingChunks.add(ChunkKeyCodec.pack(location.x() >> 4, location.z() >> 4));
                if (this.pendingChunks.size() >= FLUSH_THRESHOLD) {
                    this.flush();
                }
            }
            return changed;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException {
            boolean changed = super.setBlock(x, y, z, block);
            if (changed) {
                this.pendingChunks.add(ChunkKeyCodec.pack(x >> 4, z >> 4));
                if (this.pendingChunks.size() >= FLUSH_THRESHOLD) {
                    this.flush();
                }
            }
            return changed;
        }

        @Override
        public com.sk89q.worldedit.function.operation.Operation commit() {
            this.flush();
            return super.commit();
        }

        private void flush() {
            if (this.pendingChunks.isEmpty()) {
                return;
            }
            this.bulkService.queueInvalidationBatch(this.worldId, this.pendingChunks);
            this.pendingChunks.clear();
        }
    }
}

