package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.messages.MessagesFacade;
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
    private final MessagesFacade messages;
    private final Container<EhConfig> configContainer;

    @Inject
    public PlayerLifecycleListener(
        SessionRegistry sessionRegistry,
        ChannelInjectionService channelInjectionService,
        FarPlayerCacheService farPlayerCacheService,
        FakeChunkOrchestratorService fakeChunkOrchestratorService,
        MessagesFacade messages,
        Container<EhConfig> configContainer
    ) {
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
        this.farPlayerCacheService = farPlayerCacheService;
        this.fakeChunkOrchestratorService = fakeChunkOrchestratorService;
        this.messages = messages;
        this.configContainer = configContainer;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.fakeChunkOrchestratorService.invalidatePermissionCache(player.getUniqueId());
        PlayerSession session = this.sessionRegistry.ensureFor(player, true);
        this.channelInjectionService.inject(player, session);

        if (this.configContainer.get().welcomeEnabled() && this.messages.raw().welcome() != null) {
            player.sendMessage(this.messages.welcome(session.loadedBvChunkKeys().length, player.getName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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


