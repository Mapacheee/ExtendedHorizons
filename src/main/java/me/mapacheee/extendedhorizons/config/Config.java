package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
@Configurate("config")
public record Config(
        DebugConfig debug,
        @Setting("fake-chunks") FakeChunksConfig fakeChunks,
        @Setting("packet-interceptor") PacketInterceptorConfig packetInterceptor) {
    public boolean debugEnabled() {
        return debug == null || debug.enabled();
    }

    public int fakeTargetViewDistance() {
        return fakeChunks == null ? 32 : fakeChunks.targetViewDistance();
    }

    public int maxSendPerCycle() {
        return fakeChunks == null ? 25 : fakeChunks.maxSendPerCycle();
    }

    public int maxInflightPerPlayer() {
        return fakeChunks == null ? 16 : fakeChunks.maxInflightPerPlayer();
    }

    public long forcePlanIntervalMs() {
        return fakeChunks == null ? 3000L : fakeChunks.forcePlanIntervalMs();
    }

    public int keepAliveInitialDelayTicks() {
        if (fakeChunks == null || fakeChunks.keepalive() == null)
            return 20;
        return Math.max(1, fakeChunks.keepalive().initialDelayTicks());
    }

    public int keepAlivePeriodTicks() {
        if (fakeChunks == null || fakeChunks.keepalive() == null)
            return 10;
        return Math.max(1, fakeChunks.keepalive().periodTicks());
    }

    public int warmupDelayTicks() {
        if (fakeChunks == null || fakeChunks.warmup() == null)
            return 5;
        return Math.max(1, fakeChunks.warmup().delayTicks());
    }

    public double safeSquareFactor() {
        return fakeChunks == null ? 0.65d : fakeChunks.safeSquareFactor();
    }

    public int cacheTtlSeconds() {
        if (fakeChunks == null || fakeChunks.cache() == null)
            return 15;
        return fakeChunks.cache().ttlSeconds();
    }

    public int cacheMaxEntries() {
        if (fakeChunks == null || fakeChunks.cache() == null)
            return 1500;
        return fakeChunks.cache().maxEntries();
    }

    public long cacheBypassAfterRealInteractionMs() {
        if (fakeChunks == null || fakeChunks.cache() == null)
            return 3000L;
        return fakeChunks.cache().bypassAfterRealInteractionMs();
    }

    public boolean autoRefreshEnabled() {
        if (fakeChunks == null || fakeChunks.liveRefresh() == null)
            return true;
        return fakeChunks.liveRefresh().enabled();
    }

    public long autoRefreshPeriodMs() {
        if (fakeChunks == null || fakeChunks.liveRefresh() == null)
            return 1000L;
        return Math.max(250L, fakeChunks.liveRefresh().periodMs());
    }

    public int autoRefreshChunksPerCycle() {
        if (fakeChunks == null || fakeChunks.liveRefresh() == null)
            return 4;
        return Math.max(1, fakeChunks.liveRefresh().chunksPerCycle());
    }

    public int interceptorMaxTargetDistance() {
        return packetInterceptor == null ? 32 : packetInterceptor.maxTargetDistance();
    }

    public int interceptorMinPlayerTicksLived() {
        return packetInterceptor == null ? 40 : packetInterceptor.minPlayerTicksLived();
    }

    public int interceptorUnloadMarginChunks() {
        return packetInterceptor == null ? 1 : packetInterceptor.unloadMarginChunks();
    }

    @ConfigSerializable
    public record DebugConfig(boolean enabled) {
    }

    @ConfigSerializable
    public record FakeChunksConfig(
            @Setting("target-view-distance") int targetViewDistance,
            @Setting("max-send-per-cycle") int maxSendPerCycle,
            @Setting("max-inflight-per-player") int maxInflightPerPlayer,
            @Setting("force-plan-interval-ms") long forcePlanIntervalMs,
            KeepAliveConfig keepalive,
            WarmupConfig warmup,
            CacheConfig cache,
            @Setting("live-refresh") LiveRefreshConfig liveRefresh,
            @Setting("safe-square-factor") double safeSquareFactor) {
        @ConfigSerializable
        public record KeepAliveConfig(
                @Setting("initial-delay-ticks") int initialDelayTicks,
                @Setting("period-ticks") int periodTicks) {
        }

        @ConfigSerializable
        public record WarmupConfig(
                @Setting("delay-ticks") int delayTicks) {
        }

        @ConfigSerializable
        public record CacheConfig(
                @Setting("ttl-seconds") int ttlSeconds,
                @Setting("max-entries") int maxEntries,
                @Setting("bypass-after-real-interaction-ms") long bypassAfterRealInteractionMs) {
        }

        @ConfigSerializable
        public record LiveRefreshConfig(
                boolean enabled,
                @Setting("period-ms") long periodMs,
                @Setting("chunks-per-cycle") int chunksPerCycle) {
        }

    }

    @ConfigSerializable
    public record PacketInterceptorConfig(
            @Setting("max-target-distance") int maxTargetDistance,
            @Setting("min-player-ticks-lived") int minPlayerTicksLived,
            @Setting("unload-margin-chunks") int unloadMarginChunks) {
    }
}
