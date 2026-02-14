package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class CacheInvalidationListener implements Listener {

    private final ChunkLoaderService chunkLoaderService;
    private final Plugin plugin;

    private final Map<Long, Long> dirtyChunksWithTimestamp = new ConcurrentHashMap<>();
    private ScheduledTask cleanupTask;

    private static final int MAX_DIRTY_ENTRIES = 10000;
    private static final long STALE_TIMEOUT_MS = 5 * 60 * 1000;

    @Inject
    public CacheInvalidationListener(ChunkLoaderService chunkLoaderService, Plugin plugin) {
        this.chunkLoaderService = chunkLoaderService;
        this.plugin = plugin;
    }

    @OnEnable
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        cleanupTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> cleanupStaleEntries(),
                1,
                1,
                TimeUnit.MINUTES
        );
    }

    @OnDisable
    public void unregister() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        dirtyChunksWithTimestamp.clear();
    }

    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        dirtyChunksWithTimestamp.entrySet().removeIf(entry ->
                (now - entry.getValue()) > STALE_TIMEOUT_MS);
    }

    private void markDirty(UUID worldId, int x, int z) {
        if (dirtyChunksWithTimestamp.size() >= MAX_DIRTY_ENTRIES) {
            cleanupStaleEntries();
            if (dirtyChunksWithTimestamp.size() >= MAX_DIRTY_ENTRIES) {
                return;
            }
        }

        long key = ChunkUtils.packChunkKey(x, z);
        dirtyChunksWithTimestamp.put(key, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        markDirty(event.getBlock().getWorld().getUID(), event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        markDirty(event.getBlock().getWorld().getUID(), event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        markDirty(event.getBlock().getWorld().getUID(), event.getBlock().getChunk().getX(),
                event.getBlock().getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        markDirty(event.getLocation().getWorld().getUID(), event.getLocation().getChunk().getX(),
                event.getLocation().getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        long key = ChunkUtils.packChunkKey(event.getChunk().getX(), event.getChunk().getZ());
        if (dirtyChunksWithTimestamp.containsKey(key)) {
            chunkLoaderService.invalidateRamCache(event.getWorld().getUID(), event.getChunk().getX(),
                    event.getChunk().getZ());
            dirtyChunksWithTimestamp.remove(key);
        }
    }
}
