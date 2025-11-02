package me.mapacheee.extendedhorizons;

import com.github.retrooper.packetevents.PacketEvents;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.mapacheee.extendedhorizons.integration.placeholderapi.PlaceholderService;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import org.bukkit.Bukkit;

/*
 * Main plugin class for ExtendedHorizons.
 * This class is responsible for bootstrapping the plugin and managing its lifecycle.
 * It handles integrations with other plugins.
 */
@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {
    private static ExtendedHorizonsPlugin instance;

    public static <T> T getService(Class<T> type) {
        return instance.injector.getInstance(type);
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
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getService(PlaceholderService.class).register();
        }
        else {
            getLogger().warning("PlaceholderAPI is not installed. Placeholders will not work.");
        }

        PacketEvents.getAPI().init();

    }

    @Override
    public void onPluginDisable() {
        try {
            getService(FakeChunkService.class).shutdown();
        } catch (Exception e) {
            getLogger().warning("Failed to shutdown FakeChunkService: " + e.getMessage());
        }

        super.onPluginDisable();
        PacketEvents.getAPI().terminate();
    }
}
