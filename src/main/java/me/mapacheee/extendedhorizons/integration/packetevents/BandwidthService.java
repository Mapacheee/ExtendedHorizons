package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BandwidthService {

    private static final Logger logger = LoggerFactory.getLogger(BandwidthService.class);
    private final ConfigService configService;
    private PacketFilter packetFilter;

    @Inject
    public BandwidthService(ConfigService configService) {
        this.configService = configService;
    }

    @OnEnable
    public void onEnable() {
        if (configService.get().bandwidthSaver().enabled()) {
            this.packetFilter = new PacketFilter(configService);
            PacketEvents.getAPI().getEventManager().registerListener(packetFilter);
            logger.info("[EH] Bandwidth Saver enabled");
        }
    }

    @OnDisable
    public void onDisable() {
        if (packetFilter != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetFilter);
            logger.info("[EH] Bandwidth Saver disabled and listener unregistered");
        }
    }
}
