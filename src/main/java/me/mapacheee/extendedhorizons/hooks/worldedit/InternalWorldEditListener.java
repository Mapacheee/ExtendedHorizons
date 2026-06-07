package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.SessionManager;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

    public void invalidatePlayerSelection(Player player) {
        if (player == null) {
            return;
        }

        ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        this.scheduleSelectionInvalidation(plugin, player, 2L);
        this.scheduleSelectionInvalidation(plugin, player, 10L);
        this.scheduleSelectionInvalidation(plugin, player, 30L);
    }

    private void scheduleSelectionInvalidation(ExtendedHorizonsPlugin plugin, Player player, long delayTicks) {
        FoliaTaskUtil.runGlobalDelayed(plugin, () -> this.doInvalidateSelection(player), Math.max(1L, delayTicks));
    }

    private void doInvalidateSelection(Player player) {
        if (!player.isOnline()) {
            return;
        }

        try {
            SessionManager sessionManager = WorldEdit.getInstance().getSessionManager();
            com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
            LocalSession localSession = sessionManager.get(wePlayer);

            if (localSession == null || localSession.getSelectionWorld() == null) {
                return;
            }

            Region selection = localSession.getSelection(localSession.getSelectionWorld());
            if (selection == null) {
                return;
            }

            World bukkitWorld = BukkitAdapter.adapt(localSession.getSelectionWorld());
            if (bukkitWorld == null) {
                return;
            }

            UUID worldId = bukkitWorld.getUID();
            BlockVector3 min = selection.getMinimumPoint();
            BlockVector3 max = selection.getMaximumPoint();

            int minChunkX = min.x() >> 4;
            int maxChunkX = max.x() >> 4;
            int minChunkZ = min.z() >> 4;
            int maxChunkZ = max.z() >> 4;

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    long chunkKey = ChunkKeyCodec.pack(cx, cz);
                    this.bulkService.queueInvalidation(worldId, chunkKey);
                }
            }

            LOGGER.debug("Invalidated {} chunks from WorldEdit selection for player {}.",
                (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1), player.getName());

        } catch (Throwable t) {
            LOGGER.debug("Could not resolve WorldEdit selection for player {}.", player.getName(), t);
        }
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

