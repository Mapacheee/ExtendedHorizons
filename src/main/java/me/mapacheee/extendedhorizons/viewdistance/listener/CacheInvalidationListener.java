package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import java.util.UUID;

@Service
public class CacheInvalidationListener implements Listener {

    private final ChunkLoaderService chunkLoaderService;
    private final Plugin plugin;

    @Inject
    public CacheInvalidationListener(ChunkLoaderService chunkLoaderService, Plugin plugin) {
        this.chunkLoaderService = chunkLoaderService;
        this.plugin = plugin;
    }

    @OnEnable
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @OnDisable
    public void unregister() {
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        chunkLoaderService.invalidateRamCache(
                event.getBlock().getWorld().getUID(),
                event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        chunkLoaderService.invalidateRamCache(
                event.getBlock().getWorld().getUID(),
                event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> {
            UUID worldId = block.getWorld().getUID();
            int chunkX = block.getChunk().getX();
            int chunkZ = block.getChunk().getZ();
            chunkLoaderService.invalidateRamCache(worldId, chunkX, chunkZ);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> {
            UUID worldId = block.getWorld().getUID();
            int chunkX = block.getChunk().getX();
            int chunkZ = block.getChunk().getZ();
            chunkLoaderService.invalidateRamCache(worldId, chunkX, chunkZ);
        });
    }
}
