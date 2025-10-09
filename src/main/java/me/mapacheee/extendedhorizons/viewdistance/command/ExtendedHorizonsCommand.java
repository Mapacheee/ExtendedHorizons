package me.mapacheee.extendedhorizons.viewdistance.command;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.config.Config;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.config.Messages;
import me.mapacheee.extendedhorizons.shared.util.MessageUtil;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.optimization.service.PerformanceMonitorService;
import me.mapacheee.extendedhorizons.optimization.service.CacheService;
import me.mapacheee.extendedhorizons.integration.service.LuckPermsIntegrationService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/* Extended Horizons Command - Main command handler using Winter Framework's command system
 * Provides comprehensive admin and player commands for view distance management
 */

@CommandComponent
public class ExtendedHorizonsCommand implements CommandExecutor, TabCompleter {

    private final Logger logger;
    private final ConfigService configService;
    private final MessageUtil messageUtil;
    private final ViewDistanceService viewDistanceService;
    private final PerformanceMonitorService performanceMonitor;
    private final CacheService cacheService;
    private final LuckPermsIntegrationService luckPermsService;

    @Inject
    public ExtendedHorizonsCommand(
            Logger logger,
            ConfigService configService,
            MessageUtil messageUtil,
            ViewDistanceService viewDistanceService,
            PerformanceMonitorService performanceMonitor,
            CacheService cacheService,
            LuckPermsIntegrationService luckPermsService
    ) {
        this.logger = logger;
        this.configService = configService;
        this.messageUtil = messageUtil;
        this.viewDistanceService = viewDistanceService;
        this.performanceMonitor = performanceMonitor;
        this.cacheService = cacheService;
        this.luckPermsService = luckPermsService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        if (args.length == 0) {
            handleHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "help" -> {
                handleHelp(player);
            }
            case "info" -> {
                handleInfo(player);
            }
            case "distance" -> {
                handleDistance(player, Arrays.copyOfRange(args, 1, args.length));
            }
            case "reset" -> {
                handleReset(player, Arrays.copyOfRange(args, 1, args.length));
            }
            case "reload" -> {
                handleReload(player);
            }
            case "stats" -> {
                handleStats(player);
            }
            case "debug" -> {
                handleDebug(player);
            }
            case "world" -> {
                handleWorld(player, Arrays.copyOfRange(args, 1, args.length));
            }
            default -> {
                messageUtil.sendMessage(player, configService.getMessages().general().unknownCommand());
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartingWith(args[0], Arrays.asList(
                "help", "info", "distance", "reset", "reload", "stats", "debug", "world"
            ));
        }

        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            return switch (subCommand) {
                case "distance", "reset" -> {
                    if (sender.hasPermission("extendedhorizons.admin")) {
                        yield getOnlinePlayerNames();
                    }
                    yield new ArrayList<>();
                }
                case "world" -> getWorldNames();
                default -> new ArrayList<>();
            };
        }

        return new ArrayList<>();
    }

    private void handleHelp(Player player) {
        if (!player.hasPermission("extendedhorizons.use")) {
            messageUtil.sendNoPermission(player);
            return;
        }
        messageUtil.sendHelpMessage(player);
    }

    private void handleInfo(Player player) {
        if (!player.hasPermission("extendedhorizons.use")) {
            messageUtil.sendNoPermission(player);
            return;
        }

        Messages messages = configService.getMessages();

        // Plugin info
        messageUtil.sendMessageWithPrefix(player, messages.general().pluginInfo(),
            Map.of(
                "plugin", "ExtendedHorizons",
                "version", ExtendedHorizonsPlugin.getInstance().getDescription().getVersion(),
                "author", "Mapacheee"
            ));

        // Player view info if available
        PlayerView playerView = viewDistanceService.getPlayerView(player);
        if (playerView != null) {
            messageUtil.sendCurrentDistance(player, playerView.getCurrentDistance());

            if (playerView.areFakeChunksEnabled()) {
                Config config = configService.getConfig();
                messageUtil.sendMessage(player, messages.viewDistance().fakeChunksEnabled(),
                    Map.of("distance", String.valueOf(config.viewDistance().fakeChunksStartDistance())));
            }
        }
    }

    private void handleDistance(Player sender, String[] args) {
        if (!sender.hasPermission("extendedhorizons.use")) {
            messageUtil.sendNoPermission(sender);
            return;
        }

        // If no arguments, show current distance
        if (args.length == 0) {
            PlayerView playerView = viewDistanceService.getPlayerView(sender);
            if (playerView != null) {
                messageUtil.sendCurrentDistance(sender, playerView.getCurrentDistance());
            } else {
                messageUtil.sendMessage(sender, "&#E74C3CNo view data available!");
            }
            return;
        }

        // If only one argument, check if it's a number (distance for sender) or player name
        if (args.length == 1) {
            try {
                int distance = Integer.parseInt(args[0]);
                setPlayerDistance(sender, sender, distance);
                return;
            } catch (NumberFormatException e) {
                // It's a player name, show their distance (admin only)
                if (!sender.hasPermission("extendedhorizons.admin")) {
                    messageUtil.sendNoPermission(sender);
                    return;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    messageUtil.sendPlayerNotFound(sender, args[0]);
                    return;
                }

                PlayerView playerView = viewDistanceService.getPlayerView(target);
                if (playerView != null) {
                    messageUtil.sendMessage(sender, "&#3498DB" + target.getName() + "'s view distance: &#F39C12" +
                                          playerView.getCurrentDistance() + " &#3498DBchunks");
                } else {
                    messageUtil.sendMessage(sender, "&#E74C3CNo view data available for " + target.getName());
                }
                return;
            }
        }

        // Two arguments: player and distance
        if (args.length == 2) {
            if (!sender.hasPermission("extendedhorizons.admin")) {
                messageUtil.sendNoPermission(sender);
                return;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                messageUtil.sendPlayerNotFound(sender, args[0]);
                return;
            }

            try {
                int distance = Integer.parseInt(args[1]);
                setPlayerDistance(sender, target, distance);
            } catch (NumberFormatException e) {
                messageUtil.sendMessage(sender, "&#E74C3CInvalid distance: " + args[1]);
            }
        }
    }

    private void setPlayerDistance(Player sender, Player target, int distance) {
        Config config = configService.getConfig();

        if (distance < config.viewDistance().minDistance()) {
            messageUtil.sendMessage(sender, configService.getMessages().viewDistance().minDistanceNotMet(),
                Map.of("min", String.valueOf(config.viewDistance().minDistance())));
            return;
        }

        int maxAllowed = luckPermsService.getMaxViewDistance(target);
        if (distance > maxAllowed && !sender.hasPermission("extendedhorizons.bypass.limits")) {
            messageUtil.sendMaxDistanceExceeded(sender, maxAllowed);
            return;
        }

        viewDistanceService.setPlayerViewDistance(target, distance);

        if (sender.equals(target)) {
            messageUtil.sendDistanceChanged(target, distance);
        } else {
            messageUtil.sendDistanceSetOther(sender, target.getName(), distance);
            messageUtil.sendDistanceChanged(target, distance);
        }
    }

    private void handleReset(Player sender, String[] args) {
        if (!sender.hasPermission("extendedhorizons.use")) {
            messageUtil.sendNoPermission(sender);
            return;
        }

        Player target = sender;

        if (args.length > 0) {
            if (!sender.hasPermission("extendedhorizons.admin")) {
                messageUtil.sendNoPermission(sender);
                return;
            }

            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                messageUtil.sendPlayerNotFound(sender, args[0]);
                return;
            }
        }

        int defaultDistance = configService.getConfig().viewDistance().defaultDistance();
        viewDistanceService.setPlayerViewDistance(target, defaultDistance);

        Messages messages = configService.getMessages();
        messageUtil.sendMessage(target, messages.viewDistance().distanceReset(),
            Map.of("distance", String.valueOf(defaultDistance)));
    }

    private void handleReload(Player sender) {
        if (!sender.hasPermission("extendedhorizons.admin")) {
            messageUtil.sendNoPermission(sender);
            return;
        }

        try {
            configService.reloadConfigs();
            luckPermsService.invalidateAllCache();
            cacheService.clearAllCache();

            messageUtil.sendConfigReloaded(sender);
            logger.info("Configuration reloaded by {}", sender.getName());

        } catch (Exception e) {
            messageUtil.sendMessage(sender, configService.getMessages().errors().configError());
            logger.error("Error reloading configuration", e);
        }
    }

    private void handleStats(Player sender) {
        if (!sender.hasPermission("extendedhorizons.admin")) {
            messageUtil.sendNoPermission(sender);
            return;
        }

        Messages.StatsMessages stats = configService.getMessages().stats();
        PerformanceMonitorService.PerformanceMetrics metrics = performanceMonitor.getCurrentMetrics();
        CacheService.CacheStatistics cacheStats = cacheService.getStatistics();

        messageUtil.sendMessage(sender, stats.header());

        messageUtil.sendMessage(sender, stats.playersOnline(),
            Map.of(
                "online", String.valueOf(Bukkit.getOnlinePlayers().size()),
                "max", String.valueOf(Bukkit.getMaxPlayers())
            ));

        messageUtil.sendMessage(sender, stats.averageDistance(),
            Map.of("distance", String.format("%.1f", viewDistanceService.getAverageViewDistance())));

        messageUtil.sendMessage(sender, stats.chunksSent(),
            Map.of("chunks", String.valueOf(viewDistanceService.getTotalChunksSent())));

        messageUtil.sendMessage(sender, stats.fakeChunksSent(),
            Map.of("fake_chunks", String.valueOf(viewDistanceService.getTotalFakeChunksSent())));

        messageUtil.sendMessage(sender, stats.cacheSize(),
            Map.of("size", String.valueOf(cacheStats.currentSizeMB())));

        messageUtil.sendMessage(sender, stats.serverTps(),
            Map.of("tps", String.format("%.1f", metrics.tps())));

        messageUtil.sendMessage(sender, stats.footer());
    }

    private void handleDebug(Player sender) {
        if (!sender.hasPermission("extendedhorizons.admin")) {
            messageUtil.sendNoPermission(sender);
            return;
        }

        boolean debugEnabled = configService.isDebugEnabled();
        Messages.DebugMessages debug = configService.getMessages().debug();

        messageUtil.sendMessage(sender, debugEnabled ? debug.enabled() : debug.disabled());
    }

    private void handleWorld(Player sender, String[] args) {
        if (!sender.hasPermission("extendedhorizons.admin")) {
            messageUtil.sendNoPermission(sender);
            return;
        }

        if (args.length == 0) {
            messageUtil.sendMessage(sender, "&#E74C3CUsage: /eh world <world> [distance]");
            return;
        }

        String worldName = args[0];
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            messageUtil.sendMessage(sender, configService.getMessages().world().notFound(),
                Map.of("world", worldName));
            return;
        }

        if (args.length == 1) {
            // Show current world settings
            int maxDistance = configService.getMaxViewDistanceForWorld(worldName);
            messageUtil.sendMessage(sender,
                "&#3498DBWorld &#FFFFFF" + worldName + " &#3498DBmax distance: &#F39C12" + maxDistance);
            return;
        }

        // This would require a way to update world-specific config
        messageUtil.sendMessage(sender,
            "&#F39C12World-specific configuration changes require config file editing and reload.");
    }

    private List<String> filterStartingWith(String prefix, List<String> options) {
        return options.stream()
            .filter(option -> option.toLowerCase().startsWith(prefix.toLowerCase()))
            .toList();
    }

    private List<String> getOnlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .toList();
    }

    private List<String> getWorldNames() {
        return Bukkit.getWorlds().stream()
            .map(World::getName)
            .toList();
    }
}
