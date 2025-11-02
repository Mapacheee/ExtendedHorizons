package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/*
 *   Listens player movement and triggers view updates
 *   Uses fast prefetch for large jumps (elytra) to avoid gaps
 *   Schedules a normal update shortly after for reconciliation
*/
@ListenerComponent
public class PlayerMovementListener implements Listener {

    private final ViewDistanceService viewDistanceService;

    @Inject
    public PlayerMovementListener(ViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        int fromChunkX = event.getFrom().getBlockX() >> 4;
        int fromChunkZ = event.getFrom().getBlockZ() >> 4;
        int toChunkX = event.getTo().getBlockX() >> 4;
        int toChunkZ = event.getTo().getBlockZ() >> 4;
        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) return;

        int dX = Math.abs(toChunkX - fromChunkX);
        int dZ = Math.abs(toChunkZ - fromChunkZ);
        int cheb = Math.max(dX, dZ);

        if (cheb >= 3) {
            viewDistanceService.updatePlayerViewFast(event.getPlayer());
            Bukkit.getScheduler().runTaskLater(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class), () -> {
                if (event.getPlayer().isOnline()) viewDistanceService.updatePlayerView(event.getPlayer());
            }, 5L);
        } else {
            viewDistanceService.updatePlayerView(event.getPlayer());
        }
    }
}
