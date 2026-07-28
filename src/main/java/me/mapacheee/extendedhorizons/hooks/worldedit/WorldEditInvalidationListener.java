package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class WorldEditInvalidationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldEditInvalidationListener.class);

    private final BulkChunkInvalidationService bulkChunkInvalidationService;
    private InternalWorldEditListener internalListener;

    @Inject
    public WorldEditInvalidationListener(BulkChunkInvalidationService bulkChunkInvalidationService) {
        this.bulkChunkInvalidationService = bulkChunkInvalidationService;
    }

    @OnEnable
    public void onEnable() {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
            this.internalListener = new InternalWorldEditListener(this.bulkChunkInvalidationService);
            this.internalListener.register();
        } catch (ClassNotFoundException e) {
            LOGGER.info("WorldEdit is not present, ignoring event registration.");
        }
    }

    @OnDisable
    public void onDisable() {
        if (this.internalListener != null) {
            this.internalListener.unregister();
            this.internalListener = null;
        }
    }
}
