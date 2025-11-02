package me.mapacheee.extendedhorizons.shared.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import java.util.List;

/*
 * Represents the 'messages.yml' file structure.
 */
@ConfigSerializable
@Configurate("messages")
public record MessageConfig(
        String prefix,
        General general,
        ViewDistance viewDistance,
        List<String> stats,
        Errors errors,
        List<String> help,
        World world,
        Integrations integrations,
        Startup startup,
        Messages messages,
        Additional additional
) {
    @ConfigSerializable
    public record General(String noPermission, String playerNotFound, String playerOnly, String configReloaded, String configError, String pluginInfo, String unknownCommand) {}

    @ConfigSerializable
    public record ViewDistance(
            String currentDistance,
            String distanceChanged,
            String distanceSetOther,
            String otherCurrentDistance,
            String noViewData,
            String noViewDataOther,
            String reset,
            String maxDistanceExceeded,
            String minDistanceError,
            String invalidDistance
    ) {}

    @ConfigSerializable
    public record Errors(String databaseError, String networkError, String chunkGenerationFailed, String permissionCheckFailed, String packeteventsError) {}

    @ConfigSerializable
    public record World(String notFound, String usage, String configNotice, String distanceSet, String disabled, String performanceModeChanged, String maxDistanceInfo) {}

    @ConfigSerializable
    public record Integrations(String placeholderapiEnabled, String placeholderapiDisabled, String luckpermsEnabled, String luckpermsDisabled) {}

    @ConfigSerializable
    public record Startup(String loading, String loaded, String enabled, String serverDetected, String packeteventsInitialized) {}

    @ConfigSerializable
    public record Messages(WelcomeMessage welcomeMessage) {
        @ConfigSerializable
        public record WelcomeMessage(String text) {}
    }

    @ConfigSerializable
    public record Additional(String noViewData, String distanceSetOther, String minDistanceError) {}
}
