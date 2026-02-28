package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for block changes and invalidates the fake chunk cache
 * so that players receive updated chunk data instead of stale/old blocks.
 *
 * Uses a per-chunk cooldown (500ms) to avoid flooding invalidations
 * during rapid block changes (e.g., WorldEdit operations).
 *
 * Can be disabled via config: fake-chunks.block-change-invalidation
 */
@ListenerComponent
public class BlockChangeListener implements Listener {

    private final ChunkLoaderService chunkLoaderService;
    private final FakeChunkService fakeChunkService;
    private final ConfigService configService;

    /**
     * Cooldown map: chunkKey -> last invalidation timestamp.
     * Prevents excessive invalidation during rapid changes.
     */
    private final Map<Long, Long> invalidationCooldown = new ConcurrentHashMap<>();

    private static final long COOLDOWN_MS = 500;

    @Inject
    public BlockChangeListener(ChunkLoaderService chunkLoaderService,
                               FakeChunkService fakeChunkService,
                               ConfigService configService) {
        this.chunkLoaderService = chunkLoaderService;
        this.fakeChunkService = fakeChunkService;
        this.configService = configService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidateChunk(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidateChunk(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        for (var state : event.getReplacedBlockStates()) {
            invalidateChunk(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            invalidateChunk(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            invalidateChunk(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        invalidateChunk(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            invalidateChunk(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            invalidateChunk(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        invalidateChunk(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        invalidateChunk(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        invalidateChunk(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        invalidateChunk(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        invalidateChunk(event.getBlock());
    }

    /**
     * Invalidates cached chunk data and notifies players who have this chunk
     * as a fake chunk to re-fetch it with updated data.
     *
     * Applies a cooldown per chunk to batch rapid changes.
     */
    private void invalidateChunk(Block block) {
        if (!configService.get().performance().fakeChunks().blockChangeInvalidation()) {
            return;
        }

        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        UUID worldId = block.getWorld().getUID();

        long chunkKey = ((long) chunkZ << 32) | ((long) chunkX & 0xFFFFFFFFL);

        long now = System.currentTimeMillis();
        Long lastInvalidation = invalidationCooldown.get(chunkKey);
        if (lastInvalidation != null && (now - lastInvalidation) < COOLDOWN_MS) {
            return; 
        }
        invalidationCooldown.put(chunkKey, now);

        if (invalidationCooldown.size() > 5000) {
            invalidationCooldown.entrySet().removeIf(e -> (now - e.getValue()) > COOLDOWN_MS * 10);
        }

        chunkLoaderService.invalidatePacketCache(worldId, chunkX, chunkZ);
        fakeChunkService.invalidateAndResendChunk(chunkX, chunkZ, worldId);
    }
}
