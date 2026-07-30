package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;
import java.util.Map;

@ConfigSerializable
@Configurate("config")
public record EhConfig(
    DebugConfig debug,
    @Setting("fake-chunks") FakeChunksConfig fakeChunks,
    @Setting("world-settings") Map<String, WorldSettingsConfig> worldSettings,
    @Setting("welcome-enabled") boolean welcomeEnabled
) {

    private static final int MIN_VIEW_DISTANCE = 2;
    private static final int DEFAULT_TARGET_VIEW_DISTANCE = 32;
    private static final int DEFAULT_MAX_SEND_PER_CYCLE = 6;
    private static final int MIN_MAX_SEND_PER_CYCLE = 1;
    private static final long DEFAULT_BANDWIDTH_BYTES_PER_SECOND = 512_000L;
    private static final long MIN_BANDWIDTH_BYTES_PER_SECOND = 32_768L;
    private static final long DEFAULT_BANDWIDTH_BURST_BYTES = 1_024_000L;
    private static final int DEFAULT_SERIALIZATION_WORKERS = 0;
    private static final int MAX_SERIALIZATION_WORKERS = 16;
    private static final int DEFAULT_MAX_GLOBAL_GENERATIONS_PER_TICK = 50;
    private static final int MIN_MAX_GLOBAL_GENERATIONS_PER_TICK = 1;
    private static final int DEFAULT_MAX_INFLIGHT_PER_PLAYER = 4;
    private static final int MIN_MAX_INFLIGHT_PER_PLAYER = 1;
    private static final int DEFAULT_CHUNK_QUEUE_SIZE = 64;
    private static final int MIN_CHUNK_QUEUE_SIZE = 8;
    private static final long DEFAULT_UNAVAILABLE_RETRY_MS = 150L;
    private static final long MIN_UNAVAILABLE_RETRY_MS = 25L;
    private static final int DEFAULT_CACHE_TTL_SECONDS = 15;
    private static final int MIN_CACHE_TTL_SECONDS = 1;
    private static final int DEFAULT_CACHE_MAX_ENTRIES = 1500;
    private static final int MIN_CACHE_MAX_ENTRIES = 128;
    private static final long DEFAULT_BYPASS_AFTER_INTERACTION_MS = 3000L;
    private static final long MIN_BYPASS_AFTER_INTERACTION_MS = 250L;
    private static final int DEFAULT_PERMISSION_CACHE_ENTRIES = 4096;
    private static final int DEFAULT_PERMISSION_CACHE_TTL_SECONDS = 5;
    private static final int MIN_PERMISSION_CACHE_TTL_SECONDS = 1;
    private static final int DEFAULT_FAR_PLAYER_CACHE_ENTRIES = 500;
    private static final int MIN_FAR_PLAYER_CACHE_ENTRIES = 64;
    private static final int DEFAULT_RUNTIME_PERIOD_TICKS = 1;
    private static final int MIN_RUNTIME_PERIOD_TICKS = 1;
    private static final int DEFAULT_FAR_PLAYER_MOVE_TICKS = 4;
    private static final int MIN_FAR_PLAYER_MOVE_TICKS = 1;
    private static final int DEFAULT_FAR_PLAYER_EQUIP_TICKS = 15;

    private static final List<String> DEFAULT_ANTI_XRAY_HIDDEN_BLOCKS = List.of(
        "minecraft:diamond_ore",
        "minecraft:deepslate_diamond_ore",
        "minecraft:gold_ore",
        "minecraft:deepslate_gold_ore",
        "minecraft:iron_ore",
        "minecraft:deepslate_iron_ore",
        "minecraft:emerald_ore",
        "minecraft:deepslate_emerald_ore",
        "minecraft:redstone_ore",
        "minecraft:deepslate_redstone_ore",
        "minecraft:lapis_ore",
        "minecraft:deepslate_lapis_ore",
        "minecraft:coal_ore",
        "minecraft:deepslate_coal_ore",
        "minecraft:copper_ore",
        "minecraft:deepslate_copper_ore",
        "minecraft:nether_gold_ore",
        "minecraft:nether_quartz_ore",
        "minecraft:ancient_debris"
    );

    public static EhConfig empty() {
        return new EhConfig(null, null, null, true);
    }

    public boolean debugEnabled() {
        return this.debug == null || this.debug.enabled();
    }

    public int targetViewDistance(String worldName) {
        WorldSettingsConfig worldConfig = this.world(worldName);
        if (worldConfig != null && worldConfig.targetDistance() > 0) {
            return Math.max(MIN_VIEW_DISTANCE, worldConfig.targetDistance());
        }
        if (this.fakeChunks == null) {
            return DEFAULT_TARGET_VIEW_DISTANCE;
        }
        return Math.max(MIN_VIEW_DISTANCE, this.fakeChunks.targetViewDistance());
    }

    public boolean fakeChunksEnabledForWorld(String worldName) {
        WorldSettingsConfig worldConfig = this.world(worldName);
        return worldConfig == null || worldConfig.enableFakechunks();
    }

    public int maxSendPerCycle() {
        if (this.fakeChunks == null) {
            return DEFAULT_MAX_SEND_PER_CYCLE;
        }
        return Math.max(MIN_MAX_SEND_PER_CYCLE, this.fakeChunks.maxSendPerCycle());
    }

    public boolean bandwidthEnabled() {
        if (this.fakeChunks == null || this.fakeChunks.bandwidth() == null) {
            return false;
        }
        return this.fakeChunks.bandwidth().enabled();
    }

    public long bandwidthBytesPerSecond() {
        if (this.fakeChunks == null || this.fakeChunks.bandwidth() == null) {
            return DEFAULT_BANDWIDTH_BYTES_PER_SECOND;
        }
        return Math.max(MIN_BANDWIDTH_BYTES_PER_SECOND, this.fakeChunks.bandwidth().bytesPerSecond());
    }

    public long bandwidthBurstBytes() {
        if (this.fakeChunks == null || this.fakeChunks.bandwidth() == null) {
            return DEFAULT_BANDWIDTH_BURST_BYTES;
        }
        long configured = this.fakeChunks.bandwidth().burstBytes();
        return Math.max(this.bandwidthBytesPerSecond(), configured);
    }

    public SerializerMode serializerMode() {
        if (this.fakeChunks == null || this.fakeChunks.serializerMode() == null) {
            return SerializerMode.FAST;
        }
        return this.fakeChunks.serializerMode();
    }

    public int serializationWorkers() {
        if (this.fakeChunks == null) {
            return DEFAULT_SERIALIZATION_WORKERS;
        }
        int configured = this.fakeChunks.serializationWorkers();
        return Math.clamp(configured, DEFAULT_SERIALIZATION_WORKERS, MAX_SERIALIZATION_WORKERS);
    }

    public int maxGlobalGenerationsPerTick() {
        if (this.fakeChunks == null) {
            return DEFAULT_MAX_GLOBAL_GENERATIONS_PER_TICK;
        }
        return Math.max(MIN_MAX_GLOBAL_GENERATIONS_PER_TICK, this.fakeChunks.maxGlobalGenerationsPerTick());
    }

    public int maxInflightPerPlayer() {
        if (this.fakeChunks == null) {
            return DEFAULT_MAX_INFLIGHT_PER_PLAYER;
        }
        return Math.max(MIN_MAX_INFLIGHT_PER_PLAYER, this.fakeChunks.maxInflightPerPlayer());
    }

    public int chunkQueueSize() {
        if (this.fakeChunks == null) {
            return DEFAULT_CHUNK_QUEUE_SIZE;
        }
        return Math.max(MIN_CHUNK_QUEUE_SIZE, this.fakeChunks.chunkQueueSize());
    }

    public long unavailableRetryMs() {
        if (this.fakeChunks == null) {
            return DEFAULT_UNAVAILABLE_RETRY_MS;
        }
        return Math.max(MIN_UNAVAILABLE_RETRY_MS, this.fakeChunks.unavailableRetryMs());
    }

    public boolean generateMissingChunks() {
        if (this.fakeChunks == null) {
            return true;
        }
        return this.fakeChunks.generateMissingChunks();
    }

    public boolean diskReaderEnabled() {
        if (this.fakeChunks == null) {
            return false;
        }
        Boolean enabled = this.fakeChunks.diskReaderEnabled();
        return enabled != null && enabled;
    }

    public int cacheTtlSeconds() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return DEFAULT_CACHE_TTL_SECONDS;
        }
        return Math.max(MIN_CACHE_TTL_SECONDS, this.fakeChunks.cache().ttlSeconds());
    }

    public int cacheMaxEntries() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return DEFAULT_CACHE_MAX_ENTRIES;
        }
        return Math.max(MIN_CACHE_MAX_ENTRIES, this.fakeChunks.cache().maxEntries());
    }

    public long cacheBypassAfterRealInteractionMs() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return DEFAULT_BYPASS_AFTER_INTERACTION_MS;
        }
        return Math.max(MIN_BYPASS_AFTER_INTERACTION_MS, this.fakeChunks.cache().bypassAfterRealInteractionMs());
    }

    public int permissionCacheEntries() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return DEFAULT_PERMISSION_CACHE_ENTRIES;
        }
        int configured = this.fakeChunks.cache().permissionCacheEntries();
        return configured > 0 ? configured : DEFAULT_PERMISSION_CACHE_ENTRIES;
    }

    public int permissionCacheTtlSeconds() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return DEFAULT_PERMISSION_CACHE_TTL_SECONDS;
        }
        int configured = this.fakeChunks.cache().permissionCacheTtlSeconds();
        return Math.max(MIN_PERMISSION_CACHE_TTL_SECONDS, configured);
    }

    public int farPlayerCacheEntries() {
        if (this.fakeChunks == null || this.fakeChunks.cache() == null) {
            return DEFAULT_FAR_PLAYER_CACHE_ENTRIES;
        }
        int configured = this.fakeChunks.cache().farPlayerCacheEntries();
        return Math.max(MIN_FAR_PLAYER_CACHE_ENTRIES, configured);
    }

    public int runtimePeriodTicks() {
        if (this.fakeChunks == null || this.fakeChunks.runtime() == null) {
            return DEFAULT_RUNTIME_PERIOD_TICKS;
        }
        return Math.max(MIN_RUNTIME_PERIOD_TICKS, this.fakeChunks.runtime().periodTicks());
    }

    public int farPlayerMoveTicks() {
        if (this.fakeChunks == null || this.fakeChunks.farPlayers() == null) {
            return DEFAULT_FAR_PLAYER_MOVE_TICKS;
        }
        return Math.max(MIN_FAR_PLAYER_MOVE_TICKS, this.fakeChunks.farPlayers().moveTicks());
    }

    public boolean farPlayersEnabled() {
        if (this.fakeChunks == null || this.fakeChunks.farPlayers() == null) {
            return true;
        }
        Boolean enabled = this.fakeChunks.farPlayers().enabled();
        return enabled == null || enabled;
    }

    public int farPlayerEquipTicks() {
        if (this.fakeChunks == null || this.fakeChunks.farPlayers() == null) {
            return DEFAULT_FAR_PLAYER_EQUIP_TICKS;
        }
        return Math.max(1, this.fakeChunks.farPlayers().equipTicks());
    }

    public boolean antiXrayEnabled(String worldName) {
        WorldSettingsConfig worldConfig = this.world(worldName);
        if (worldConfig != null && worldConfig.antiXray() != null && worldConfig.antiXray().enabled() != null) {
            return worldConfig.antiXray().enabled();
        }
        if (this.fakeChunks == null || this.fakeChunks.antiXray() == null) {
            return false;
        }
        return this.fakeChunks.antiXray().enabled();
    }

    public List<String> antiXrayHiddenBlocks(String worldName) {
        WorldSettingsConfig worldConfig = this.world(worldName);
        if (worldConfig != null && worldConfig.antiXray() != null
            && worldConfig.antiXray().hiddenBlocks() != null && !worldConfig.antiXray().hiddenBlocks().isEmpty()) {
            return worldConfig.antiXray().hiddenBlocks();
        }
        if (this.fakeChunks == null || this.fakeChunks.antiXray() == null
            || this.fakeChunks.antiXray().hiddenBlocks() == null || this.fakeChunks.antiXray().hiddenBlocks().isEmpty()) {
            return DEFAULT_ANTI_XRAY_HIDDEN_BLOCKS;
        }
        return this.fakeChunks.antiXray().hiddenBlocks();
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
    public record DebugConfig(boolean enabled) {}

    @ConfigSerializable
    public record FakeChunksConfig(
        @Setting("target-view-distance") int targetViewDistance,
        @Setting("serializer-mode") SerializerMode serializerMode,
        @Setting("serialization-workers") int serializationWorkers,
        BandwidthConfig bandwidth,
        @Setting("max-send-per-cycle") int maxSendPerCycle,
        @Setting("max-global-generations-per-tick") int maxGlobalGenerationsPerTick,
        @Setting("max-inflight-per-player") int maxInflightPerPlayer,
        @Setting("chunk-queue-size") int chunkQueueSize,
        @Setting("generate-missing-chunks") boolean generateMissingChunks,
        @Setting("unavailable-retry-ms") long unavailableRetryMs,
        @Setting("anti-xray") AntiXrayConfig antiXray,
        @Setting("disk-reader-enabled") Boolean diskReaderEnabled,
        CacheConfig cache,
        RuntimeConfig runtime,
        @Setting("far-players") FarPlayersConfig farPlayers
    ) {}

    @ConfigSerializable
    public record CacheConfig(
        @Setting("ttl-seconds") int ttlSeconds,
        @Setting("max-entries") int maxEntries,
        @Setting("bypass-after-real-interaction-ms") long bypassAfterRealInteractionMs,
        @Setting("permission-cache-entries") int permissionCacheEntries,
        @Setting("permission-cache-ttl-seconds") int permissionCacheTtlSeconds,
        @Setting("far-player-cache-entries") int farPlayerCacheEntries
    ) {}

    @ConfigSerializable
    public record RuntimeConfig(
        @Setting("period-ticks") int periodTicks
    ) {}

    @ConfigSerializable
    public record BandwidthConfig(
        boolean enabled,
        @Setting("bytes-per-second") long bytesPerSecond,
        @Setting("burst-bytes") long burstBytes
    ) {}

    @ConfigSerializable
    public record WorldSettingsConfig(
        @Setting("enable-fakechunks") boolean enableFakechunks,
        @Setting("target-distance") int targetDistance,
        @Setting("anti-xray") WorldAntiXrayConfig antiXray
    ) {}

    @ConfigSerializable
    public record AntiXrayConfig(
        boolean enabled,
        @Setting("hidden-blocks") List<String> hiddenBlocks
    ) {}

    @ConfigSerializable
    public record WorldAntiXrayConfig(
        Boolean enabled,
        @Setting("hidden-blocks") List<String> hiddenBlocks
    ) {}

    @ConfigSerializable
    public record FarPlayersConfig(
        Boolean enabled,
        @Setting("move-ticks") int moveTicks,
        @Setting("equip-ticks") int equipTicks
    ) {}

    public enum SerializerMode {
        FAST,
        VANILLA
    }
}

