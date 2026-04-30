package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.LightPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkDispatchService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.UUID;

@ListenerComponent
public final class ChunkInvalidationListener implements Listener {

    private final ChunkBuildCacheService cacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;
    private final ChunkDispatchService dispatchService;

    @Inject
    public ChunkInvalidationListener(
        ChunkBuildCacheService cacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService,
        LightPayloadCacheService lightPayloadCacheService,
        SessionRegistry sessionRegistry,
        ChannelInjectionService channelInjectionService,
        ChunkDispatchService dispatchService
    ) {
        this.cacheService = cacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
        this.lightPayloadCacheService = lightPayloadCacheService;
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
        this.dispatchService = dispatchService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        this.invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        this.invalidate(event.getBlockPlaced());
    }

    private void invalidate(Block block) {
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        long chunkKey = ChunkKeyCodec.pack(chunkX, chunkZ);
        UUID worldId = block.getWorld().getUID();
        this.cacheService.invalidate(worldId, chunkKey);
        this.antiXrayPayloadCacheService.invalidateChunk(worldId, chunkKey);
        this.lightPayloadCacheService.invalidate(worldId, chunkKey);

        this.sessionRegistry.forEachSession(session -> {
            if (!worldId.equals(session.worldId())) {
                return;
            }
            boolean wasLoaded = session.invalidateChunk(chunkKey);
            if (wasLoaded) {
                Player player = Bukkit.getPlayer(session.playerId());
                if (player == null) {
                    return;
                }
                Channel channel = this.channelInjectionService.resolveChannel(player);
                if (channel == null || !channel.isActive()) {
                    return;
                }
                this.channelInjectionService.executeOnEventLoop(channel, () ->
                    this.dispatchService.sendUnload(channel, session, chunkKey)
                );
            }
        });
    }
}
