package me.mapacheee.extendedhorizons;

import com.github.retrooper.packetevents.PacketEvents;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

@WinterBootPlugin
public class ExtendedHorizonsPlugin extends PaperWinterPlugin {

    private static ExtendedHorizonsPlugin instance;

    public static ExtendedHorizonsPlugin getInstance() {
        return instance;
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
            PacketEvents.getAPI().terminate();
        } catch (Throwable ignored) {
        }
        super.onPluginDisable();
    }
}
