package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

@ListenerComponent
public class ClientSettingsListener implements Listener {

    private final ViewDistanceService viewDistanceService;

    @Inject
    public ClientSettingsListener(ViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
    }

    @EventHandler
    public void onClientOptionsChange(PlayerClientOptionsChangeEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        try {
            if (((CraftPlayer) player).getHandle().connection == null) {
                return;
            }
        } catch (Exception e) {
            return;
        }

        if (event.hasViewDistanceChanged()) {
            int newDistance = event.getViewDistance();
            try {
                viewDistanceService.setPlayerDistance(player, newDistance);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
