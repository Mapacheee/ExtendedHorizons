package me.mapacheee.extendedhorizons.playersync.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.playersync.FarPlayerSyncService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;

@ListenerComponent
public class FarPlayerSyncListener implements Listener {

  private final FarPlayerSyncService farPlayerSyncService;

  @Inject
  public FarPlayerSyncListener(FarPlayerSyncService farPlayerSyncService) {
    this.farPlayerSyncService = farPlayerSyncService;
  }

  @EventHandler
  public void onPlayerAnimation(PlayerAnimationEvent event) {
    if (event == null || event.getPlayer() == null) return;
    farPlayerSyncService.recordSwing(event.getPlayer().getUniqueId());
  }
}
