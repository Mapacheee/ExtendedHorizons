package me.mapacheee.extendedhorizons.integration.craftengine;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

/*
 *   Detects and provides compatibility with CraftEngine plugin
 *   CraftEngine uses Netty to intercept chunk packets and add custom blocks
 *   We ensure our fake chunks are sent through PacketEvents so CraftEngine can process them
 */
@Service
public class CraftEngineService {

    @Inject
    private Logger logger;

    private final ConfigService configService;
    private boolean craftEnginePresent = false;

    @Inject
    public CraftEngineService(ConfigService configService) {
        this.configService = configService;
    }

    public void enable() {
        if (!configService.get().integrations().craftEngine().enabled()) {
            return;
        }

        craftEnginePresent = Bukkit.getPluginManager().getPlugin("CraftEngine") != null;

        if (craftEnginePresent) {
            logger.info("CraftEngine detected - chunk interception compatibility enabled");
        }
    }

    /**
     * Checks if CraftEngine is present and enabled
     */
    public boolean isCraftEnginePresent() {
        return craftEnginePresent && configService.get().integrations().craftEngine().enabled();
    }

    /**
     * Returns whether we should use PacketEvents for chunk sending
     * This ensures CraftEngine can intercept and modify our chunks
     */
    public boolean shouldUsePacketEventsForChunks() {
        return isCraftEnginePresent();
    }
}
