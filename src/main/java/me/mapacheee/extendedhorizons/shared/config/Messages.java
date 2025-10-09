/* Messages record for ExtendedHorizons plugin
 * Contains all translatable messages with HEX color support
 */
package me.mapacheee.extendedhorizons.shared.config;

public record Messages(
    String prefix,
    GeneralMessages general,
    ViewDistanceMessages viewDistance,
    PerformanceMessages performance,
    StatsMessages stats,
    DebugMessages debug,
    ErrorMessages errors,
    HelpMessages help,
    WorldMessages world,
    IntegrationMessages integrations,
    StartupMessages startup,
    ShutdownMessages shutdown
) {
    public record GeneralMessages(
        String noPermission,
        String playerNotFound,
        String configReloaded,
        String pluginInfo,
        String unknownCommand
    ) {}

    public record ViewDistanceMessages(
        String currentDistance,
        String distanceChanged,
        String distanceSetOther,
        String distanceReset,
        String maxDistanceExceeded,
        String minDistanceNotMet,
        String permissionRequired,
        String fakeChunksEnabled,
        String fakeChunksDisabled
    ) {}

    public record PerformanceMessages(
        String lowTpsWarning,
        String performanceRestored,
        String networkLimitReached,
        String chunkGenerationLimit,
        String adaptiveModeEnabled,
        String adaptiveModeDisabled
    ) {}

    public record StatsMessages(
        String header,
        String playersOnline,
        String averageDistance,
        String chunksSent,
        String fakeChunksSent,
        String networkUsage,
        String cacheSize,
        String serverTps,
        String workerThreads,
        String footer
    ) {}

    public record DebugMessages(
        String enabled,
        String disabled,
        String chunkInfo,
        String playerInfo,
        String performanceInfo
    ) {}

    public record ErrorMessages(
        String databaseError,
        String networkError,
        String chunkGenerationFailed,
        String permissionCheckFailed,
        String configError,
        String packeteventsError
    ) {}

    public record HelpMessages(
        String header,
        String info,
        String distance,
        String reset,
        String reload,
        String stats,
        String debug,
        String world,
        String footer
    ) {}

    public record WorldMessages(
        String distanceSet,
        String notFound,
        String disabled,
        String performanceModeChanged
    ) {}

    public record IntegrationMessages(
        String placeholderapiEnabled,
        String placeholderapiDisabled,
        String luckpermsEnabled,
        String luckpermsDisabled
    ) {}

    public record StartupMessages(
        String loading,
        String loaded,
        String enabled,
        String serverDetected,
        String packeteventsInitialized
    ) {}

    public record ShutdownMessages(
        String disabling,
        String disabled,
        String savingData,
        String dataSaved
    ) {}
}
