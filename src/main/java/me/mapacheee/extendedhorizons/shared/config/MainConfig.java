package me.mapacheee.extendedhorizons.shared.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/*
 * Represents the main 'config.yml' structure.
 * Only contains the keys present in the shipped config.yml.
 */
@ConfigSerializable
@Configurate("config")
public record MainConfig(
        ViewDistanceConfig viewDistance,
        PerformanceConfig performance,
        DatabaseConfig database,
        IntegrationsConfig integrations,
        MessagesConfig messages
) {
    @ConfigSerializable
    public record ViewDistanceConfig(int maxDistance, int minDistance, int defaultDistance) {}

    @ConfigSerializable
    public record PerformanceConfig(
            int maxChunksPerTick,
            FakeChunksConfig fakeChunks
    ) {
        @ConfigSerializable
        public record FakeChunksConfig(
                boolean enabled,
                int maxCachedPackets,
                boolean useCompression,
                int cacheCleanupInterval
        ) {}
    }

    @ConfigSerializable
    public record DatabaseConfig(boolean enabled, String fileName) {}

    @ConfigSerializable
    public record IntegrationsConfig(PlaceholderIntegration placeholderapi, LuckPermsIntegration luckperms, CraftEngineIntegration craftEngine) {
        @ConfigSerializable
        public record PlaceholderIntegration(boolean enabled) {}
        @ConfigSerializable
        public record LuckPermsIntegration(boolean enabled, int checkInterval, boolean useGroupPermissions) {}
        @ConfigSerializable
        public record CraftEngineIntegration(boolean enabled) {}
    }

    @ConfigSerializable
    public record MessagesConfig(WelcomeMessage welcomeMessage) {
        @ConfigSerializable
        public record WelcomeMessage(boolean enabled) {}
    }
}
