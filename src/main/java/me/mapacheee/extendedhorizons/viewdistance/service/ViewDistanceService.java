package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.integration.luckperms.LuckPermsService;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.service.MessageService;
import me.mapacheee.extendedhorizons.shared.storage.PlayerStorageService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.listener.PlayerMovementListener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.WorldBorder;
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

        if (!player.hasPermission("extendedhorizons.use")) {
            return;
        }

        if (!isPluginEnabledForWorld(player.getWorld())) {
            return;
        }

        UUID playerId = player.getUniqueId();

        storageService.getPlayerData(playerId).thenAccept(playerData -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                return;
            }

            int maxAllowed = getAllowedMax(p);

            int dbDistance = playerData.map(PlayerData::getViewDistance).orElse(-1);

            int initialDistance;
            if (dbDistance > 0) {
                initialDistance = Math.min(dbDistance, maxAllowed);
            } else {
                initialDistance = maxAllowed;
            }

            int clamped = clampDistance(p, initialDistance);
            PlayerView playerView = new PlayerView(p, clamped, initialDistance);
            playerViews.put(p.getUniqueId(), playerView);

            packetService.ensureClientRadius(p, clamped);
            packetService.ensureClientSimulationDistance(p, clamped);

            p.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                    (task) -> {
                        if (!p.isOnline())
                            return;

                        packetService.ensureClientRadius(p, clamped);
                        packetService.ensureClientSimulationDistance(p, clamped);
                    }, null, 5L);

            var msgCfg = configService.get().messages();
            if (msgCfg != null && msgCfg.welcomeMessage() != null && msgCfg.welcomeMessage().enabled()) {
                p.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                        (task) -> {
                            if (p.isOnline())
                                messageService.sendWelcome(p, clamped);
                        }, null, 15L);
            }

            p.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                    (task) -> {
                        if (!p.isOnline())
                            return;

                        packetService.ensureClientRadius(p, clamped);
                        packetService.ensureClientSimulationDistance(p, clamped);
                        updatePlayerView(p);
                    }, null, 70L);
        });
    }

    /**
     * Handles the logic when a player quits the server.
     */
    public void handlePlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerView playerView = playerViews.remove(playerId);
        if (playerView != null) {
            storageService.savePlayerData(new PlayerData(
                    playerId, playerView.getPreferredDistance()));
        }

        fakeChunkService.cleanupPlayer(player, true,
                FakeChunkUnloadEvent.UnloadReason.PLAYER_QUIT);
        packetService.cleanupPlayer(player);
        movementListener.cleanupPlayer(playerId);

        if (luckPermsService != null && luckPermsService.isEnabled()) {
            luckPermsService.cleanupPlayer(playerId);
        }

        // IMPORTANT: Final cleanup of the player state to avoid memory leaks
        ExtendedHorizonsPlugin.getService(me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager.class)
                .remove(playerId);
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

        if (!player.hasPermission("extendedhorizons.use")) {
            return;
        }

        if (!isPluginEnabledForWorld(player.getWorld())) {
            throw new IllegalStateException("ExtendedHorizons is disabled in this world");
        }

        int clamped = clampDistance(player, requestedDistance);

        PlayerView view = playerViews.compute(player.getUniqueId(), (uuid, existing) -> {
            if (existing == null) {
                return new PlayerView(player, clamped, requestedDistance);
            }
            existing.setTargetDistance(clamped);
            existing.setPreferredDistance(requestedDistance);
            return existing;
        });

        storageService.savePlayerData(
                new PlayerData(player.getUniqueId(), requestedDistance));

        packetService.ensureClientRadius(player, clamped);
        packetService.ensureClientSimulationDistance(player, clamped);

        updatePlayerView(player);
    }

    /**
     * Returns allowed maximum distance for the player.
     * Priority:
     * 1. LuckPerms meta/permission (extendedhorizons.max.<N>) if available
     * 2. Bukkit permission (extendedhorizons.see.<N>) - scans for highest value
     * 3. Config default-distance as fallback
     * 
     * The result is ALWAYS capped by config max-distance (global or per-world).
     */
    public int getAllowedMax(Player player) {
        String worldName = player.getWorld().getName();

        // Absolute cap: world-specific or global max-distance (nothing overrides this)
        Map<String, WorldConfig> worldSettings = configService.get().worldSettings();
        int absoluteCap;
        if (worldSettings != null && worldSettings.containsKey(worldName)) {
            absoluteCap = worldSettings.get(worldName).maxDistance();
        } else {
            absoluteCap = configService.get().viewDistance().maxDistance();
        }

        int baseDefault = configService.get().viewDistance().defaultDistance();

        // 1. Check LuckPerms override first (backward compat)
        int luckPermsMax = -1;
        if (luckPermsService != null && luckPermsService.isEnabled()) {
            luckPermsMax = luckPermsService.resolveMaxDistance(player, -1);
        }

        // 2. Check Bukkit permission extendedhorizons.see.<N>
        int permissionMax = resolveSeePermission(player);

        // Determine effective max:
        // - LuckPerms takes priority if set
        // - Then Bukkit see.<N> permission
        // - Then config default
        int effectiveMax;
        if (luckPermsMax > 0) {
            effectiveMax = luckPermsMax;
        } else if (permissionMax > 0) {
            effectiveMax = permissionMax;
        } else {
            effectiveMax = baseDefault;
        }

        // Absolute cap always wins
        return Math.min(effectiveMax, absoluteCap);
    }

    /**
     * Scans player's Bukkit permissions for extendedhorizons.see.<N>
     * Returns the highest N value found, or -1 if no such permission exists.
     */
    private int resolveSeePermission(Player player) {
        int best = -1;
        for (var perm : player.getEffectivePermissions()) {
            String p = perm.getPermission();
            if (p.startsWith("extendedhorizons.see.") && perm.getValue()) {
                try {
                    int val = Integer.parseInt(p.substring("extendedhorizons.see.".length()));
                    if (val > 0 && val > best) {
                        best = val;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
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

        int clampedTarget = clampDistance(player, playerView.getPreferredDistance());
        if (clampedTarget != playerView.getTargetDistance()) {
            playerView.setTargetDistance(clampedTarget);
        }

        packetService.ensureClientCenter(player);

        packetService.ensureClientRadius(player, playerView.getTargetDistance());
        packetService.ensureClientSimulationDistance(player, playerView.getTargetDistance());

        // Store values to prevent memory leak
        UUID playerId = player.getUniqueId();
        int distance = playerView.getTargetDistance();

        player.getScheduler().runDelayed(ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null && p.isOnline()) {
                        packetService.ensureClientSimulationDistance(p, distance);
                    }
                }, null, 20L);

        WorldBorder border = player.getWorld().getWorldBorder();
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderSize = border.getSize();
        int targetDistance = playerView.getTargetDistance();

        org.bukkit.Bukkit.getAsyncScheduler().runNow(ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    if (!player.isOnline())
                        return;

                    Set<Long> allNeededChunks = chunkService.computeCircularKeys(player, targetDistance + 1);
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

        int baseTarget = clampDistance(player, playerView.getPreferredDistance());

        packetService.ensureClientCenter(player);
        packetService.ensureClientRadius(player, baseTarget);
        packetService.ensureClientSimulationDistance(player, baseTarget);

        WorldBorder border = player.getWorld().getWorldBorder();
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderSize = border.getSize();

        Bukkit.getAsyncScheduler().runNow(ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    if (!player.isOnline())
                        return;

                    Set<Long> allNeededChunks = chunkService.computeCircularKeys(player, baseTarget + 1);
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
     * World border filtering is done later in FakeChunkService.sendFakeChunks()
     */
    private ChunkClassification classifyChunks(Player player, Set<Long> allChunks, double borderCenterX,
            double borderCenterZ, double borderSize) {
        int serverViewDistance = player.getViewDistance();
        if (serverViewDistance <= 0) {
            serverViewDistance = fakeChunkService.getServerViewDistance();
        }
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        Set<Long> realChunks = new HashSet<>();
        Set<Long> fakeChunks = new HashSet<>();

        for (long key : allChunks) {
            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);

            int dx = chunkX - playerChunkX;
            int dz = chunkZ - playerChunkZ;
            int safeSquareRadius = (int) Math.floor(serverViewDistance * 0.65);
            int chebyshevDist = Math.max(Math.abs(dx), Math.abs(dz));

            if (chebyshevDist <= safeSquareRadius) {
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
