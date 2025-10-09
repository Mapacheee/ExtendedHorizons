/* ExtendedHorizons Plugin - Main class that initializes the Winter Framework
 * and sets up PacketEvents integration for advanced view distance management
 */
package me.mapacheee.extendedhorizons;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.slf4j.Logger;

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

        initializePacketEvents();
        getSLF4JLogger().info("ExtendedHorizons loaded successfully!");
    }

    @Override
    public void onPluginEnable() {
        super.onPluginEnable();

        PacketEvents.getAPI().init();
        getSLF4JLogger().info("ExtendedHorizons enabled successfully!");
        getSLF4JLogger().info("Running on server version: {}", PacketEvents.getAPI().getServerManager().getVersion());

        detectServerType();
    }

    @Override
    public void onPluginDisable() {
        super.onPluginDisable();

        if (PacketEvents.getAPI().isInitialized()) {
            PacketEvents.getAPI().terminate();
        }
        getSLF4JLogger().info("ExtendedHorizons disabled successfully!");
    }

    private void initializePacketEvents() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));

        PacketEventsSettings settings = PacketEvents.getAPI().getSettings();
        settings
            .debug(false)
            .bStats(false)
            .checkForUpdates(false)
            .kickOnPacketException(false);
    }

    private void detectServerType() {
        Logger logger = getSLF4JLogger();

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            logger.info("Detected Folia server - enabling regional threading optimizations");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
                logger.info("Detected Paper server - enabling Paper-specific optimizations");
            } catch (ClassNotFoundException ex) {
                logger.info("Detected Spigot/CraftBukkit server - using standard optimizations");
            }
        }
    }
}
