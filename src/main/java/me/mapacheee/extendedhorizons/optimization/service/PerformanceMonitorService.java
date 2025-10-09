package me.mapacheee.extendedhorizons.optimization.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/* Performance Monitor Service - Collects performance metrics for stats only */

@Service
public class PerformanceMonitorService {

    private final Logger logger;
    private final ConfigService configService;

    private final ScheduledExecutorService monitorExecutor;
    private final AtomicReference<PerformanceMetrics> currentMetrics;
    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;

    @Inject
    public PerformanceMonitorService(Logger logger, ConfigService configService) {
        this.logger = logger;
        this.configService = configService;
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExtendedHorizons-PerformanceMonitor");
            t.setDaemon(true);
            return t;
        });
        this.currentMetrics = new AtomicReference<>(new PerformanceMetrics(20.0, 0, 0, 0, 0));
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();

        startMonitoring();
    }

    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::updateMetrics, 1, 1, TimeUnit.SECONDS);
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
            return 20.0;
        }
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

    public double getCurrentTPS() {
        return currentMetrics.get().tps();
    }

    public PerformanceMetrics getCurrentMetrics() {
        return currentMetrics.get();
    }

    public double getPerformanceScore() {
        PerformanceMetrics metrics = currentMetrics.get();

        double tpsScore = Math.min(1.0, metrics.tps() / 20.0);
        double memoryScore = Math.max(0.0, 1.0 - (metrics.memoryUsagePercent() / 100.0));
        double cpuScore = Math.max(0.0, 1.0 - (metrics.cpuUsage() / 100.0));

        return (tpsScore * 0.5) + (memoryScore * 0.3) + (cpuScore * 0.2);
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
    ) {}
}
