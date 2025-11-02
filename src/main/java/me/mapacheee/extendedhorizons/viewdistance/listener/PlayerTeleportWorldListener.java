package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/*
 * Handles teleports and world changes to resync cache center/radius and prefetch
 */
@ListenerComponent
public class PlayerTeleportWorldListener implements Listener {

    private final ViewDistanceService viewDistanceService;

    @Inject
    public PlayerTeleportWorldListener(ViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        viewDistanceService.updatePlayerViewFast(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        viewDistanceService.updatePlayerViewFast(event.getPlayer());
    }
}

