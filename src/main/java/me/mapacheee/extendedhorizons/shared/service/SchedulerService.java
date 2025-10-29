package me.mapacheee.extendedhorizons.shared.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.storage.ViewDataStorage;
import me.mapacheee.extendedhorizons.viewdistance.service.IViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/* Scheduler Service - Handles periodic tasks and statistics collection
 * Manages hourly and daily statistics for the fake chunks system
 */

@Service
public class SchedulerService {

    private final Logger logger;
    private final ConfigService configService;
    private final IViewDistanceService viewDistanceService;
    private final ViewDataStorage storage;

    private final ScheduledExecutorService scheduler;
    private final AtomicInteger peakPlayers;

    @Inject
    public SchedulerService(
        Logger logger,
        ConfigService configService,
        IViewDistanceService viewDistanceService,
        ViewDataStorage storage
    ) {
        this.logger = logger;
        this.configService = configService;
        this.viewDistanceService = viewDistanceService;
        this.storage = storage;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ExtendedHorizons-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.peakPlayers = new AtomicInteger(0);

        startScheduledTasks();
    }

    private void startScheduledTasks() {
        scheduler.scheduleAtFixedRate(
            this::collectHourlyStatistics,
            1,
            1,
            TimeUnit.HOURS
        );

        // Daily statistics
        scheduler.scheduleAtFixedRate(
            this::collectDailyStatistics,
            1,
            24,
            TimeUnit.HOURS
        );

        scheduler.scheduleAtFixedRate(
            this::checkPlayerPermissions,
            30,
            30,
            TimeUnit.SECONDS
        );

        logger.info("Scheduled tasks started");
    }

    private void collectHourlyStatistics() {
        try {
            viewDistanceService.resetStats();
            peakPlayers.set(Bukkit.getOnlinePlayers().size());
            logger.debug("Hourly statistics collected and reset");

        } catch (Exception e) {
            logger.error("Error collecting hourly statistics", e);
        }
    }

    private void collectDailyStatistics() {
        try {
            ViewDataStorage.DailyStats stats = new ViewDataStorage.DailyStats(
                new Date(),
                peakPlayers.get(),
                viewDistanceService.getTotalChunksSent(),
                viewDistanceService.getTotalFakeChunksSent(),
                calculateTotalNetworkBytes(),
                20,
                peakPlayers.get()
            );

            storage.saveDailyStats(stats);

            logger.info("Daily statistics saved - Peak players: {}, Chunks sent: {}, Fake chunks: {}",
                stats.peakPlayers(), stats.chunksSent(), stats.fakeChunksSent());

        } catch (Exception e) {
            logger.error("Error collecting daily statistics", e);
        }
    }

    private long calculateTotalNetworkBytes() {
        return Bukkit.getOnlinePlayers().stream()
            .mapToLong(player -> {
                var view = viewDistanceService.getPlayerView(player.getUniqueId());
                return view != null ? view.getNetworkBytesUsed() : 0;
            })
            .sum();
    }

    private void checkPlayerPermissions() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                viewDistanceService.updatePlayerPermissions(player);
            }
        } catch (Exception e) {
            logger.error("Error checking player permissions", e);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Scheduler service shut down");
    }
}
