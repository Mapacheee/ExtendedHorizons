package me.mapacheee.extendedhorizons.view;

import com.google.inject.Inject;
import com.thewinterframework.component.annotation.Component;
import com.thewinterframework.component.annotation.HandledBy;
import com.thewinterframework.paper.listener.handler.ListenerComponentHandler;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.minecraft.world.InteractionHand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

@Component
@HandledBy(ListenerComponentHandler.class)
public final class ViewListener implements Listener {

  private final ViewService views;

  @Inject
  public ViewListener(ViewService views) {
    this.views = views;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onConfigure(PlayerConnectionInitialConfigureEvent event) {
    this.views.configure(event.getConnection());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSwing(PlayerArmSwingEvent event) {
    this.views.swing(event.getPlayer().getUniqueId(),
      event.getHand() == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onHide(PlayerHideEntityEvent event) {
    if (event.getEntity() instanceof Player player) {
      this.views.hide(event.getPlayer().getUniqueId(), player.getUniqueId(), true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onShow(PlayerShowEntityEvent event) {
    if (event.getEntity() instanceof Player player) {
      this.views.hide(event.getPlayer().getUniqueId(), player.getUniqueId(), false);
    }
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
