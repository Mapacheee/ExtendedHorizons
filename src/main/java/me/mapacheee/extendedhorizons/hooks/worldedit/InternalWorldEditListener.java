package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

public final class InternalWorldEditListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalWorldEditListener.class);
    private final BulkChunkInvalidationService bulkService;
    private final boolean fawe;

    public InternalWorldEditListener(BulkChunkInvalidationService bulkService) {
        this.bulkService = bulkService;
        boolean available;
        try {
            Class.forName("com.fastasyncworldedit.core.queue.IBatchProcessor", false, getClass().getClassLoader());
            available = true;
        } catch (ClassNotFoundException ignored) {
            available = false;
        }
        this.fawe = available;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
        LOGGER.info("Registered {} invalidation hook.", this.fawe ? "FAWE chunk post-processor" : "WorldEdit extent");
    }

    public void unregister() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        // In FAWE the processor is attached to the shared queue, including bypass/fast operations.
        // Attach after FAWE has finished constructing its own processor chain.
        EditSession.Stage stage = this.fawe ? EditSession.Stage.BEFORE_HISTORY : EditSession.Stage.BEFORE_CHANGE;
        if (event.getStage() != stage || event.getWorld() == null || event.getExtent() == null) return;
        UUID worldId;
        try {
            worldId = BukkitAdapter.adapt(event.getWorld()).getUID();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not resolve WorldEdit world for chunk invalidation.", exception);
            return;
        }
        if (this.fawe) {
            FaweChunkInvalidationProcessor.attach(event, worldId, this.bulkService);
        } else if (!(event.getExtent() instanceof WorldEditInvalidationExtent)) {
            event.setExtent(new WorldEditInvalidationExtent(event.getExtent(), worldId, this.bulkService));
        }
    }
}
