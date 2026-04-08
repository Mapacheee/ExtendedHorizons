package me.mapacheee.extendedhorizons.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FoliaTaskUtil {

    private final static Logger LOGGER = LoggerFactory.getLogger(FoliaTaskUtil.class);

    private FoliaTaskUtil() {}

    public static ScheduledTask runGlobalTimer(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> runnable.run(),
                Math.max(1L, initialDelayTicks),
                Math.max(1L, periodTicks)
        );
    }

    public static void runForPlayer(Player player, Plugin plugin, Runnable runnable) {
        if (player == null || plugin == null || runnable == null) {
            return;
        }
        try {
            player.getScheduler().run(plugin, task -> runnable.run(), null);
        } catch (Throwable throwable) {
            LOGGER.error("Error running task for player", throwable);
        }
    }

    public static boolean runAtChunk(World world, int chunkX, int chunkZ, Plugin plugin, Runnable runnable) {
        if (world == null || plugin == null || runnable == null) {
            return false;
        }
        try {
            Location loc = new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
            Bukkit.getRegionScheduler().execute(plugin, loc, runnable);
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("Error running task for chunk", throwable);
            return false;
        }
    }
}
