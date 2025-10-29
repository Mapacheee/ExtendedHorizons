package me.mapacheee.extendedhorizons.shared.config;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import me.mapacheee.extendedhorizons.integration.service.IPacketEventsService;
import me.mapacheee.extendedhorizons.integration.service.PacketEventsService;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkSenderService;
import me.mapacheee.extendedhorizons.viewdistance.service.IChunkSenderService;
import me.mapacheee.extendedhorizons.viewdistance.service.IViewDistanceService;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;

/**
 * Guice module for binding interfaces to their implementations
 * This resolves circular dependency issues by allowing Guice to create proxies
 */
public class ServiceBindingModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(IViewDistanceService.class).to(ViewDistanceService.class).in(Singleton.class);
        bind(IChunkSenderService.class).to(ChunkSenderService.class).in(Singleton.class);
        bind(IPacketEventsService.class).to(PacketEventsService.class).in(Singleton.class);
    }
}
