package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;

import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;

import com.thewinterframework.paper.listener.ListenerComponent;

@ListenerComponent
public class BlockChangeListener implements Listener {

    private final ChunkLoaderService chunkLoaderService;

    @Inject
    public BlockChangeListener(ChunkLoaderService chunkLoaderService) {
        this.chunkLoaderService = chunkLoaderService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidate(event.getBlock().getWorld().getUID(), event.getBlock().getChunk().getChunkKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock().getWorld().getUID(), event.getBlock().getChunk().getChunkKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> invalidate(block.getWorld().getUID(), block.getChunk().getChunkKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> invalidate(block.getWorld().getUID(), block.getChunk().getChunkKey()));
    }

    private void invalidate(java.util.UUID worldId, long chunkKey) {
        chunkLoaderService.invalidateChunk(worldId, chunkKey);
    }
}
