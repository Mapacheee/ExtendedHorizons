package me.mapacheee.extendedhorizons.viewdistance.service;

import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Interface for View Distance Service - Core service managing player view distances and chunk sending
 * Handles real and fake chunk management with performance optimizations
 */
public interface IViewDistanceService {

    void initializePlayerView(Player player);

    void removePlayerView(Player player);

    void updatePlayerView(Player player);

    void setViewDistance(Player player, int distance);

    int getViewDistance(Player player);

    int getEffectiveViewDistance(Player player);

    void setGlobalPause(boolean paused);

    boolean isGlobalPaused();

    void setAdaptiveMode(boolean enabled);

    boolean isAdaptiveMode();

    PlayerView getPlayerView(UUID playerId);

    Collection<PlayerView> getAllPlayerViews();

    void handleChunkLoad(Player player, ViewMap.ChunkCoordinate coordinate);

    void handleChunkUnload(Player player, ViewMap.ChunkCoordinate coordinate);

    int getTotalChunksSent();

    int getTotalFakeChunksSent();

    void resetStats();

    void updatePlayerPermissions(Player player);
}
