package me.mapacheee.extendedhorizons.shared.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/*
 * Represents the main 'config.yml' structure.
 * Only contains the keys present in the shipped config.yml.
 */
@ConfigSerializable
@Configurate("config")
public record MainConfig(
                ViewDistanceConfig viewDistance,
                java.util.Map<String, WorldConfig> worldSettings,
                PerformanceConfig performance,
                DatabaseConfig database,
                IntegrationsConfig integrations,
                MessagesConfig messages,
                BandwidthSaverConfig bandwidthSaver) {

        @ConfigSerializable
        public record ViewDistanceConfig(int maxDistance, int defaultDistance) {
        }

        @ConfigSerializable
        public record WorldConfig(
                        boolean enabled,
                        int maxDistance) {
        }

        @ConfigSerializable
        public record PerformanceConfig(
                        @Setting("chunk-processor-threads") int chunkProcessorThreads,
                        @Setting("teleport-warmup-delay") int teleportWarmupDelay,
                        @Setting("max-mspt-for-loading") double maxMsptForLoading,
                        @Setting("max-async-load-tasks") int maxAsyncLoadTasks,
                        @Setting("max-async-load-queue") int maxAsyncLoadQueue,
                        @Setting("max-generations-per-tick") int maxGenerationsPerTick,
                        @Setting("max-disk-loads-per-tick") int maxDiskLoadsPerTick,
                        @Setting("fake-chunks") FakeChunksConfig fakeChunks,
                        @Setting("occlusion-culling") OcclusionCullingConfig occlusionCulling) {
                @ConfigSerializable
                public record FakeChunksConfig(
                                boolean enabled,
                                @Setting("max-cached-packets") int maxCachedPackets,
                                @Setting("use-compression") boolean useCompression,
                                @Setting("enable-memory-cache") boolean enableMemoryCache,
                                @Setting("max-memory-cache-size") int maxMemoryCacheSize,
                                @Setting("surface-only-mode") boolean surfaceOnlyMode,
                                @Setting("depth-below-surface") int depthBelowSurface,
                                @Setting("disk-cache") boolean diskCache,
                                @Setting("enable-anti-xray") boolean enableAntiXray) {
                }

                @ConfigSerializable
                public record OcclusionCullingConfig(
                                boolean enabled,
                                @Setting("sky-light-threshold") int skyLightThreshold,
                                @Setting("max-y-level") int maxYLevel,
                                @Setting("min-y-level") int minYLevel) {
                }
        }

        @ConfigSerializable
        public record DatabaseConfig(boolean enabled, @Setting("file-name") String fileName) {
        }

        @ConfigSerializable
        public record IntegrationsConfig(PlaceholderIntegration placeholderapi, LuckPermsIntegration luckperms) {
                @ConfigSerializable
                public record PlaceholderIntegration(boolean enabled) {
                }

                @ConfigSerializable
                public record LuckPermsIntegration(boolean enabled, @Setting("check-interval") int checkInterval,
                                @Setting("use-group-permissions") boolean useGroupPermissions) {
                }
        }

        @ConfigSerializable
        public record MessagesConfig(@Setting("welcome-message") WelcomeMessage welcomeMessage) {
                @ConfigSerializable
                public record WelcomeMessage(boolean enabled) {
                }
        }

        @ConfigSerializable
        public record BandwidthSaverConfig(
                        boolean enabled,
                        @Setting("skip-redundant-packets") boolean skipRedundantPackets,
                        @Setting("max-fake-chunks-per-tick") int maxFakeChunksPerTick,
                        @Setting("max-bandwidth-per-player") int maxBandwidthPerPlayer,
                        @Setting("adaptive-rate-limiting") boolean adaptiveRateLimiting,
                        @Setting("bandwidth-profiles") java.util.Map<String, Integer> bandwidthProfiles,
                        @Setting("estimated-packet-size") int estimatedPacketSize) {
        }
}
