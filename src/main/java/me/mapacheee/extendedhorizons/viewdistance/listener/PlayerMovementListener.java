package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.service.IViewDistanceService;
import me.mapacheee.extendedhorizons.shared.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.slf4j.Logger;

/**
 * Listener for player events related to view distance management
 * Handles player join/quit, movement, and world changes
 */
@ListenerComponent
public class PlayerMovementListener implements Listener {

    private final Logger logger;
    private final ConfigService configService;
    private final IViewDistanceService viewDistanceService;
    private final MessageUtil messageUtil;

    @Inject
    public PlayerMovementListener(
            Logger logger,
            ConfigService configService,
            IViewDistanceService viewDistanceService,
            MessageUtil messageUtil
    ) {
        this.logger = logger;
        this.configService = configService;
        this.viewDistanceService = viewDistanceService;
        this.messageUtil = messageUtil;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!configService.isEnabled()) {
            return;
        }

        try {
            viewDistanceService.initializePlayerView(player);

            int effectiveDistance = viewDistanceService.getEffectiveViewDistance(player);

            logger.debug("Initialized view distance for player {} with distance {}",
                        player.getName(), effectiveDistance);

            if (configService.isWelcomeMessageEnabled()) {
                messageUtil.sendWelcomeMessage(player, effectiveDistance);
            }

        } catch (Exception e) {
            logger.error("Error initializing view distance for player {}", player.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        try {
            viewDistanceService.getPlayerView(player.getUniqueId());
            logger.debug("Cleaned view distance data for player {}", player.getName());

        } catch (Exception e) {
            logger.error("Error cleaning view distance for player {}", player.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        try {
            viewDistanceService.updatePlayerView(player);

        } catch (Exception e) {
            logger.error("Error updating view distance for moving player {}", player.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        if (!configService.isEnabled()) {
            return;
        }

        if (!configService.isWorldEnabled(player.getWorld().getName())) {
            return;
        }

        try {
            int defaultDistance = configService.getDefaultViewDistance();
            viewDistanceService.setViewDistance(player, defaultDistance);

            logger.debug("Reset view distance for player {} in world {}",
                        player.getName(), player.getWorld().getName());

        } catch (Exception e) {
            logger.error("Error resetting view distance for player {} in world {}",
                        player.getName(), player.getWorld().getName(), e);
        }
    }
}
