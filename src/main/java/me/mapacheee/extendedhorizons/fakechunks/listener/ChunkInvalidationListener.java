package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.hooks.worldedit.BulkChunkInvalidationService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

@ListenerComponent
public final class ChunkInvalidationListener implements Listener {
  private final BulkChunkInvalidationService invalidations;

  @Inject
  public ChunkInvalidationListener(BulkChunkInvalidationService invalidations) {
    this.invalidations = invalidations;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    invalidate(event.getBlockPlaced());
    if (event instanceof BlockMultiPlaceEvent multi) {
      multi.getReplacedBlockStates().forEach(state -> invalidate(state.getBlock()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityExplosion(EntityExplodeEvent event) {
    event.blockList().forEach(this::invalidate);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplosion(BlockExplodeEvent event) {
    invalidate(event.getBlock());
    event.blockList().forEach(this::invalidate);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    invalidate(event.getBlock());
    event.getBlocks().forEach(block -> {
      invalidate(block);
      invalidate(block.getRelative(event.getDirection()));
    });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    invalidate(event.getBlock());
    event.getBlocks().forEach(block -> {
      invalidate(block);
      invalidate(block.getRelative(event.getDirection()));
    });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFlow(BlockFromToEvent event) {
    invalidate(event.getBlock());
    invalidate(event.getToBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGrow(BlockGrowEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onForm(BlockFormEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSpread(BlockSpreadEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFade(BlockFadeEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDecay(LeavesDecayEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityChange(EntityChangeBlockEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    event.getBlocks().forEach(state -> invalidate(state.getBlock()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFertilize(BlockFertilizeEvent event) {
    event.getBlocks().forEach(state -> invalidate(state.getBlock()));
  }

  private void invalidate(Block block) {
    int x = block.getX() >> 4;
    int z = block.getZ() >> 4;
    int localX = block.getX() & 15;
    int localZ = block.getZ() & 15;
    var worldId = block.getWorld().getUID();
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        int distanceX = dx < 0 ? localX + 1 : dx > 0 ? 16 - localX : 0;
        int distanceZ = dz < 0 ? localZ + 1 : dz > 0 ? 16 - localZ : 0;
        if (distanceX + distanceZ <= 15) {
          invalidations.queueInvalidation(worldId, ChunkKeyCodec.pack(x + dx, z + dz));
        }
      }
    }
  }
}
