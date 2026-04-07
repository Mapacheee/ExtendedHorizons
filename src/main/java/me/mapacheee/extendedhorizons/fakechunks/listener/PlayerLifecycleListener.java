package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

@ListenerComponent
public final class PlayerLifecycleListener implements Listener {

    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public PlayerLifecycleListener(SessionRegistry sessionRegistry, ChannelInjectionService channelInjectionService) {
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.sessionRegistry.ensureFor(player, true);
        this.channelInjectionService.inject(player);
        this.sessionRegistry.updateChunk(player, player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        int fromChunkX = event.getFrom().getBlockX() >> 4;
        int fromChunkZ = event.getFrom().getBlockZ() >> 4;
        int toChunkX = event.getTo().getBlockX() >> 4;
        int toChunkZ = event.getTo().getBlockZ() >> 4;
        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) {
            return;
        }
        Player player = event.getPlayer();
        PlayerSession session = this.sessionRegistry.ensureFor(player, false);
        if (session.hasChunkChanged(toChunkX, toChunkZ)) {
            this.sessionRegistry.updateChunk(player, toChunkX, toChunkZ);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        this.sessionRegistry.ensureFor(player, true);
        this.channelInjectionService.inject(player);
        this.sessionRegistry.updateChunk(player, event.getTo().getBlockX() >> 4, event.getTo().getBlockZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.channelInjectionService.uninject(player);
        this.sessionRegistry.remove(player.getUniqueId());
    }
}


