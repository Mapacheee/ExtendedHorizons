package me.mapacheee.extendedhorizons.optimization.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/* Performance Monitor Service - Monitors server performance and adjusts view distances
 * Tracks TPS, memory usage, and network performance for adaptive optimizations
 */

@Service
public class PerformanceMonitorService {

    private final Logger logger;
    private final ConfigService configService;
    private final MessageUtil messageUtil;

    private final ScheduledExecutorService monitorExecutor;
    private final AtomicReference<PerformanceMetrics> currentMetrics;
    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;

    private volatile boolean performanceWarningActive;
    private volatile long lastTpsWarning;
    private volatile long lastMemoryWarning;

    @Inject
    public PerformanceMonitorService(Logger logger, ConfigService configService, MessageUtil messageUtil) {
        this.logger = logger;
        this.configService = configService;
        this.messageUtil = messageUtil;
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExtendedHorizons-PerformanceMonitor");
            t.setDaemon(true);
            return t;
        });
        this.currentMetrics = new AtomicReference<>(new PerformanceMetrics(20.0, 0, 0, 0, 0));
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.performanceWarningActive = false;
        this.lastTpsWarning = 0;
        this.lastMemoryWarning = 0;

        startMonitoring();
    }

    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::updateMetrics, 1, 1, TimeUnit.SECONDS);

        if (configService.isPerformanceWarningLoggingEnabled()) {
            monitorExecutor.scheduleAtFixedRate(this::checkPerformanceWarnings, 5, 5, TimeUnit.SECONDS);
        }
    }

    private void updateMetrics() {
        try {
            double tps = calculateTPS();
            long usedMemory = getUsedMemoryMB();
            long maxMemory = getMaxMemoryMB();
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
            double cpuUsage = getCpuUsage();

            PerformanceMetrics metrics = new PerformanceMetrics(
                tps, usedMemory, maxMemory, memoryUsagePercent, cpuUsage
            );

            currentMetrics.set(metrics);

        } catch (Exception e) {
            logger.error("Error updating performance metrics", e);
        }
    }

    private double calculateTPS() {
        try {
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            double[] recentTps = (double[]) server.getClass().getMethod("recentTps").invoke(server);
            return Math.min(20.0, Math.max(0.0, recentTps[0]));
        } catch (Exception e) {
            return estimateTPS();
        }
    }

    private double estimateTPS() {
        long start = System.nanoTime();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 20.0;
        }
        long elapsed = System.nanoTime() - start;
        double actualSleep = elapsed / 1_000_000.0;

        if (actualSleep > 55) {
            return Math.max(1.0, 20.0 * (50.0 / actualSleep));
        }
        return 20.0;
    }

    private long getUsedMemoryMB() {
        return (memoryBean.getHeapMemoryUsage().getUsed() +
                memoryBean.getNonHeapMemoryUsage().getUsed()) / (1024 * 1024);
    }

    private long getMaxMemoryMB() {
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long nonHeapMax = memoryBean.getNonHeapMemoryUsage().getMax();

        if (heapMax == -1) heapMax = Runtime.getRuntime().maxMemory();
        if (nonHeapMax == -1) nonHeapMax = 256 * 1024 * 1024; // 256MB estimate

        return (heapMax + nonHeapMax) / (1024 * 1024);
    }

    private double getCpuUsage() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getProcessCpuLoad() * 100;
        }
        return 0.0;
    }

    private void checkPerformanceWarnings() {
        PerformanceMetrics metrics = currentMetrics.get();
        long currentTime = System.currentTimeMillis();

        if (metrics.tps() < configService.getMinTpsThreshold()) {
            if (currentTime - lastTpsWarning > 30000) { // 30 seconds cooldown
                notifyLowTPS(metrics.tps());
                lastTpsWarning = currentTime;
            }
            performanceWarningActive = true;
        }

        if (metrics.memoryUsagePercent() > 85.0) {
            if (currentTime - lastMemoryWarning > 60000) {
                notifyHighMemoryUsage(metrics.memoryUsagePercent());
                lastMemoryWarning = currentTime;
            }
        }

        if (metrics.tps() >= configService.getMinTpsThreshold() && performanceWarningActive) {
            notifyPerformanceRecovered(metrics.tps());
            performanceWarningActive = false;
        }
    }

    private void notifyLowTPS(double tps) {
        logger.warn("Low TPS detected: {}", String.format("%.1f", tps));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("extendedhorizons.admin")) {
                messageUtil.sendLowTpsWarning(player, tps);
            }
        }
    }

    private void notifyHighMemoryUsage(double memoryPercent) {
        logger.warn("High memory usage detected: {}%", String.format("%.1f", memoryPercent));
    }

    private void notifyPerformanceRecovered(double tps) {
        logger.info("Server performance recovered: TPS {}", String.format("%.1f", tps));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("extendedhorizons.admin")) {
                messageUtil.sendMessage(player, configService.getPerformanceRestoredMessage());
            }
        }
    }

    public double getCurrentTPS() {
        return currentMetrics.get().tps();
    }

    public PerformanceMetrics getCurrentMetrics() {
        return currentMetrics.get();
    }

    public boolean shouldReduceViewDistances() {
        PerformanceMetrics metrics = currentMetrics.get();
        // Removed automatic TPS-based view distance reduction
        return metrics.memoryUsagePercent() > 85.0; // Only reduce on very high memory usage
    }

    public double getPerformanceScore() {
        PerformanceMetrics metrics = currentMetrics.get();

        double tpsScore = Math.min(1.0, metrics.tps() / 20.0);
        double memoryScore = Math.max(0.0, 1.0 - (metrics.memoryUsagePercent() / 100.0));
        double cpuScore = Math.max(0.0, 1.0 - (metrics.cpuUsage() / 100.0));

        return (tpsScore * 0.5) + (memoryScore * 0.3) + (cpuScore * 0.2);
    }

    public int getRecommendedMaxViewDistance() {
        // Removed automatic view distance adjustment based on performance
        // Always return the configured maximum
        return 64;
    }

    public void shutdown() {
        monitorExecutor.shutdown();
        try {
            if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record PerformanceMetrics(
        double tps,
        long usedMemoryMB,
        long maxMemoryMB,
        double memoryUsagePercent,
        double cpuUsage
        // Removed isLagging field - no longer needed
    ) {}
}
