package me.mapacheee.extendedhorizons.chunk.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.viewdistance.PlayerDistancePreferenceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/*
 * Listens for player movement and connection events to trigger fake chunk updates.
 */
@ListenerComponent
public class PlayerChunkListener implements Listener {

    private final FakeChunkService fakeChunkService;
    private final PlayerDistancePreferenceService playerDistancePreferenceService;

    @Inject
    public PlayerChunkListener(FakeChunkService fakeChunkService, PlayerDistancePreferenceService playerDistancePreferenceService) {
        this.fakeChunkService = fakeChunkService;
        this.playerDistancePreferenceService = playerDistancePreferenceService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        fakeChunkService.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerDistancePreferenceService.remove(event.getPlayer().getUniqueId());
        fakeChunkService.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getChunk().equals(event.getFrom().getChunk())) return;
        fakeChunkService.handleMove(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        fakeChunkService.handleTeleport(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        fakeChunkService.handleTeleport(event.getPlayer());
    }
}
