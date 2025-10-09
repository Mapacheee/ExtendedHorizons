package me.mapacheee.extendedhorizons.viewdistance.listener;
import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.util.MessageUtil;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import me.mapacheee.extendedhorizons.integration.service.LuckPermsIntegrationService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.slf4j.Logger;

/* Player Movement Listener - Handles player events for view distance management
 * Integrates with Winter Framework's listener system for automatic registration
 */

@ListenerComponent
public class PlayerMovementListener implements Listener {

    private final Logger logger;
    private final ConfigService configService;
    private final MessageUtil messageUtil;
    private final ViewDistanceService viewDistanceService;
    private final LuckPermsIntegrationService luckPermsService;

    @Inject
    public PlayerMovementListener(
            Logger logger,
            ConfigService configService,
            MessageUtil messageUtil,
            ViewDistanceService viewDistanceService,
            LuckPermsIntegrationService luckPermsService
    ) {
        this.logger = logger;
        this.configService = configService;
        this.messageUtil = messageUtil;
        this.viewDistanceService = viewDistanceService;
        this.luckPermsService = luckPermsService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!configService.isEnabled()) return;
        if (!configService.isWorldEnabled(player.getWorld().getName())) return;

        // Initialize player view with slight delay to ensure proper loading
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
            () -> {
                viewDistanceService.initializePlayerView(player);

                var playerView = viewDistanceService.getPlayerView(player);
                if (playerView != null && configService.isDebugEnabled()) {
                    messageUtil.sendMessage(player,
                        "&#3498DBExtendedHorizons enabled with view distance: &#F39C12" +
                        playerView.getCurrentDistance() + " &#3498DBchunks");
                }
            },
            20L // 1 second delay
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        viewDistanceService.removePlayerView(player);
        luckPermsService.invalidatePlayerCache(player);

        logger.debug("Cleaned up view data for player {}", player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        if (!configService.isEnabled()) return;

        // Check if new world has ExtendedHorizons enabled
        if (!configService.isWorldEnabled(player.getWorld().getName())) {
            viewDistanceService.removePlayerView(player);
            return;
        }

        // Reinitialize view for new world
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
            () -> {
                viewDistanceService.removePlayerView(player);
                viewDistanceService.initializePlayerView(player);

                if (configService.isDebugEnabled()) {
                    messageUtil.sendMessage(player,
                        "&#3498DBView distance updated for world: &#F39C12" + player.getWorld().getName());
                }
            },
            10L // 0.5 second delay
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!configService.isEnabled()) return;
        if (!configService.isWorldEnabled(player.getWorld().getName())) return;

        // Only process if player actually moved to a different chunk
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;

        // Handle movement asynchronously to avoid blocking the main thread
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(
            org.bukkit.Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
            () -> viewDistanceService.handlePlayerMovement(player)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (!configService.isEnabled()) return;
        if (!configService.isWorldEnabled(player.getWorld().getName())) return;

        // Handle teleportation with a delay to ensure the player has loaded properly
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
            () -> {
                viewDistanceService.handlePlayerMovement(player);

                var playerView = viewDistanceService.getPlayerView(player);
                if (playerView != null) {
                    // Force immediate permission check after teleport
                    viewDistanceService.updatePlayerPermissions(player);
                }
            },
            5L // 0.25 second delay
        );
    }
}
