package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.integration.luckperms.LuckPermsService;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.service.MessageService;
import me.mapacheee.extendedhorizons.shared.storage.PlayerStorageService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    @Inject
    public ViewDistanceService(ConfigService configService,
                               PlayerStorageService storageService,
                               ChunkService chunkService,
                               FakeChunkService fakeChunkService,
                               PacketService packetService,
                               LuckPermsService luckPermsService,
                               MessageService messageService) {
        this.configService = configService;
        this.storageService = storageService;
        this.chunkService = chunkService;
        this.fakeChunkService = fakeChunkService;
        this.packetService = packetService;
        this.luckPermsService = luckPermsService;
        this.messageService = messageService;
    }

    /**
     * Handles the logic when a player joins the server.
     */
    public void handlePlayerJoin(Player player) {
        storageService.getPlayerData(player.getUniqueId()).thenAccept(playerData -> {
            int fallbackDefault = configService.get().viewDistance().defaultDistance();
            int initialDistance = playerData.map(me.mapacheee.extendedhorizons.shared.storage.PlayerData::getViewDistance).orElse(fallbackDefault);
            int clamped = clampDistance(player, initialDistance);
            PlayerView playerView = new PlayerView(player, clamped);
            playerViews.put(player.getUniqueId(), playerView);

            packetService.ensureClientRadius(player, clamped);

            Bukkit.getScheduler().runTaskLater(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class), () -> {
                if (!player.isOnline()) return;
                packetService.ensureClientRadius(player, clamped);
            }, 5L);

            var msgCfg = configService.get().messages();
            if (msgCfg != null && msgCfg.welcomeMessage() != null && msgCfg.welcomeMessage().enabled()) {
                Bukkit.getScheduler().runTaskLater(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class), () -> {
                    if (player.isOnline()) messageService.sendWelcome(player, clamped);
                }, 15L);
            }

            Bukkit.getScheduler().runTaskLater(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class), () -> {
                if (!player.isOnline()) return;
                packetService.ensureClientRadius(player, clamped);
                updatePlayerView(player);
            }, 20L);
        });
    }

    /**
     * Handles the logic when a player quits the server.
     */
    public void handlePlayerQuit(Player player) {
        PlayerView playerView = playerViews.remove(player.getUniqueId());
        if (playerView != null) {
            storageService.savePlayerData(new me.mapacheee.extendedhorizons.shared.storage.PlayerData(player.getUniqueId(), playerView.getTargetDistance()));
        }

        fakeChunkService.clearPlayerFakeChunks(player);
    }

    /**
     * Sets player target distance with clamp and triggers update.
     * 
     * @param player The player to set view distance for (must not be null and must be online)
     * @param requestedDistance The requested view distance
     * @throws IllegalArgumentException if player is null or not online
     */
    public void setPlayerDistance(Player player, int requestedDistance) {
        if (player == null || !player.isOnline()) {
            throw new IllegalArgumentException("Player must not be null and must be online");
        }
        
        PlayerView view = playerViews.computeIfAbsent(player.getUniqueId(), id -> new PlayerView(player, clampDistance(player, requestedDistance)));
        int clamped = clampDistance(player, requestedDistance);
        view.setTargetDistance(clamped);

        storageService.savePlayerData(new me.mapacheee.extendedhorizons.shared.storage.PlayerData(player.getUniqueId(), clamped));
        packetService.ensureClientRadius(player, clamped);

        updatePlayerView(player);
    }

    /**
     * Returns allowed maximum distance for this player after LuckPerms check.
     */
    public int getAllowedMax(Player player) {
        int configMax = configService.get().viewDistance().maxDistance();
        return luckPermsService != null && luckPermsService.isEnabled()
                ? Math.min(configMax, luckPermsService.resolveMaxDistance(player, configMax))
                : configMax;
    }

    private int clampDistance(Player player, int value) {
        int min = configService.get().viewDistance().minDistance();
        int max = getAllowedMax(player);
        if (value < min) return min;
        return Math.min(value, max);
    }

    /**
     * Update player view when they move - DUAL SYSTEM
     */
    public void updatePlayerView(Player player) {
        PlayerView playerView = playerViews.get(player.getUniqueId());
        if (playerView == null || !player.isOnline()) return;

        int clampedTarget = clampDistance(player, playerView.getTargetDistance());
        if (clampedTarget != playerView.getTargetDistance()) {
            playerView.setTargetDistance(clampedTarget);
        }

        packetService.ensureClientCenter(player);
        packetService.ensureClientRadius(player, playerView.getTargetDistance());

        Set<Long> allNeededChunks = chunkService.computeCircularKeys(player, playerView.getTargetDistance());
        ChunkClassification classification = classifyChunks(player, allNeededChunks);

        if (configService.get().performance().fakeChunks().enabled() && !classification.fakeChunks.isEmpty()) {
            fakeChunkService.sendFakeChunks(player, classification.fakeChunks);
        }
    }

    /**
     * Fast update for players in flight or moving fast
     */
    public void updatePlayerViewFast(Player player) {
        PlayerView playerView = playerViews.get(player.getUniqueId());
        if (playerView == null || !player.isOnline()) return;

        int baseTarget = clampDistance(player, playerView.getTargetDistance());
        packetService.ensureClientCenter(player);
        packetService.ensureClientRadius(player, baseTarget);

        Set<Long> allNeededChunks = chunkService.computeCircularKeys(player, baseTarget);
        ChunkClassification classification = classifyChunks(player, allNeededChunks);

        if (configService.get().performance().fakeChunks().enabled() && !classification.fakeChunks.isEmpty()) {
            fakeChunkService.sendFakeChunks(player, classification.fakeChunks);
        }
    }

    /**
     * Classifies chunks into real (within server view-distance) and fake (beyond server view-distance)
     */
    private ChunkClassification classifyChunks(Player player, Set<Long> allChunks) {
        int serverViewDistance = fakeChunkService.getServerViewDistance();
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        Set<Long> realChunks = new HashSet<>();
        Set<Long> fakeChunks = new HashSet<>();

        double serverRadiusSquared = (serverViewDistance + 0.5) * (serverViewDistance + 0.5);

        for (long key : allChunks) {
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);
            
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

    public PlayerView getPlayerView(UUID uuid) {
        return playerViews.get(uuid);
    }
}
