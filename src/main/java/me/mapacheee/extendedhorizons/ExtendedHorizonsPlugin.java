package me.mapacheee.extendedhorizons;

import dev.faststats.bukkit.BukkitMetrics;
import dev.faststats.core.Metrics;
import com.google.inject.Binder;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.backend.PaperChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.disk.RegionFileReader;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.PaperFarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import dev.faststats.core.data.Metric;
import java.util.concurrent.atomic.AtomicInteger;

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

    private static final String METRICS_TOKEN = "a1d882d1ace0dfbd8ccfa1eef51a4b1e";

    private Metrics metrics;

    private static volatile ExtendedHorizonsPlugin instance;
    private static volatile boolean loading = false;

    public static ExtendedHorizonsPlugin getInstance() {
        return instance;
    }

    public static <T> T getService(Class<T> type) {
        ExtendedHorizonsPlugin current = instance;
        if (current == null || loading) {
            throw new IllegalStateException("ExtendedHorizons plugin is not loaded yet");
        }
        return current.getInjector().getInstance(type);
    }


    @Override
    public void onPluginEnable() {
        this.metrics = BukkitMetrics.factory()
            .token(METRICS_TOKEN)
            .addMetric(Metric.number("active_sessions", () -> {
                int count = 0;
                SessionRegistry registry = getService(SessionRegistry.class);
                if (registry != null) {
                    AtomicInteger active = new AtomicInteger();
                    registry.forEachSession(session -> {
                        if (session.enabled()) {
                            active.incrementAndGet();
                        }
                    });
                    count = active.get();
                }
                return count;
            }))
            .addMetric(Metric.number("total_queued_chunks", () -> {
                int count = 0;
                SessionRegistry registry = getService(SessionRegistry.class);
                if (registry != null) {
                    AtomicInteger queued = new AtomicInteger();
                    registry.forEachSession(session -> {
                        queued.addAndGet(session.chunkQueue().size());
                    });
                    count = queued.get();
                }
                return count;
            }))
            .addMetric(Metric.number("cached_built_chunks", () -> {
                ChunkBuildCacheService cache = getService(ChunkBuildCacheService.class);
                return cache != null ? cache.getEstimatedSize() : 0;
            }))
            .create(this);

        this.metrics.ready();
    }
    @Override
    public void onPluginLoad() {
        loading = true;
        try {
            super.onPluginLoad();
            instance = this;
        } finally {
            loading = false;
        }
    }

    @Override
    public void onPluginDisable() {
        RegionFileReader.clearCache();
        instance = null;
        super.onPluginDisable();
    }

    @Override
    public void configure(Binder binder) {
        binder.bind(ChunkBackend.class).to(PaperChunkBackend.class);
        binder.bind(FarPlayerBackend.class).to(PaperFarPlayerBackend.class);
    }
}

