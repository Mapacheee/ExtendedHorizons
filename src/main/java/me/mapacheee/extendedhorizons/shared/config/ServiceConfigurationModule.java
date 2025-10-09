package me.mapacheee.extendedhorizons.shared.config;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.thewinterframework.module.annotation.ModuleComponent;
import com.thewinterframework.plugin.module.PluginModule;
import com.thewinterframework.plugin.WinterPlugin;
import me.mapacheee.extendedhorizons.integration.service.IPacketEventsService;
import me.mapacheee.extendedhorizons.integration.service.LuckPermsIntegrationService;
import me.mapacheee.extendedhorizons.integration.service.PacketEventsService;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import me.mapacheee.extendedhorizons.viewdistance.service.*;
import org.slf4j.Logger;

/**
 * Winter module for configuring service bindings
 * Integrates the Guice binding module with Winter framework
 */
@ModuleComponent
public class ServiceConfigurationModule implements PluginModule {

    @Override
    public boolean onLoad(WinterPlugin plugin) {
        plugin.getSLF4JLogger().info("ServiceConfigurationModule loaded");
        return true;
    }

    @Override
    public boolean onEnable(WinterPlugin plugin) {
        plugin.getSLF4JLogger().info("ServiceConfigurationModule enabled");
        return true;
    }

    @Override
    public boolean onDisable(WinterPlugin plugin) {
        plugin.getSLF4JLogger().info("ServiceConfigurationModule disabled");
        return true;
    }

    @Provides
    @Singleton
    public IViewDistanceService provideViewDistanceService(
            Logger logger,
            ConfigService configService,
            PlayerViewService playerViewService,
            LuckPermsIntegrationService luckPermsService,
            PerformanceMonitorService performanceMonitor
    ) {
        ViewDistanceService service = new ViewDistanceService(logger, configService, playerViewService, luckPermsService, performanceMonitor);
        return service;
    }

    @Provides
    @Singleton
    public IChunkSenderService provideChunkSenderService(
            Logger logger,
            ConfigService configService,
            PlayerViewService playerViewService,
            me.mapacheee.extendedhorizons.optimization.service.CacheService cacheService,
            IPacketEventsService packetEventsService
    ) {
        return new ChunkSenderService(logger, configService, playerViewService, cacheService, packetEventsService);
    }

    @Provides
    @Singleton
    public IPacketEventsService providePacketEventsService(
            Logger logger,
            WinterPlugin plugin
    ) {
        return new PacketEventsService(logger, (me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin) plugin);
    }
}
