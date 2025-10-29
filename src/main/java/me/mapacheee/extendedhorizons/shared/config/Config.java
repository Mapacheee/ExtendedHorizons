package me.mapacheee.extendedhorizons.shared.config;

/* Configuration record for ExtendedHorizons plugin
 * Uses Winter Framework's configuration system with automatic YAML mapping
 */

public record Config(
    GeneralConfig general,
    ViewDistanceConfig viewDistance,
    PerformanceConfig performance,
    NetworkConfig network,
    FakeChunksConfig fakeChunks,
    WorldsConfig worlds,
    DatabaseConfig database,
    IntegrationsConfig integrations,
    MonitoringConfig monitoring
) {

    public record GeneralConfig(
        boolean enabled,
        String language,
        boolean debug,
        boolean detectFolia
    ) {

    }

    public record ViewDistanceConfig(
        int maxDistance,
        int minDistance,
        int defaultDistance,
        String shape,
        boolean enableFakeChunks,
        int fakeChunksStartDistance
    ) {

    }

    public record PerformanceConfig(
        int maxChunksPerTick,
        int maxGenerationPerTick,
        boolean adaptivePerformance,
        double minTpsThreshold,
        boolean enableMultithreading,
        int workerThreads
    ) {

    }

    public record NetworkConfig(
        long maxBytesPerSecondPerPlayer,
        long maxBytesPerSecondGlobal,
        boolean adaptiveSpeed,
        int compressionLevel,
        boolean enablePacketBundling
    ) {

    }

    public record FakeChunksConfig(
        boolean simulateTerrain,
        boolean simulateBiomes,
        boolean simulateStructures,
        int cacheSize,
        boolean heightBased
    ) {

    }

    public record WorldsConfig(
        WorldConfig defaultWorld,
        WorldConfig worldNether,
        WorldConfig worldTheEnd
    ) {

        public record WorldConfig(
            boolean enabled,
            int maxDistance,
            boolean fakeChunksEnabled,
            String performanceMode
        ) {

        }
    }

    public record DatabaseConfig(
        String type,
        String fileName,
        int connectionPoolSize,
        boolean enableCache,
        int autoSaveInterval
    ) {

    }

    public record IntegrationsConfig(
        PlaceholderApiConfig placeholderapi,
        LuckPermsConfig luckperms
    ) {

        public record PlaceholderApiConfig(boolean enabled) {

        }

        public record LuckPermsConfig(
            boolean enabled,
            int checkInterval,
            boolean useGroupPermissions
        ) {

        }
    }

    public record MonitoringConfig(
        boolean enableMetrics,
        boolean logPerformanceWarnings,
        int chunkLoadTimeout,
        int networkTimeout
    ) {

    }
}
