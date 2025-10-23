package me.mapacheee.extendedhorizons.integration.service;

import org.bukkit.entity.Player;

/**
 * Interface for LuckPerms integration service
 */
public interface ILuckPermsIntegrationService {

    /**
     * Gets the maximum view distance for a player based on permissions
     * @param player the player
     * @return the max view distance
     */
    int getMaxViewDistance(Player player);

    /**
     * Invalidates the cache for a specific player
     * @param player the player
     */
    void invalidatePlayerCache(Player player);

    /**
     * Invalidates all cached permissions
     */
    void invalidateAllCache();
}
