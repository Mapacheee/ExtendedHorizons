package me.mapacheee.extendedhorizons.viewdistance.service.player;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;

/**
 * Manages the warmup period for fake chunk loading.
 * Typically triggered after a teleport or join to prevent immediate load
 * spikes.
 */
@Service
public class WarmupManager {

    private final ConfigService configService;

    @Inject
    public WarmupManager(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Starts a new warmup period for the given player state.
     * Often used when a player teleports significantly.
     * 
     * @param state The player's chunk state
     */
    public void startWarmup(PlayerChunkState state) {
        state.setWarmupStartTime(System.currentTimeMillis());
        state.setInWarmup(true);
        state.getChunkQueue().clear(); // Clear old chunks as they might be irrelevant
    }

    /**
     * Checks if the player is currently in a warmup period.
     * The warmup is considered active if the time since start is less than the
     * configured delay.
     * 
     * @param state The player's chunk state
     * @return true if warmup is active
     */
    public boolean isWarmupActive(PlayerChunkState state) {
        long warmupDelay = configService.get().performance().teleportWarmupDelay();
        if (warmupDelay <= 0) {
            state.setInWarmup(false);
            return false;
        }
        boolean isActive = System.currentTimeMillis() - state.getWarmupStartTime() < warmupDelay;

        state.setInWarmup(isActive);

        return isActive;
    }
}
