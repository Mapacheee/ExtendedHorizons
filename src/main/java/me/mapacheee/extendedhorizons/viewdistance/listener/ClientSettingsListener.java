package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.ClientViewDistanceService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

@ListenerComponent
public class ClientSettingsListener implements Listener {

    private final ClientViewDistanceService clientViewDistanceService;

    @Inject
    public ClientSettingsListener(ClientViewDistanceService clientViewDistanceService) {
        this.clientViewDistanceService = clientViewDistanceService;
    }

    @EventHandler
    public void onClientOptionsChange(PlayerClientOptionsChangeEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return;
        clientViewDistanceService.set(player.getUniqueId(), event.getViewDistance());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        clientViewDistanceService.remove(player.getUniqueId());
    }
}

