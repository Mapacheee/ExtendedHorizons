package me.mapacheee.extendedhorizons.integration.service;

import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import org.bukkit.entity.Player;

/**
 * No-op implementation of LuckPerms integration service when LuckPerms is not available
 */
public class NoOpLuckPermsIntegrationService implements ILuckPermsIntegrationService {

    private final ConfigService configService;

    public NoOpLuckPermsIntegrationService(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public int getMaxViewDistance(Player player) {
        int worldMax = configService.getMaxViewDistanceForWorld(player.getWorld().getName());
        int globalMax = configService.getMaxViewDistance();
        return Math.min(worldMax, globalMax);
    }

    @Override
    public void invalidatePlayerCache(Player player) {
        // No-op
    }

    @Override
    public void invalidateAllCache() {
        // No-op
    }
}
