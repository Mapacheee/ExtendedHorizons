package me.mapacheee.extendedhorizons.shared.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.storage.ViewDataStorage;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkSenderService;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import me.mapacheee.extendedhorizons.optimization.service.CacheService;
import me.mapacheee.extendedhorizons.integration.service.LuckPermsIntegrationService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/* Scheduler Service - Manages all automated tasks and periodic operations
 * Handles performance monitoring, cache cleanup, and statistics collection
 */

@Service
public class SchedulerService {

    private final Logger logger;
    private final ConfigService configService;
    private final ViewDataStorage storage;
    private final ViewDistanceService viewDistanceService;
    private final ChunkSenderService chunkSenderService;
    private final PerformanceMonitorService performanceMonitor;
    private final CacheService cacheService;
    private final LuckPermsIntegrationService luckPermsService;

    private final List<BukkitTask> tasks;
    private final AtomicInteger peakPlayers;
    private volatile boolean running;

    @Inject
    public SchedulerService(
            Logger logger,
            ConfigService configService,
            ViewDataStorage storage,
            ViewDistanceService viewDistanceService,
            ChunkSenderService chunkSenderService,
            PerformanceMonitorService performanceMonitor,
            CacheService cacheService,
            LuckPermsIntegrationService luckPermsService
    ) {
        this.logger = logger;
        this.configService = configService;
        this.storage = storage;
        this.viewDistanceService = viewDistanceService;
        this.chunkSenderService = chunkSenderService;
        this.performanceMonitor = performanceMonitor;
        this.cacheService = cacheService;
        this.luckPermsService = luckPermsService;
        this.tasks = new ArrayList<>();
        this.peakPlayers = new AtomicInteger(0);
        this.running = false;

        startScheduledTasks();
    }

    private void startScheduledTasks() {
        if (running) return;
        running = true;

        // Chunk processing reset task (every tick)
        tasks.add(Bukkit.getScheduler().runTaskTimer(
                Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ExtendedHorizons")),
            chunkSenderService::resetGlobalChunkCounter,
            0L, 1L
        ));

        // Performance monitoring task (every 5 seconds)
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ExtendedHorizons")),
            this::performanceCheck,
            100L, 100L
        ));

        // Permission check task (every 60 seconds)
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ExtendedHorizons")),
            this::checkPlayerPermissions,
            1200L, 1200L
        ));

        // Auto-save task (every 5 minutes)
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ExtendedHorizons")),
            this::autoSavePlayerData,
            6000L, 6000L
        ));

        // Statistics collection task (every hour)
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ExtendedHorizons")),
            this::collectHourlyStatistics,
            72000L, 72000L
        ));

        // Daily statistics task (every 24 hours)
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("ExtendedHorizons")),
            this::collectDailyStatistics,
            0L, 1728000L // 24 hours in ticks
        ));

        logger.info("Scheduled tasks started successfully ({} tasks)", tasks.size());
    }

    private void performanceCheck() {
        try {
            int currentPlayers = Bukkit.getOnlinePlayers().size();
            peakPlayers.updateAndGet(peak -> Math.max(peak, currentPlayers));

            performAdaptiveAdjustments();

        } catch (Exception e) {
            logger.error("Error during performance check", e);
        }
    }

    private void performAdaptiveAdjustments() {
        var metrics = performanceMonitor.getCurrentMetrics();
        if (metrics.memoryUsagePercent() > 85.0) {
            cacheService.clearAllCache();
        }
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

    private void autoSavePlayerData() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                var playerView = viewDistanceService.getPlayerView(player);
                if (playerView != null) {
                    storage.savePlayerData(playerView);
                }
            }

            logger.debug("Auto-saved data for {} players", Bukkit.getOnlinePlayers().size());

        } catch (Exception e) {
            logger.error("Error during auto-save", e);
        }
    }

    private void collectHourlyStatistics() {
        try {
            viewDistanceService.resetStatistics();
            peakPlayers.set(Bukkit.getOnlinePlayers().size());
            logger.debug("Hourly statistics collected and reset");

        } catch (Exception e) {
            logger.error("Error collecting hourly statistics", e);
        }
    }

    private void collectDailyStatistics() {
        try {
            PerformanceMonitorService.PerformanceMetrics metrics = performanceMonitor.getCurrentMetrics();

            ViewDataStorage.DailyStats stats = new ViewDataStorage.DailyStats(
                new Date(),
                peakPlayers.get(),
                viewDistanceService.getTotalChunksSent(),
                viewDistanceService.getTotalFakeChunksSent(),
                calculateTotalNetworkBytes(),
                metrics.tps(),
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
                var view = viewDistanceService.getPlayerView(player);
                return view != null ? view.getNetworkBytesUsed() : 0;
            })
            .sum();
    }

    public void shutdown() {
        running = false;

        tasks.forEach(task -> {
            if (!task.isCancelled()) {
                task.cancel();
            }
        });
        tasks.clear();

        autoSavePlayerData();
        collectDailyStatistics();

        logger.info("Scheduler service shutdown completed");
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveTaskCount() {
        return (int) tasks.stream().filter(task -> !task.isCancelled()).count();
    }

    public int getPeakPlayers() {
        return peakPlayers.get();
    }

    public void resetPeakPlayers() {
        peakPlayers.set(Bukkit.getOnlinePlayers().size());
    }
}
