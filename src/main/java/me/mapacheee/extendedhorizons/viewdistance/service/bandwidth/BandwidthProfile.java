package me.mapacheee.extendedhorizons.viewdistance.service.bandwidth;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@ListenerComponent
public class BandwidthProfile implements Listener {

    private final BandwidthController bandwidthController;
    private final ConfigService configService;
    private static final Logger logger = LoggerFactory.getLogger(BandwidthProfile.class);

    @Inject
    public BandwidthProfile(BandwidthController bandwidthController, ConfigService configService) {
        this.bandwidthController = bandwidthController;
        this.configService = configService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyProfile(event.getPlayer());
    }

    @Inject
    public void init() {
        int defaultBw = configService.get().bandwidthSaver().maxBandwidthPerPlayer();
        logger.info("[EH] BandwidthProfile initialized. Default limit: {} KB/s", defaultBw);
        if (defaultBw > 2000) {
            logger.warn(
                    "[EH] WARNING: Default bandwidth is set to {} KB/s (High!). Usage: {} players -> {} MB/s potential usage.",
                    defaultBw, 50, (50 * defaultBw) / 1024);
        }
    }

    private void applyProfile(Player player) {
        Map<String, Integer> profiles = configService.get().bandwidthSaver().bandwidthProfiles();

        if (profiles == null || profiles.isEmpty()) {
            return;
        }

        int maxKbps = -1;

        for (Map.Entry<String, Integer> entry : profiles.entrySet()) {
            String profileName = entry.getKey();
            int kbps = entry.getValue();
            String permission = "extendedhorizons.bandwidth." + profileName;

            if (player.hasPermission(permission)) {
                if (kbps > maxKbps) {
                    maxKbps = kbps;
                }
            }
        }

        if (maxKbps > 0) {
            bandwidthController.setPlayerBandwidth(player.getUniqueId(), maxKbps);
            logger.info(
                    "[EH] Applied bandwidth profile to {}: {} KB/s (Permission: extendedhorizons.bandwidth.<profile>)",
                    player.getName(), maxKbps);

            int defaultBw = configService.get().bandwidthSaver().maxBandwidthPerPlayer();
            if (defaultBw > 0 && maxKbps < defaultBw) {
                logger.warn(
                        "[EH] Configuration Alert: Player {} has profile limit ({} KB/s) LOWER than default ({} KB/s). They will be throttled!",
                        player.getName(), maxKbps, defaultBw);
            }
        } else {
            // Log for default users if debug or first time
            // logger.info("[EH] No specific profile for {}, using default.",
            // player.getName());
        }
    }
}
