package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.integration.luckperms.LuckPermsService;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.service.MessageService;
import me.mapacheee.extendedhorizons.shared.storage.PlayerStorageService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.listener.PlayerMovementListener;

import org.bukkit.entity.Player;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.mapacheee.extendedhorizons.shared.storage.PlayerData;
import me.mapacheee.extendedhorizons.shared.config.MainConfig.WorldConfig;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.event.FakeChunkUnloadEvent;

/*
 *   Manages extended view distance with dual system:
 *   - Real chunks (0 to server view-distance): Handled by server naturally
 *   - Fake chunks (beyond server view-distance): Sent from packet cache
 */
@Service
public class ViewDistanceService {

    private final Map<UUID, PlayerView> playerViews = new ConcurrentHashMap<>();
    private final ConfigService configService;
    private final PlayerStorageService storageService;
    private final ChunkService chunkService;
    private final FakeChunkService fakeChunkService;
    private final PacketService packetService;
    private final LuckPermsService luckPermsService;
    private final MessageService messageService;
    private final OcclusionCullingService occlusionCullingService;
    private final PlayerMovementListener movementListener;

    @Inject
    public ViewDistanceService(ConfigService configService,
            PlayerStorageService storageService,
            ChunkService chunkService,
            FakeChunkService fakeChunkService,
            PacketService packetService,
            LuckPermsService luckPermsService,
            MessageService messageService,
            OcclusionCullingService occlusionCullingService,
            PlayerMovementListener movementListener) {
        this.configService = configService;
        this.storageService = storageService;
        this.chunkService = chunkService;
        this.fakeChunkService = fakeChunkService;
        this.packetService = packetService;
        this.luckPermsService = luckPermsService;
        this.messageService = messageService;
        this.occlusionCullingService = occlusionCullingService;
        this.movementListener = movementListener;
    }

    /**
     * Handles the logic when a player joins the server.
     */
    public void handlePlayerJoin(Player player) {
        fakeChunkService.onPlayerJoin(player);

        if (!isPluginEnabledForWorld(player.getWorld())) {
            return;
        }

        storageService.getPlayerData(player.getUniqueId()).thenAccept(playerData -> {
            int fallbackDefault = configService.get().viewDistance().defaultDistance();
            int clientDistance = player.getClientViewDistance();
            int initialDistance = (clientDistance > 0) ? clientDistance
                    : playerData
                            .map(PlayerData::getViewDistance)
                            .orElse(fallbackDefault);

            int clamped = clampDistance(player, initialDistance);
            PlayerView playerView = new PlayerView(player, clamped);
            playerViews.put(player.getUniqueId(), playerView);

            packetService.ensureClientRadius(player, clamped);
            packetService.ensureClientSimulationDistance(player, clamped);

            player.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                    (task) -> {
                        if (!player.isOnline())
                            return;

                        packetService.ensureClientRadius(player, clamped);
                        packetService.ensureClientSimulationDistance(player, clamped);
                    }, null, 5L);

            var msgCfg = configService.get().messages();
            if (msgCfg != null && msgCfg.welcomeMessage() != null && msgCfg.welcomeMessage().enabled()) {
                player.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                        (task) -> {
                            if (player.isOnline())
                                messageService.sendWelcome(player, clamped);
                        }, null, 15L);
            }

            player.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                    (task) -> {
                        if (!player.isOnline())
                            return;

                        packetService.ensureClientRadius(player, clamped);
                        packetService.ensureClientSimulationDistance(player, clamped);
                        updatePlayerView(player);
                    }, null, 70L);
        });
    }

    /**
     * Handles the logic when a player quits the server.
     */
    public void handlePlayerQuit(Player player) {
        PlayerView playerView = playerViews.remove(player.getUniqueId());
        if (playerView != null) {
            storageService.savePlayerData(new PlayerData(
                    player.getUniqueId(), playerView.getTargetDistance()));
        }

        fakeChunkService.clearPlayerFakeChunks(player, true,
                FakeChunkUnloadEvent.UnloadReason.PLAYER_QUIT);
        packetService.cleanupPlayer(player);
        movementListener.cleanupPlayer(player.getUniqueId());
    }

    /**
     * Sets player target distance with clamp and triggers update.
     * 
     * @param player            The player to set view distance for (must not be
     *                          null and must be online)
     * @param requestedDistance The requested view distance
     * @throws IllegalArgumentException if player is null or not online
     */
    public void setPlayerDistance(Player player, int requestedDistance) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }

        if (!isPluginEnabledForWorld(player.getWorld())) {
            throw new IllegalStateException("ExtendedHorizons is disabled in this world");
        }

        PlayerView view = playerViews.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerView(player, clampDistance(player, requestedDistance)));
        int clamped = clampDistance(player, requestedDistance);
        view.setTargetDistance(clamped);

        storageService.savePlayerData(
                new PlayerData(player.getUniqueId(), clamped));

        packetService.ensureClientRadius(player, clamped);
        packetService.ensureClientSimulationDistance(player, clamped);

        updatePlayerView(player);
    }

    /**
     * Returns allowed maximum distance for this player after LuckPerms check.
     */
    public int getAllowedMax(Player player) {
        String worldName = player.getWorld().getName();

        Map<String, WorldConfig> worldSettings = configService
                .get().worldSettings();

        int configMax;
        if (worldSettings != null && worldSettings.containsKey(worldName)) {
            configMax = worldSettings.get(worldName).maxDistance();
        } else {
            configMax = configService.get().viewDistance().maxDistance();
        }

        return luckPermsService != null && luckPermsService.isEnabled()
                ? Math.min(configMax, luckPermsService.resolveMaxDistance(player, configMax))
                : configMax;
    }

    private int clampDistance(Player player, int value) {
        int minViewDistance = org.bukkit.Bukkit.getServer().getViewDistance();
        int max = getAllowedMax(player);

        if (value < minViewDistance)
            return minViewDistance;
        return Math.min(value, max);
    }

    /**
     * Update player view when they move - DUAL SYSTEM
     */
    public void updatePlayerView(Player player) {
        if (!player.isOnline())
            return;
        if (!isPluginEnabledForWorld(player.getWorld())) {
            int serverDist = org.bukkit.Bukkit.getViewDistance();
            packetService.ensureClientRadius(player, serverDist);
            packetService.ensureClientSimulationDistance(player, serverDist);
            fakeChunkService.clearPlayerFakeChunks(player);
            return;
        }

        if (occlusionCullingService.isOccluded(player)) {
            int serverDist = org.bukkit.Bukkit.getViewDistance();
            packetService.ensureClientRadius(player, serverDist);
            packetService.ensureClientSimulationDistance(player, serverDist);
            fakeChunkService.clearPlayerFakeChunks(player);
            return;
        }

        PlayerView playerView = playerViews.get(player.getUniqueId());
        if (playerView == null)
            return;

        int clampedTarget = clampDistance(player, playerView.getTargetDistance());
        if (clampedTarget != playerView.getTargetDistance()) {
            playerView.setTargetDistance(clampedTarget);
        }

        packetService.ensureClientCenter(player);

        packetService.ensureClientRadius(player, playerView.getTargetDistance());
        packetService.ensureClientSimulationDistance(player, playerView.getTargetDistance());
        player.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    if (player.isOnline()) {
                        packetService.ensureClientSimulationDistance(player, playerView.getTargetDistance());
                    }
                }, null, 20L);

        int serverViewDistance = fakeChunkService.getServerViewDistance();
        if (playerView.getTargetDistance() <= serverViewDistance) {
            fakeChunkService.clearPlayerFakeChunks(player, true,
                    FakeChunkUnloadEvent.UnloadReason.DISTANCE);
            return;
        }

        org.bukkit.WorldBorder border = player.getWorld().getWorldBorder();
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderSize = border.getSize();
        int targetDistance = playerView.getTargetDistance();

        org.bukkit.Bukkit.getAsyncScheduler().runNow(ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    if (!player.isOnline())
                        return;

                    Set<Long> allNeededChunks = chunkService.computeCircularKeys(player, targetDistance);
                    ChunkClassification classification = classifyChunks(player, allNeededChunks, borderCenterX,
                            borderCenterZ, borderSize);

                    if (configService.get().performance().fakeChunks().enabled()
                            && fakeChunkService.isFakeChunksEnabledForWorld(player.getWorld())
                            && !classification.fakeChunks.isEmpty()) {
                        fakeChunkService.sendFakeChunks(player, classification.fakeChunks, borderCenterX, borderCenterZ,
                                borderSize);
                    }
                });
    }

    /**
     * Fast update for players in flight or moving fast
     */
    public void updatePlayerViewFast(Player player) {
        if (!player.isOnline())
            return;

        if (!isPluginEnabledForWorld(player.getWorld())) {
            return;
        }

        if (occlusionCullingService.isOccluded(player)) {
            return;
        }

        PlayerView playerView = playerViews.get(player.getUniqueId());
        if (playerView == null)
            return;

        int baseTarget = clampDistance(player, playerView.getTargetDistance());

        packetService.ensureClientCenter(player);
        packetService.ensureClientRadius(player, baseTarget);
        packetService.ensureClientSimulationDistance(player, baseTarget);

        org.bukkit.WorldBorder border = player.getWorld().getWorldBorder();
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderSize = border.getSize();

        org.bukkit.Bukkit.getAsyncScheduler().runNow(ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    if (!player.isOnline())
                        return;

                    Set<Long> allNeededChunks = chunkService.computeCircularKeys(player, baseTarget);
                    ChunkClassification classification = classifyChunks(player, allNeededChunks, borderCenterX,
                            borderCenterZ, borderSize);

                    if (configService.get().performance().fakeChunks().enabled()
                            && fakeChunkService.isFakeChunksEnabledForWorld(player.getWorld())
                            && !classification.fakeChunks.isEmpty()) {
                        fakeChunkService.sendFakeChunks(player, classification.fakeChunks, borderCenterX, borderCenterZ,
                                borderSize);
                    }
                });
    }

    /**
     * Classifies chunks into real (within server view-distance) and fake (beyond
     * server view-distance)
     * Also filters out chunks outside the world border.
     */
    private ChunkClassification classifyChunks(Player player, Set<Long> allChunks, double borderCenterX,
            double borderCenterZ, double borderSize) {
        int serverViewDistance = fakeChunkService.getServerViewDistance();
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        Set<Long> realChunks = new HashSet<>();
        Set<Long> fakeChunks = new HashSet<>();

        double serverRadiusSquared = (serverViewDistance + 0.5) * (serverViewDistance + 0.5);

        for (long key : allChunks) {
            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);

            if (!ChunkUtils.isChunkWithinWorldBorder(borderCenterX, borderCenterZ, borderSize, chunkX, chunkZ)) {
                continue;
            }

            int dx = chunkX - playerChunkX;
            int dz = chunkZ - playerChunkZ;
            double distanceSquared = dx * dx + dz * dz;

            if (distanceSquared <= serverRadiusSquared) {
                realChunks.add(key);
            } else {
                fakeChunks.add(key);
            }
        }

        return new ChunkClassification(realChunks, fakeChunks);
    }

    /**
     * Simple container for chunk classification result
     * realChunks are kept for potential future use (e.g., debugging, statistics)
     */
    private static class ChunkClassification {
        @SuppressWarnings("unused")
        final Set<Long> realChunks;
        final Set<Long> fakeChunks;

        ChunkClassification(Set<Long> realChunks, Set<Long> fakeChunks) {
            this.realChunks = realChunks;
            this.fakeChunks = fakeChunks;
        }
    }

    /**
     * Checks if the plugin is enabled for the specific world
     */
    private boolean isPluginEnabledForWorld(org.bukkit.World world) {
        String worldName = world.getName();
        var worldSettings = configService.get().worldSettings();

        if (worldSettings != null && worldSettings.containsKey(worldName)) {
            return worldSettings.get(worldName).enabled();
        }

        return configService.get().performance().fakeChunks().enabled();
    }

    public PlayerView getPlayerView(UUID uuid) {
        return playerViews.get(uuid);
    }
}
