package me.mapacheee.extendedhorizons.viewdistance.service.packet;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.bandwidth.BandwidthController;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for processing the queue of pending packets for each
 * player.
 * It manages the asynchronous task that iterates through players and sends
 * packets
 * respecting bandwidth limits.
 */
@Service
public class PacketQueueProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PacketQueueProcessor.class);

    private final PlayerStateManager playerStateManager;
    private final BandwidthController bandwidthController;
    private final PacketSizeEstimator packetSizeEstimator;
    private final NMSPacketAccess nmsPacketAccess;
    private final ConfigService configService;

    private ScheduledTask packetSenderTask;

    @Inject
    public PacketQueueProcessor(
            PlayerStateManager playerStateManager,
            BandwidthController bandwidthController,
            PacketSizeEstimator packetSizeEstimator,
            NMSPacketAccess nmsPacketAccess,
            ConfigService configService) {
        this.playerStateManager = playerStateManager;
        this.bandwidthController = bandwidthController;
        this.packetSizeEstimator = packetSizeEstimator;
        this.nmsPacketAccess = nmsPacketAccess;
        this.configService = configService;
    }

    @OnEnable
    public void start() {
        startPacketSenderTask();
    }

    @OnDisable
    public void stop() {
        if (packetSenderTask != null) {
            packetSenderTask.cancel();
            packetSenderTask = null;
        }
    }

    /**
     * Starts a task that flushes pending packets to players
     */
    private void startPacketSenderTask() {
        this.packetSenderTask = Bukkit.getAsyncScheduler()
                .runAtFixedRate(ExtendedHorizonsPlugin.getInstance(), (task) -> {
                    processPacketQueues();
                }, 50L, 50L, TimeUnit.MILLISECONDS);
    }

    private void processPacketQueues() {
        List<UUID> playerIds = getPlayersWithPendingPackets();

        for (UUID uuid : playerIds) {
            processPlayerQueue(uuid);
        }
    }

    private List<UUID> getPlayersWithPendingPackets() {
        List<UUID> playerIds = new ArrayList<>();
        for (UUID playerId : playerStateManager.getAllPlayerIds()) {
            PlayerChunkState state = playerStateManager.get(playerId).orElse(null);
            if (state != null && !state.getPendingPackets().isEmpty()) {
                playerIds.add(playerId);
            }
        }
        return playerIds;
    }

    private void processPlayerQueue(UUID uuid) {
        PlayerChunkState state = playerStateManager.get(uuid).orElse(null);
        if (state == null) {
            return;
        }

        Queue<Object> queue = state.getPendingPackets();
        if (queue.isEmpty()) {
            return;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            playerStateManager.remove(uuid);
            return;
        }

        try {
            int ping = player.getPing();
            state.setAvgPing(ping);

            int maxPacketsThisTick = bandwidthController.calculateMaxPackets(ping);

            // Reset second counters if needed
            if (state.shouldResetSecondCounters()) {
                state.resetSecondCounters();
            }

            long bytesSent = state.getBytesThisSecond();
            int maxBandwidth = configService.get().bandwidthSaver().maxBandwidthPerPlayer();
            boolean bandwidthExceeded = maxBandwidth > 0 && bytesSent >= (maxBandwidth * 1024L);

            int count = 0;
            while (!queue.isEmpty() && count < maxPacketsThisTick && !bandwidthExceeded) {
                Object packet = queue.poll();
                if (packet == null)
                    continue;

                nmsPacketAccess.sendPacket(player, packet);
                count++;

                long packetSize = packetSizeEstimator.estimatePacketSize(packet);
                bytesSent += packetSize;

                // Update state metrics
                state.setBytesThisSecond(bytesSent);
                state.addActualBytesSent(packetSize);
                state.updateAvgPacketSize(packetSize);

                if (maxBandwidth > 0 && bytesSent >= (maxBandwidth * 1024L)) {
                    bandwidthExceeded = true;
                }
            }
        } catch (Exception e) {
            logger.error("[EH] Error sending packets to " + player.getName(), e);
            playerStateManager.remove(uuid);
        }
    }
}
