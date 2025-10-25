package me.mapacheee.extendedhorizons;

import com.github.retrooper.packetevents.PacketEvents;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.mapacheee.extendedhorizons.integration.service.PlaceholderAPIProvider;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

/* ExtendedHorizons Plugin - main class that initializes the Winter Framework
 * and sets up PacketEvents integration for advanced view distance management
 */

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

    private static ExtendedHorizonsPlugin instance;

    public static <T> T getService(Class<T> type) {
        return instance.injector.getInstance(type);
    }

    public static ExtendedHorizonsPlugin getInstance() {
        return instance;
    }

    @Override
    public void onPluginLoad() {
        super.onPluginLoad();
        instance = this;
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        getSLF4JLogger().info("ExtendedHorizons loaded successfully!");
    }

    @Override
    public void onPluginEnable() {
        super.onPluginEnable();
        PacketEvents.getAPI().init();
        if (PacketEvents.getAPI().isInitialized()) {
            getSLF4JLogger().info("ExtendedHorizons enabled successfully!");
            getSLF4JLogger().info("Using PacketEvents version: {}", PacketEvents.getAPI().getVersion());
        } else {
            getSLF4JLogger().error("PacketEvents plugin not found or not initialized! Please install PacketEvents plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            ConfigService configService = getService(ConfigService.class);
            ViewDistanceService viewDistanceService = getService(ViewDistanceService.class);
            Logger logger = getSLF4JLogger();
            PlaceholderAPIProvider provider = new PlaceholderAPIProvider(logger, configService, viewDistanceService);
            provider.tryRegister();
        } catch (Throwable e) {
            getSLF4JLogger().warn("PlaceholderAPI not found. Skipping placeholder hook.");
        }

        detectServerType();
    }

    @Override
    public void onPluginDisable() {
        super.onPluginDisable();
        PacketEvents.getAPI().terminate();
        getSLF4JLogger().info("ExtendedHorizons disabled successfully!");
    }


    private void detectServerType() {
        Logger logger = getSLF4JLogger();

        try {
            String serverVersion = Bukkit.getVersion().toLowerCase();
            String serverName = Bukkit.getName().toLowerCase();

            if (serverVersion.contains("folia") || serverName.contains("folia")) {
                logger.info("Detected Folia server - enabling regional threading optimizations");
            } else if (serverVersion.contains("paper") || serverName.contains("paper")) {
                logger.info("Detected Paper server - enabling Paper-specific optimizations");
            } else {
                logger.info("Detected Spigot/CraftBukkit server - using standard optimizations");
            }
        } catch (Exception e) {
            logger.warn("Could not detect server type, using standard optimizations", e);
        }
    }
}
