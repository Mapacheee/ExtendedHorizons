package me.mapacheee.extendedhorizons.shared.config;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.thewinterframework.module.annotation.ModuleComponent;
import com.thewinterframework.plugin.module.PluginModule;
import com.thewinterframework.plugin.WinterPlugin;
import me.mapacheee.extendedhorizons.integration.service.IPacketEventsService;
import me.mapacheee.extendedhorizons.integration.service.ILuckPermsIntegrationService;
import me.mapacheee.extendedhorizons.integration.service.NoOpLuckPermsIntegrationService;
import me.mapacheee.extendedhorizons.integration.service.PacketEventsService;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import me.mapacheee.extendedhorizons.shared.storage.ViewDataStorage;
import me.mapacheee.extendedhorizons.viewdistance.service.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
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

        Injector injector = plugin.getInjector();
        ViewDistanceService viewDistanceService = (ViewDistanceService) injector.getInstance(IViewDistanceService.class);
        IChunkSenderService chunkSenderService = injector.getInstance(IChunkSenderService.class);

        viewDistanceService.setChunkSenderService(chunkSenderService);
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
            ILuckPermsIntegrationService luckPermsService,
            PerformanceMonitorService performanceMonitor,
            ViewDataStorage storage
    ) {
        ViewDistanceService service = new ViewDistanceService(logger, configService, playerViewService, luckPermsService, performanceMonitor, storage);
        logger.info("ViewDistanceService created");
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
        ChunkSenderService service = new ChunkSenderService(logger, configService, playerViewService, cacheService, packetEventsService);
        logger.info("ChunkSenderService created");
        return service;
    }

    @Provides
    @Singleton
    public IPacketEventsService providePacketEventsService(
            Logger logger,
            WinterPlugin plugin
    ) {
        PacketEventsService service = new PacketEventsService(logger, (me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin) plugin);
        logger.info("PacketEventsService created");
        return service;
    }

    @Provides
    @Singleton
    public ILuckPermsIntegrationService provideLuckPermsIntegrationService(
            Logger logger,
            ConfigService configService
    ) {
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms != null && luckPerms.isEnabled()) {
            try {
                Class<?> clazz = Class.forName("me.mapacheee.extendedhorizons.integration.service.LuckPermsIntegrationService");
                return (ILuckPermsIntegrationService) clazz.getConstructor(Logger.class, ConfigService.class).newInstance(logger, configService);
            } catch (Exception e) {
                logger.warn("Failed to load LuckPerms integration: " + e.getMessage());
                return new NoOpLuckPermsIntegrationService(configService);
            }
        } else {
            return new NoOpLuckPermsIntegrationService(configService);
        }
    }
}
