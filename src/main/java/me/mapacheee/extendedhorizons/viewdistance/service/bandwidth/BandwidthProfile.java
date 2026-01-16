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
            logger.info("Applied bandwidth profile to {}: {} KB/s", player.getName(), maxKbps);
        }
    }
}
