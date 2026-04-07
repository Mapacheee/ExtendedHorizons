package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

@ListenerComponent
public final class ChunkInvalidationListener implements Listener {

    private final ChunkBuildCacheService cacheService;
    private final SessionRegistry sessionRegistry;

    @Inject
    public ChunkInvalidationListener(ChunkBuildCacheService cacheService, SessionRegistry sessionRegistry) {
        this.cacheService = cacheService;
        this.sessionRegistry = sessionRegistry;
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
        long chunkKey = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
        this.cacheService.invalidate(block.getWorld().getUID(), chunkKey);

        for (Player player : block.getWorld().getPlayers()) {
            PlayerSession session = this.sessionRegistry.get(player.getUniqueId());
            if (session == null || !session.sentChunks().contains(chunkKey)) {
                continue;
            }
            session.sentChunks().remove(chunkKey);
            session.inflightChunks().remove(chunkKey);
            if (session.queuedChunks().add(chunkKey)) {
                session.pendingQueue().addFirst(chunkKey);
            }
        }
    }
}
