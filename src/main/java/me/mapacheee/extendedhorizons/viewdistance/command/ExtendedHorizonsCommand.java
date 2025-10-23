package me.mapacheee.extendedhorizons.viewdistance.command;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.util.MessageUtil;
import me.mapacheee.extendedhorizons.viewdistance.service.IViewDistanceService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.optimization.service.CacheService;
import me.mapacheee.extendedhorizons.integration.service.ILuckPermsIntegrationService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;
import org.slf4j.Logger;

import java.util.Collection;

/* Extended Horizons Command - Main command handler using Winter Framework's command system with Incendo Cloud
 * Provides comprehensive admin and player commands for view distance management
 */

@CommandComponent
public class ExtendedHorizonsCommand {

    private final Logger logger;
    private final ConfigService configService;
    private final MessageUtil messageUtil;
    private final IViewDistanceService viewDistanceService;
    private final CacheService cacheService;
    private final ILuckPermsIntegrationService luckPermsService;

    @Inject
    public ExtendedHorizonsCommand(
            Logger logger,
            ConfigService configService,
            MessageUtil messageUtil,
            IViewDistanceService viewDistanceService,
            CacheService cacheService,
            ILuckPermsIntegrationService luckPermsService
    ) {
        this.logger = logger;
        this.configService = configService;
        this.messageUtil = messageUtil;
        this.viewDistanceService = viewDistanceService;
        this.cacheService = cacheService;
        this.luckPermsService = luckPermsService;
    }

    @Command("extendedhorizons")
    @Permission("extendedhorizons.use")
    public void handleMainFull(Source source) {
        if (source.source() instanceof Player player) {
            messageUtil.sendHelpMessage(player);
        }
    }

    @Command("eh")
    @Permission("extendedhorizons.use")
    public void handleMain(Source source) {
        if (source.source() instanceof Player player) {
            messageUtil.sendHelpMessage(player);
        }
    }

    @Command("horizons")
    @Permission("extendedhorizons.use")
    public void handleHorizons(Source source) {
        if (source.source() instanceof Player player) {
            messageUtil.sendHelpMessage(player);
        }
    }

    @Command("viewdistance")
    @Permission("extendedhorizons.use")
    public void handleViewdistance(Source source) {
        if (source.source() instanceof Player player) {
            messageUtil.sendHelpMessage(player);
        }
    }

    @Command("vd")
    @Permission("extendedhorizons.use")
    public void handleVd(Source source) {
        if (source.source() instanceof Player player) {
            messageUtil.sendHelpMessage(player);
        }
    }

    @Command("eh help")
    @Permission("extendedhorizons.use")
    public void handleHelp(Source source) {
        if (source.source() instanceof Player player) {
            messageUtil.sendHelpMessage(player);
        }
    }

    @Command("eh info")
    @Permission("extendedhorizons.use")
    public void handleInfo(Source source) {
        if (source.source() instanceof Player player) {
            String info = configService.getPluginInfoMessage();
            messageUtil.sendMessageWithPrefix(player, info, java.util.Map.of(
                    "plugin", "ExtendedHorizons",
                    "version", ExtendedHorizonsPlugin.getInstance().getPluginMeta().getVersion(),
                    "author", "Mapacheee"
            ));

            PlayerView playerView = viewDistanceService.getPlayerView(player.getUniqueId());
            if (playerView != null) {
                messageUtil.sendCurrentDistance(player, playerView.getCurrentDistance());

                if (playerView.areFakeChunksEnabled()) {
                    int fakeStartDistance = configService.getFakeChunksStartDistance();
                    String fakeMsg = configService.getFakeChunksEnabledInfoMessage();
                    messageUtil.sendMessage(player, fakeMsg, java.util.Map.of("distance", String.valueOf(fakeStartDistance)));
                }
            }
        }
    }

    @Command("eh view")
    @Permission("extendedhorizons.use")
    public void handleViewDistance(Source source) {
        if (source.source() instanceof Player player) {
            PlayerView playerView = viewDistanceService.getPlayerView(player.getUniqueId());
            if (playerView != null) {
                messageUtil.sendCurrentDistance(player, playerView.getCurrentDistance());
            } else {
                messageUtil.sendNoViewData(player);
            }
        }
    }

    @Command("eh setme <distance>")
    @Permission("extendedhorizons.use")
    public void handleSetMyDistance(Source source, @Argument("distance") int distance) {
        if (source.source() instanceof Player player) {
            setPlayerDistance(player, player, distance);
        }
    }

    @Command("eh reset")
    @Permission("extendedhorizons.use")
    public void handleReset(Source source) {
        if (source.source() instanceof Player player) {
            int defaultDistance = configService.getDefaultViewDistance();
            viewDistanceService.setViewDistance(player, defaultDistance);
            messageUtil.sendViewDistanceReset(player, defaultDistance);
        }
    }

    @Command("eh check <player>")
    @Permission("extendedhorizons.admin")
    public void handleCheckPlayer(Source source, @Argument("player") Player target) {
        CommandSender sender = source.source();
        PlayerView playerView = viewDistanceService.getPlayerView(target.getUniqueId());
        if (playerView != null) {
            String msg = configService.getOtherCurrentDistanceMessage();
            messageUtil.sendMessage(sender, msg, java.util.Map.of(
                    "player", target.getName(),
                    "distance", String.valueOf(playerView.getCurrentDistance())
            ));
        } else {
            String msg = configService.getNoViewDataOtherMessage();
            messageUtil.sendMessage(sender, msg, java.util.Map.of("player", target.getName()));
        }
    }

    @Command("eh setplayer <player> <distance>")
    @Permission("extendedhorizons.admin")
    public void handleSetPlayerDistance(Source source, @Argument("player") Player target, @Argument("distance") int distance) {
        CommandSender sender = source.source();
        setPlayerDistance(sender, target, distance);
    }

    @Command("eh resetplayer <player>")
    @Permission("extendedhorizons.admin")
    public void handleResetPlayer(Source source, @Argument("player") Player target) {
        CommandSender sender = source.source();
        int defaultDistance = configService.getDefaultViewDistance();
        viewDistanceService.setViewDistance(target, defaultDistance);
        messageUtil.sendViewDistanceReset(target, defaultDistance);

        if (sender instanceof Player p && !p.equals(target)) {
            messageUtil.sendDistanceSetOther(p, target.getName(), defaultDistance);
        }
    }

    @Command("eh reload")
    @Permission("extendedhorizons.admin")
    public void handleReload(Source source) {
        CommandSender sender = source.source();
        try {
            configService.reload();
            luckPermsService.invalidateAllCache();
            cacheService.clearAllCache();

            if (sender instanceof Player p) {
                messageUtil.sendConfigReloaded(p);
            } else {
                logger.info("Configuration reloaded from console");
            }
            logger.info("Configuration reloaded by {}", sender.getName());

        } catch (Exception e) {
            if (sender instanceof Player p) {
                messageUtil.sendConfigError(p);
            } else {
                logger.error("Error reloading configuration from console", e);
            }
            logger.error("Error reloading configuration", e);
        }
    }

    @Command("eh stats")
    @Permission("extendedhorizons.admin")
    public void handleStats(Source source) {
        CommandSender sender = source.source();
        CacheService.CacheStatistics cacheStats = cacheService.getStatistics();

        messageUtil.sendStatsHeader(sender);
        messageUtil.sendStatsPlayersOnline(sender, Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());

        Collection<PlayerView> allViews = viewDistanceService.getAllPlayerViews();
        double averageDistance = allViews.stream()
            .mapToInt(PlayerView::getCurrentDistance)
            .average()
            .orElse(0.0);
        messageUtil.sendStatsAverageDistance(sender, averageDistance);

        messageUtil.sendStatsChunksSent(sender, viewDistanceService.getTotalChunksSent());
        messageUtil.sendStatsFakeChunksSent(sender, viewDistanceService.getTotalFakeChunksSent());
        messageUtil.sendStatsCacheSize(sender, cacheStats.currentSizeMB());
        messageUtil.sendStatsFooter(sender);
    }

    @Command("eh debug")
    @Permission("extendedhorizons.admin")
    public void handleDebug(Source source) {
        CommandSender sender = source.source();
        boolean debugEnabled = configService.isDebugEnabled();
        messageUtil.sendDebugStatus(sender, debugEnabled);
    }

    @Command("eh worldinfo <world>")
    @Permission("extendedhorizons.admin")
    public void handleWorldInfo(Source source, @Argument("world") World world) {
        CommandSender sender = source.source();
        int maxDistance = configService.getMaxViewDistanceForWorld(world.getName());
        String msg = configService.getWorldMaxDistanceInfoMessage();
        messageUtil.sendMessage(sender, msg, java.util.Map.of(
                "world", world.getName(),
                "distance", String.valueOf(maxDistance)
        ));
    }

    @Command("eh worldhelp")
    @Permission("extendedhorizons.admin")
    public void handleWorldHelp(Source source) {
        CommandSender sender = source.source();
        messageUtil.sendWorldUsage(sender);
    }

    private void setPlayerDistance(CommandSender sender, Player target, int distance) {
        int minDistance = configService.getMinViewDistance();

        if (distance < minDistance) {
            messageUtil.sendMinDistanceError(sender, minDistance);
            return;
        }

        int maxAllowed = luckPermsService.getMaxViewDistance(target);
        if (distance > maxAllowed && !sender.hasPermission("extendedhorizons.bypass.limits")) {
            messageUtil.sendMaxDistanceExceeded(sender, maxAllowed);
            return;
        }

        viewDistanceService.setViewDistance(target, distance);

        if (sender instanceof Player p && p.equals(target)) {
            messageUtil.sendDistanceChanged(target, distance);
        } else if (sender instanceof Player p) {
            messageUtil.sendDistanceSetOther(p, target.getName(), distance);
            messageUtil.sendDistanceChanged(target, distance);
        } else {
            messageUtil.sendDistanceChanged(target, distance);
            logger.info("Distance set to {} for {} from console", distance, target.getName());
        }
    }
}
