package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.hooks.worldedit.BulkChunkInvalidationService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.UUID;

@ListenerComponent
public final class ChunkInvalidationListener implements Listener {

    private static final int CHUNK_SHIFT = 4;

    private final BulkChunkInvalidationService bulkService;

    @Inject
    public ChunkInvalidationListener(BulkChunkInvalidationService bulkService) {
        this.bulkService = bulkService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        this.invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        this.invalidate(placed);
    }

    private void invalidate(Block block) {
        int chunkX = block.getX() >> CHUNK_SHIFT;
        int chunkZ = block.getZ() >> CHUNK_SHIFT;
        long chunkKey = ChunkKeyCodec.pack(chunkX, chunkZ);
        UUID worldId = block.getWorld().getUID();
        this.bulkService.queueInvalidation(worldId, chunkKey);
    }
}
