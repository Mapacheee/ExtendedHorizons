package me.mapacheee.extendedhorizons.viewdistance.service.bandwidth;

import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import java.util.UUID;

/**
 * Manages bandwidth allocation and rate limiting for fake chunk packet sending.
 * 
 * This controller centralizes all bandwidth-related logic including:
 * - Per-tick bandwidth limits
 * - Per-second bandwidth limits
 * - Adaptive rate limiting based on player ping
 * - Packet size tracking and statistics
 * 
 * Thread-safe via PlayerStateManager delegation.
 */
@Service
public class BandwidthController {

    private final PlayerStateManager playerStateManager;
    private final ConfigService configService;

    /**
     * Maximum bytes that can be sent per tick (50ms) to a single player (Default).
     * Defaults to 25KB (500KB/s / 20 ticks).
     * Updated dynamically from config.
     */
    private volatile long defaultMaxBytesPerTick = 25000;

    @Inject
    public BandwidthController(PlayerStateManager playerStateManager, ConfigService configService) {
        this.playerStateManager = playerStateManager;
        this.configService = configService;
    }

    /**
     * Calculates the maximum number of packets that can be sent this tick
     * based on the player's ping and adaptive rate limiting configuration.
     * 
     * @param ping Player's current ping in milliseconds
     * @return Maximum number of packets to send this tick
     */
    public int calculateMaxPackets(int ping) {
        if (!configService.get().bandwidthSaver().adaptiveRateLimiting()) {
            return 50; // Default: 50 packets per tick (1000 packets/second)
        }

        // Adaptive rate limiting based on ping
        if (ping < 50) {
            return 100; // Low ping: send more packets
        } else if (ping < 100) {
            return 50; // Medium ping: standard rate
        } else if (ping < 200) {
            return 25; // High ping: reduce rate
        } else {
            return 10; // Very high ping: minimal rate
        }
    }

    /**
     * Checks if a player can send more data this tick without exceeding bandwidth
     * limits.
     * 
     * @param playerId       The player's UUID
     * @param estimatedBytes Estimated size of data to send (in bytes)
     * @return true if the player can send the data, false if bandwidth limit would
     *         be exceeded
     */
    public boolean canSendData(UUID playerId, long estimatedBytes) {

        PlayerChunkState state = playerStateManager.getOrCreate(playerId);

        long bytesUsedThisTick = state.getBytesThisTick();

        long limit = state.getMaxBytesPerTick();
        if (limit <= 0) {
            limit = defaultMaxBytesPerTick;
        }

        return bytesUsedThisTick + estimatedBytes < limit;
    }

    /**
     * Checks if a player can send more data this second without exceeding
     * per-second bandwidth limits.
     * 
     * @param playerId The player's UUID
     * @return true if the player hasn't exceeded their per-second bandwidth limit
     */
    public boolean canSendDataThisSecond(UUID playerId) {
        PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
        if (state == null) {
            return true;
        }

        long bytesSent = state.getBytesThisSecond();

        long maxBandwidthBytes = state.getMaxBytesPerSecond();

        if (maxBandwidthBytes <= 0) {
            int maxBandwidthKB = configService.get().bandwidthSaver().maxBandwidthPerPlayer();
            maxBandwidthBytes = maxBandwidthKB * 1024L;
        }

        return maxBandwidthBytes <= 0 || bytesSent < maxBandwidthBytes;
    }

    /**
     * Records that data was sent to a player, updating bandwidth tracking
     * statistics.
     * 
     * @param playerId    The player's UUID
     * @param actualBytes Actual size of data sent (in bytes)
     */
    public void recordDataSent(UUID playerId, long actualBytes) {
        PlayerChunkState state = playerStateManager.getOrCreate(playerId);

        // Update per-second bandwidth tracking
        long currentSecondBytes = state.getBytesThisSecond() + actualBytes;
        state.setBytesThisSecond(currentSecondBytes);

        // Update total bytes sent
        state.addActualBytesSent(actualBytes);

        // Update average packet size (exponential moving average)
        state.updateAvgPacketSize(actualBytes);
    }

    /**
     * Gets the current global maximum bytes per tick limit.
     * 
     * @return Maximum bytes that can be sent per tick
     */
    public long getDefaultMaxBytesPerTick() {
        return defaultMaxBytesPerTick;
    }

    /**
     * Updates the default maximum bytes per tick based on configured bandwidth per
     * player.
     * 
     * @param bandwidthPerPlayerKB Bandwidth limit per player in KB/s
     */
    public void updateMaxBytesPerTick(int bandwidthPerPlayerKB) {
        if (bandwidthPerPlayerKB > 0) {
            // Convert KB/s to bytes/tick (20 ticks per second)
            this.defaultMaxBytesPerTick = (bandwidthPerPlayerKB * 1024) / 20;
        }
    }

    /**
     * Sets a specific bandwidth limit for a player.
     * 
     * @param playerId           The player UUID
     * @param kilobytesPerSecond The limit in KB/s. Set to -1 to use default.
     */
    public void setPlayerBandwidth(UUID playerId, int kilobytesPerSecond) {
        PlayerChunkState state = playerStateManager.getOrCreate(playerId);
        if (kilobytesPerSecond <= 0) {
            state.setMaxBytesPerTick(-1);
            state.setMaxBytesPerSecond(-1);
        } else {
            long bytesPerSec = kilobytesPerSecond * 1024L;
            state.setMaxBytesPerSecond(bytesPerSec);
            state.setMaxBytesPerTick(bytesPerSec / 20);
        }
    }
}
