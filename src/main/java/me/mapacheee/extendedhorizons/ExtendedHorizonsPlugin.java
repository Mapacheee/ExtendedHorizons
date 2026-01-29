package me.mapacheee.extendedhorizons;

import com.github.retrooper.packetevents.PacketEvents;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Main plugin class for ExtendedHorizons.
 * This class is responsible for bootstrapping the plugin and managing its lifecycle.
 * It handles integrations with other plugins.
 */
@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {
    private static final Logger logger = LoggerFactory.getLogger(ExtendedHorizonsPlugin.class);
    private static ExtendedHorizonsPlugin instance;

    public static ExtendedHorizonsPlugin getInstance() {
        return instance;
    }

    public static <T> T getService(Class<T> type) {
        return instance.injector.getInstance(type);
    }

    @Override
    protected Injector createInjector() {
        return com.google.inject.Guice.createInjector(Stage.PRODUCTION, getGuiceModules());
    }

    @Override
    public void onPluginLoad() {
        super.onPluginLoad();
        instance = this;
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onPluginEnable() {
            super.onPluginEnable();
            PacketEvents.getAPI().init();
    }

    @Override
    public void onPluginDisable() {
        try {
            getService(FakeChunkService.class).shutdown();
        } catch (Exception e) {
            logger.warn("Failed to shutdown FakeChunkService: " + e.getMessage());
        }

        super.onPluginDisable();
        PacketEvents.getAPI().terminate();
    }
}
