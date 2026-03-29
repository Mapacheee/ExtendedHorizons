package me.mapacheee.extendedhorizons.chunk.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

@ListenerComponent
public class ChunkCacheInvalidationListener implements Listener {

  private final ChunkPacketCacheService chunkPacketCacheService;
  private final FakeChunkService fakeChunkService;

  @Inject
  public ChunkCacheInvalidationListener(
      ChunkPacketCacheService chunkPacketCacheService, FakeChunkService fakeChunkService) {
    this.chunkPacketCacheService = chunkPacketCacheService;
    this.fakeChunkService = fakeChunkService;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    updatePresence(event.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    chunkPacketCacheService.onPlayerQuit(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onTeleport(PlayerTeleportEvent event) {
    updatePresence(event.getPlayer());
  }

  @EventHandler
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    updatePresence(event.getPlayer());
  }

  @EventHandler
  public void onMove(PlayerMoveEvent event) {
    if (event.getTo() == null || event.getFrom() == null) return;
    if (event.getTo().getChunk().equals(event.getFrom().getChunk())) return;
    updatePresence(event.getPlayer());
  }

  @EventHandler
  public void onBreak(BlockBreakEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onPlace(BlockPlaceEvent event) {
    invalidate(event.getBlockPlaced());
  }

  @EventHandler
  public void onBurn(BlockBurnEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onFade(BlockFadeEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onForm(BlockFormEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onGrow(BlockGrowEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onSpread(BlockSpreadEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onIgnite(BlockIgniteEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onPhysics(BlockPhysicsEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onFlow(BlockFromToEvent event) {
    invalidate(event.getBlock());
    invalidate(event.getToBlock());
  }

  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) {
    invalidate(event.getClickedBlock());
  }

  @EventHandler
  public void onEntityChangeBlock(EntityChangeBlockEvent event) {
    invalidate(event.getBlock());
  }

  @EventHandler
  public void onBlockExplode(BlockExplodeEvent event) {
    invalidate(event.getBlock());
    for (Block block : event.blockList()) {
      invalidate(block);
    }
  }

  @EventHandler
  public void onEntityExplode(EntityExplodeEvent event) {
    for (Block block : event.blockList()) {
      invalidate(block);
    }
  }

  private void updatePresence(Player player) {
    if (player == null || !player.isOnline()) return;
    int chunkX = player.getLocation().getBlockX() >> 4;
    int chunkZ = player.getLocation().getBlockZ() >> 4;
    chunkPacketCacheService.onPlayerChunk(
        player.getUniqueId(), player.getWorld().getUID(), chunkX, chunkZ);
    fakeChunkService.handleRealChunkInteraction(player.getWorld(), chunkX, chunkZ);
  }

  private void invalidate(Block block) {
    if (block == null) return;
    int chunkX = block.getX() >> 4;
    int chunkZ = block.getZ() >> 4;
    fakeChunkService.handleRealChunkInteraction(block.getWorld(), chunkX, chunkZ);
  }
}
