package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 *   Listens player movement and triggers view updates
 *   Uses throttling to prevent excessive updates
*/
@ListenerComponent
public class PlayerMovementListener implements Listener {

    private final Provider<ViewDistanceService> viewDistanceServiceProvider;
    private final Map<UUID, Long> lastUpdateTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChunkPos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fastMoveCount = new ConcurrentHashMap<>();

    private static final long UPDATE_COOLDOWN_MS = 500;
    private static final long FAST_MOVE_COOLDOWN_MS = 500;
    private static final int FAST_MOVE_THRESHOLD = 3;

    @Inject
    public PlayerMovementListener(Provider<ViewDistanceService> viewDistanceServiceProvider) {
        this.viewDistanceServiceProvider = viewDistanceServiceProvider;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null)
            return;

        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockY() == event.getTo().getBlockY() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        int fromChunkX = event.getFrom().getBlockX() >> 4;
        int fromChunkZ = event.getFrom().getBlockZ() >> 4;
        int toChunkX = event.getTo().getBlockX() >> 4;
        int toChunkZ = event.getTo().getBlockZ() >> 4;

        UUID playerId = event.getPlayer().getUniqueId();
        long currentChunkPos = ((long) toChunkZ << 32) | (toChunkX & 0xFFFFFFFFL);
        Long lastChunk = lastChunkPos.get(playerId);

        if (lastChunk != null && lastChunk == currentChunkPos) {
            Long lastUpdate = lastUpdateTime.get(playerId);
            if (lastUpdate != null && System.currentTimeMillis() - lastUpdate < UPDATE_COOLDOWN_MS) {
                return;
            }
        }

        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) {
            return;
        }

        lastChunkPos.put(playerId, currentChunkPos);

        int dX = Math.abs(toChunkX - fromChunkX);
        int dZ = Math.abs(toChunkZ - fromChunkZ);
        int cheb = Math.max(dX, dZ);

        if (cheb >= FAST_MOVE_THRESHOLD) {
            int count = fastMoveCount.getOrDefault(playerId, 0) + 1;
            fastMoveCount.put(playerId, count);
        } else {
            fastMoveCount.put(playerId, 0);
        }

        Long lastUpdate = lastUpdateTime.get(playerId);
        long now = System.currentTimeMillis();

        int currentFastMoveCount = fastMoveCount.getOrDefault(playerId, 0);
        long cooldown = currentFastMoveCount > 2 ? FAST_MOVE_COOLDOWN_MS : 250;

        if (lastUpdate != null && now - lastUpdate < cooldown) {
            return;
        }
        lastUpdateTime.put(playerId, now);

        ViewDistanceService viewDistanceService = viewDistanceServiceProvider.get();

        if (cheb >= 3) {
            viewDistanceService.updatePlayerViewFast(event.getPlayer());
            if (currentFastMoveCount < 3) {
                event.getPlayer().getScheduler().runDelayed(
                        ExtendedHorizonsPlugin.getPlugin(ExtendedHorizonsPlugin.class),
                        (task) -> {
                            if (event.getPlayer().isOnline())
                                viewDistanceServiceProvider.get().updatePlayerView(event.getPlayer());
                        },
                        null,
                        5L);
            }
        } else {
            viewDistanceService.updatePlayerView(event.getPlayer());
        }
    }

    /**
     * Cleans up player tracking data on quit
     */
    public void cleanupPlayer(UUID playerId) {
        lastUpdateTime.remove(playerId);
        lastChunkPos.remove(playerId);
        fastMoveCount.remove(playerId);
    }
}
