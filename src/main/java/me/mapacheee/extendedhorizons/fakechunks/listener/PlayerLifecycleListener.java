package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

@ListenerComponent
public final class PlayerLifecycleListener implements Listener {

    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;
    private final FarPlayerCacheService farPlayerCacheService;
    private final FakeChunkOrchestratorService fakeChunkOrchestratorService;

    @Inject
    public PlayerLifecycleListener(
            SessionRegistry sessionRegistry,
            ChannelInjectionService channelInjectionService,
            FarPlayerCacheService farPlayerCacheService,
            FakeChunkOrchestratorService fakeChunkOrchestratorService
    ) {
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
        this.farPlayerCacheService = farPlayerCacheService;
        this.fakeChunkOrchestratorService = fakeChunkOrchestratorService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.fakeChunkOrchestratorService.invalidatePermissionCache(player.getUniqueId());
        PlayerSession session = this.sessionRegistry.ensureFor(player, true);
        this.channelInjectionService.inject(player, session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        this.fakeChunkOrchestratorService.invalidatePermissionCache(player.getUniqueId());
        PlayerSession session = this.sessionRegistry.ensureFor(player, true);
        this.channelInjectionService.inject(player, session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.fakeChunkOrchestratorService.invalidatePermissionCache(player.getUniqueId());
        this.channelInjectionService.uninject(player);
        this.sessionRegistry.remove(player.getUniqueId());
        this.farPlayerCacheService.removePlayer(player.getUniqueId());
    }
}


