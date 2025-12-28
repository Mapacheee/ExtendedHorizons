package me.mapacheee.extendedhorizons.viewdistance.service.event;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.event.FakeChunkLoadEvent;
import me.mapacheee.extendedhorizons.api.event.FakeChunkUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * centralized dispatcher for fake chunk events.
 * Handles the firing of Load/Unload events on the main thread (when possible)
 * or async,
 * ensuring safety and consistent error handling.
 */
@Service
public class ChunkEventDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ChunkEventDispatcher.class);

    @Inject
    public ChunkEventDispatcher() {
    }

    /**
     * Fires a FakeChunkLoadEvent.
     * Note: This method blocks until the event is processed on the main thread if
     * called from async.
     *
     * @param player     The player loading the chunk
     * @param chunkX     Chunk X coordinate
     * @param chunkZ     Chunk Z coordinate
     * @param world      The world
     * @param loadSource Source of the chunk
     * @return true if the event was cancelled, false otherwise
     */
    public boolean fireLoadEvent(Player player, int chunkX, int chunkZ, World world,
            FakeChunkLoadEvent.LoadSource loadSource) {
        try {
            if (Bukkit.isPrimaryThread()) {
                FakeChunkLoadEvent event = new FakeChunkLoadEvent(player, chunkX, chunkZ, world, loadSource);
                Bukkit.getPluginManager().callEvent(event);
                return event.isCancelled();
            }

            boolean cancelled = Bukkit.getScheduler().callSyncMethod(ExtendedHorizonsPlugin.getInstance(), () -> {
                FakeChunkLoadEvent event = new FakeChunkLoadEvent(player, chunkX, chunkZ, world, loadSource);
                Bukkit.getPluginManager().callEvent(event);
                return event.isCancelled();
            }).get();

            return cancelled;

        } catch (Exception e) {
            logger.error("[EH] Error firing FakeChunkLoadEvent for {},{}", chunkX, chunkZ, e);
            return false;
        }
    }

    /**
     * Fires a FakeChunkUnloadEvent.
     * Use this when you don't need to wait for the result or strictly need sync
     * execution (Unload is often just notification).
     * However, API usually expects events on main thread.
     *
     * @param player The player
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @param world  The world
     * @param reason The reason for unload
     */
    public void fireUnloadEvent(Player player, int chunkX, int chunkZ, World world,
            FakeChunkUnloadEvent.UnloadReason reason) {
        Bukkit.getScheduler().runTask(ExtendedHorizonsPlugin.getInstance(), () -> {
            try {
                FakeChunkUnloadEvent event = new FakeChunkUnloadEvent(player, chunkX, chunkZ, world, reason);
                Bukkit.getPluginManager().callEvent(event);
            } catch (Throwable t) {
                logger.error("[EH] Error firing FakeChunkUnloadEvent", t);
            }
        });
    }

    /**
     * Optimized version for firing load event where we want to know cancellation
     * status
     * efficiently.
     */
    public boolean fireLoadEventAndWait(Player player, int chunkX, int chunkZ, World world,
            FakeChunkLoadEvent.LoadSource loadSource) {
        if (!player.isOnline())
            return true;

        try {
            return Bukkit.getScheduler().callSyncMethod(ExtendedHorizonsPlugin.getInstance(), () -> {
                FakeChunkLoadEvent event = new FakeChunkLoadEvent(player, chunkX, chunkZ, world, loadSource);
                Bukkit.getPluginManager().callEvent(event);
                return event.isCancelled();
            }).get();
        } catch (Exception e) {
            logger.error("[EH] Error firing FakeChunkLoadEvent sync", e);
            return false;
        }
    }
}
