package me.mapacheee.extendedhorizons;

import dev.faststats.bukkit.BukkitContext;
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
import dev.faststats.data.Metric;

import java.util.concurrent.atomic.AtomicInteger;

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

    private static final String METRICS_TOKEN = "a1d882d1ace0dfbd8ccfa1eef51a4b1e";

    private BukkitContext context;

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
        this.context = new BukkitContext.Factory(this, METRICS_TOKEN)
            .metrics(factory -> factory
                .addMetric(Metric.number("active_sessions", () -> {
                    SessionRegistry registry = getService(SessionRegistry.class);
                    if (registry == null) return 0;
                    AtomicInteger active = new AtomicInteger();
                    registry.forEachSession(session -> {
                        if (session.enabled()) {
                            active.incrementAndGet();
                        }
                    });
                    return active.get();
                }))
                .addMetric(Metric.number("total_queued_chunks", () -> {
                    SessionRegistry registry = getService(SessionRegistry.class);
                    if (registry == null) return 0;
                    AtomicInteger queued = new AtomicInteger();
                    registry.forEachSession(session -> queued.addAndGet(session.chunkQueue().size()));
                    return queued.get();
                }))
                .addMetric(Metric.number("cached_built_chunks", () -> {
                    ChunkBuildCacheService cache = getService(ChunkBuildCacheService.class);
                    return cache != null ? cache.getEstimatedSize() : 0;
                }))
                .create())
            .create();

        this.context.ready();
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
        if (this.context != null) {
            this.context.shutdown();
        }
        instance = null;
        super.onPluginDisable();
    }

    @Override
    public void configure(Binder binder) {
        binder.bind(ChunkBackend.class).to(PaperChunkBackend.class);
        binder.bind(FarPlayerBackend.class).to(PaperFarPlayerBackend.class);
    }
}
