package me.mapacheee.extendedhorizons.view;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

@ListenerComponent
public final class ViewListener implements Listener {

  private final ViewService views;

  @Inject
  public ViewListener(ViewService views) {
    this.views = views;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    this.views.join(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    this.views.detach(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
    this.views.teleport(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChunkLoad(ChunkLoadEvent event) {
    this.views.invalidate(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChunkUnload(ChunkUnloadEvent event) {
    this.views.invalidate(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWorldUnload(WorldUnloadEvent event) {
    this.views.invalidate(event.getWorld());
  }
}
