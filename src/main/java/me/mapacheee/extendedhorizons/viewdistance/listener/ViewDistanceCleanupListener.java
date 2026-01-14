package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ListenerComponent
public class ViewDistanceCleanupListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger(ViewDistanceCleanupListener.class);
    private final ChunkLoaderService chunkLoaderService;

    @Inject
    public ViewDistanceCleanupListener(ChunkLoaderService chunkLoaderService) {
        this.chunkLoaderService = chunkLoaderService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        int cleared = chunkLoaderService.getMemoryCacheSize();
        chunkLoaderService.clearMemoryCache();

        if (cleared > 0) {
            logger.info("[EH] Cleared {} chunks from memory cache due to world unload: {}",
                    cleared, event.getWorld().getName());
        }
    }
}
