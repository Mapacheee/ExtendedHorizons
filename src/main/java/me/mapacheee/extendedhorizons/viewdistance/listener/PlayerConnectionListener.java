package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/*
 * Listens for player connection events (join and quit).
 * This class is responsible for initializing and cleaning up
 * player-specific data when they connect or disconnect.
 */
@ListenerComponent
public class PlayerConnectionListener implements Listener {

    private final ViewDistanceService viewDistanceService;

    @Inject
    public PlayerConnectionListener(ViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        viewDistanceService.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        viewDistanceService.handlePlayerQuit(event.getPlayer());
    }
}

