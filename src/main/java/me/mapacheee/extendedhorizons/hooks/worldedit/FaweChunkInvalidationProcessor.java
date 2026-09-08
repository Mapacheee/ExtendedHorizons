package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Loaded only when FAWE is present; plain WorldEdit does not provide these interfaces. */
final class FaweChunkInvalidationProcessor implements IBatchProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(FaweChunkInvalidationProcessor.class);
    private final UUID worldId;
    private final BulkChunkInvalidationService bulkService;

    FaweChunkInvalidationProcessor(UUID worldId, BulkChunkInvalidationService bulkService) {
        this.worldId = worldId;
        this.bulkService = bulkService;
    }

    static void attach(EditSessionEvent event, UUID worldId, BulkChunkInvalidationService bulkService) {
        Extent extent = event.getExtent();
        Extent attached = extent.addPostProcessor(new FaweChunkInvalidationProcessor(worldId, bulkService));
        // Queued edits attach in place, avoiding FAWE's custom extent allowlist.
        if (attached != extent) {
            LOGGER.warn("FAWE is using a non-queued root extent. If its extent allowlist rejects {}, "
                + "enable FAWE queued placement or allow that class to receive immediate EH updates.",
                attached.getClass().getName());
            event.setExtent(attached);
        }
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) { return set; }

    @Override
    public Future<?> postProcessSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        bulkService.queueInvalidationWithNeighbors(worldId, chunk.getX(), chunk.getZ());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Extent construct(Extent child) {
        // FAWE's non-queued placement path uses an ordinary extent chain.
        return new WorldEditInvalidationExtent(child, worldId, bulkService);
    }

    @Override
    public ProcessorScope getScope() { return ProcessorScope.READING_BLOCKS; }
}
