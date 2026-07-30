package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class WorldEditInvalidationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldEditInvalidationListener.class);

    private final BulkChunkInvalidationService bulkChunkInvalidationService;
    private final Container<EhConfig> configContainer;
    private InternalWorldEditListener internalListener;

    @Inject
    public WorldEditInvalidationListener(
        BulkChunkInvalidationService bulkChunkInvalidationService,
        Container<EhConfig> configContainer
    ) {
        this.bulkChunkInvalidationService = bulkChunkInvalidationService;
        this.configContainer = configContainer;
    }

    @OnEnable
    public void onEnable() {
        if (!this.configContainer.get().worldEditEnabled()) {
            LOGGER.info("WorldEdit hook is disabled via config.");
            return;
        }
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
