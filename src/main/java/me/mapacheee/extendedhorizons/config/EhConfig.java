package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Map;

@ConfigSerializable
@Configurate("config")
public record EhConfig(
        DebugConfig debug,
        @Setting("fake-chunks") FakeChunksConfig fakeChunks,
        @Setting("world-settings") Map<String, WorldSettingsConfig> worldSettings
) {

    public static EhConfig empty() {
        return new EhConfig(null, null, null);
    }

    public boolean debugEnabled() {
        return this.debug == null || this.debug.enabled();
    }

    public int targetViewDistance(String worldName) {
        WorldSettingsConfig worldConfig = this.world(worldName);
        if (worldConfig != null && worldConfig.targetDistance() > 0) {
            return Math.max(2, worldConfig.targetDistance());
        }
        if (this.fakeChunks == null) {
            return 32;
        }
        return Math.max(2, this.fakeChunks.targetViewDistance());
    }

    public boolean fakeChunksEnabledForWorld(String worldName) {
        WorldSettingsConfig worldConfig = this.world(worldName);
        return worldConfig == null || worldConfig.enableFakechunks();
    }

    public int maxSendPerCycle() {
        if (this.fakeChunks == null) {
            return 6;
        }
        return Math.max(1, this.fakeChunks.maxSendPerCycle());
    }

    public int maxInflightPerPlayer() {
        if (this.fakeChunks == null) {
            return 4;
        }
        return Math.max(1, this.fakeChunks.maxInflightPerPlayer());
    }

    public int chunkQueueSize() {
        if (this.fakeChunks == null) {
            return 64;
        }
        return Math.max(8, this.fakeChunks.chunkQueueSize());
    }

    public long dispatchTimeBudgetNanos() {
        if (this.fakeChunks == null) {
            return 1_200_000L;
        }
        return Math.max(250_000L, this.fakeChunks.dispatchTimeBudgetNanos());
    }

    public long unavailableRetryMs() {
        if (this.fakeChunks == null) {
            return 150L;
        }
        return Math.max(25L, this.fakeChunks.unavailableRetryMs());
    }

    public boolean generateMissingChunks() {
        if (this.fakeChunks == null) {
            return true;
        }
        return this.fakeChunks.generateMissingChunks();
    }

    public long forcePlanIntervalMs() {
        if (this.fakeChunks == null) {
            return 2500L;
        }
        return Math.max(250L, this.fakeChunks.forcePlanIntervalMs());
    }

    public double safeSquareFactor() {
        if (this.fakeChunks == null) {
            return 0.8d;
        }
        double value = this.fakeChunks.safeSquareFactor();
        if (Double.isNaN(value) || value <= 0.0d) {
            return 0.8d;
        }
        return Math.min(1.5d, value);
    }

    public int cacheTtlSeconds() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return 15;
        }
        return Math.max(1, this.fakeChunks.cache().ttlSeconds());
    }

    public int cacheMaxEntries() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return 1500;
        }
        return Math.max(128, this.fakeChunks.cache().maxEntries());
    }

    public long cacheBypassAfterRealInteractionMs() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return 3000L;
        }
        return Math.max(250L, this.fakeChunks.cache().bypassAfterRealInteractionMs());
    }

    public int runtimePeriodTicks() {
        if (this.fakeChunks == null || this.fakeChunks.runtime() == null) {
            return 1;
        }
        return Math.max(1, this.fakeChunks.runtime().periodTicks());
    }

    private WorldSettingsConfig world(String worldName) {
        if (worldName == null || worldName.isBlank() || this.worldSettings == null || this.worldSettings.isEmpty()) {
            return null;
        }
        WorldSettingsConfig direct = this.worldSettings.get(worldName);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, WorldSettingsConfig> entry : this.worldSettings.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(worldName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @ConfigSerializable
    public record DebugConfig(boolean enabled) {
    }

    @ConfigSerializable
    public record FakeChunksConfig(
            @Setting("target-view-distance") int targetViewDistance,
            @Setting("max-send-per-cycle") int maxSendPerCycle,
            @Setting("max-inflight-per-player") int maxInflightPerPlayer,
            @Setting("chunk-queue-size") int chunkQueueSize,
            @Setting("dispatch-time-budget-nanos") long dispatchTimeBudgetNanos,
            @Setting("generate-missing-chunks") boolean generateMissingChunks,
            @Setting("force-plan-interval-ms") long forcePlanIntervalMs,
            @Setting("unavailable-retry-ms") long unavailableRetryMs,
            @Setting("safe-square-factor") double safeSquareFactor,
            CacheConfig cache,
            RuntimeConfig runtime
    ) {
    }

    @ConfigSerializable
    public record CacheConfig(
            @Setting("ttl-seconds") int ttlSeconds,
            @Setting("max-entries") int maxEntries,
            @Setting("bypass-after-real-interaction-ms") long bypassAfterRealInteractionMs
    ) {
    }

    @ConfigSerializable
    public record RuntimeConfig(
            @Setting("period-ticks") int periodTicks
    ) {
    }

    @ConfigSerializable
    public record WorldSettingsConfig(
            @Setting("enable-fakechunks") boolean enableFakechunks,
            @Setting("target-distance") int targetDistance
    ) {
    }
}

