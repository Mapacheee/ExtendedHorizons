package me.mapacheee.extendedhorizons.viewdistance.command;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.service.ReloadServiceManager;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;

import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.service.MessageService;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;

/*
 *   Command entrypoint for user/admin operations
*/
@CommandComponent
public class ViewDistanceCommand {

    private final ViewDistanceService viewDistanceService;
    private final MessageService messageService;
    private final ConfigService configService;
    private final ReloadServiceManager reloadServiceManager;
    private final FakeChunkService fakeChunkService;
    private final ChunkLoaderService chunkLoaderService;

    @Inject
    public ViewDistanceCommand(
            ViewDistanceService viewDistanceService,
            MessageService messageService,
            ConfigService configService,
            ReloadServiceManager reloadServiceManager,
            FakeChunkService fakeChunkService,
            ChunkLoaderService chunkLoaderService) {
        this.viewDistanceService = viewDistanceService;
        this.messageService = messageService;
        this.configService = configService;
        this.reloadServiceManager = reloadServiceManager;
        this.fakeChunkService = fakeChunkService;
        this.chunkLoaderService = chunkLoaderService;
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd help")
    @Permission("extendedhorizons.use")
    public void help(Source source) {
        CommandSender sender = source.source();
        boolean isAdmin = sender.hasPermission("extendedhorizons.admin");
        messageService.sendHelp(sender, isAdmin);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd info")
    @Permission("extendedhorizons.use")
    public void info(Source source) {
        CommandSender sender = source.source();
        Plugin plugin = ExtendedHorizonsPlugin.getPlugin(ExtendedHorizonsPlugin.class);
        var meta = plugin.getPluginMeta();
        messageService.sendPluginInfo(sender, meta.getName(), meta.getVersion(), String.join(", ", meta.getAuthors()));
        if (sender instanceof Player player) {
            var view = viewDistanceService.getPlayerView(player.getUniqueId());
            if (view != null) {
                messageService.sendCurrentDistance(player, view.getTargetDistance());
            }
        }
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd view")
    @Permission("extendedhorizons.use")
    public void view(Source source) {
        CommandSender sender = source.source();
        if (!(sender instanceof Player player)) {
            messageService.sendPlayerOnly(sender);
            return;
        }
        var view = viewDistanceService.getPlayerView(player.getUniqueId());
        if (view == null) {
            messageService.sendNoViewData(player);
            return;
        }
        messageService.sendCurrentDistance(player, view.getTargetDistance());
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd setme <distance>")
    @Permission("extendedhorizons.use")
    public void setMe(Source source, @Argument("distance") int distance) {
        CommandSender sender = source.source();
        if (!(sender instanceof Player player)) {
            messageService.sendPlayerOnly(sender);
            return;
        }
        int serverViewDistance = getServerViewDistance();
        int allowedMax = viewDistanceService.getAllowedMax(player);
        if (distance < serverViewDistance) {
            messageService.sendServerViewDistanceError(sender, serverViewDistance);
            return;
        }
        if (distance > allowedMax) {
            messageService.sendMaxDistanceExceeded(player, allowedMax);
            return;
        }
        viewDistanceService.setPlayerDistance(player, distance);
        messageService.sendDistanceChanged(player, distance);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd reset")
    @Permission("extendedhorizons.use")
    public void reset(Source source) {
        CommandSender sender = source.source();
        if (!(sender instanceof Player player)) {
            messageService.sendPlayerOnly(sender);
            return;
        }
        int def = configService.get().viewDistance().defaultDistance();
        viewDistanceService.setPlayerDistance(player, def);
        messageService.sendReset(player, def);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd check <player>")
    @Permission("extendedhorizons.admin")
    public void check(Source source, @Argument("player") Player target) {
        CommandSender sender = source.source();
        var view = viewDistanceService.getPlayerView(target.getUniqueId());
        if (view == null) {
            messageService.sendNoViewDataOther(sender, target.getName());
            return;
        }
        messageService.sendOtherCurrentDistance(sender, target.getName(), view.getTargetDistance());
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd setplayer <player> <distance>")
    @Permission("extendedhorizons.admin")
    public void setPlayer(Source source, @Argument("player") Player target, @Argument("distance") int distance) {
        CommandSender sender = source.source();
        int serverViewDistance = getServerViewDistance();
        int allowedMax = viewDistanceService.getAllowedMax(target);
        if (distance < serverViewDistance) {
            messageService.sendServerViewDistanceError(sender, serverViewDistance);
            return;
        }
        if (distance > allowedMax) {
            messageService.sendMaxDistanceExceeded(sender, allowedMax);
            return;
        }
        viewDistanceService.setPlayerDistance(target, distance);
        messageService.sendDistanceSetOther(sender, target.getName(), distance);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd resetplayer <player>")
    @Permission("extendedhorizons.admin")
    public void resetPlayer(Source source, @Argument("player") Player target) {
        CommandSender sender = source.source();
        int def = configService.get().viewDistance().defaultDistance();
        viewDistanceService.setPlayerDistance(target, def);
        messageService.sendReset(sender, def);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd reload")
    @Permission("extendedhorizons.admin")
    public void reload(Source source) {
        reloadServiceManager.reload();
        messageService.sendConfigReloaded(source.source());
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd stats")
    @Permission("extendedhorizons.admin")
    public void stats(Source source) {
        CommandSender sender = source.source();
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        int countWithView = 0;
        int sum = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            var v = viewDistanceService.getPlayerView(p.getUniqueId());
            if (v != null) {
                countWithView++;
                sum += v.getTargetDistance();
            }
        }
        int avg = countWithView == 0 ? 0 : (sum / countWithView);

        int cachedFakePackets = chunkLoaderService.getPacketCacheSize();
        double fakeMemoryMB = chunkLoaderService.getEstimatedMemoryUsageMB();

        messageService.sendStats(sender, online, max, avg, fakeChunkService.getServerViewDistance(),
                cachedFakePackets, fakeMemoryMB, chunkLoaderService.getCacheHitRate(),
                fakeChunkService.getStats());
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd worldinfo <world>")
    @Permission("extendedhorizons.admin")
    public void worldInfo(Source source, @Argument("world") String worldName) {
        CommandSender sender = source.source();
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            messageService.sendWorldNotFound(sender, worldName);
            return;
        }
        int max = configService.get().viewDistance().maxDistance();
        messageService.sendWorldMaxDistanceInfo(sender, worldName, max);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd worldhelp")
    @Permission("extendedhorizons.admin")
    public void worldHelp(Source source) {
        CommandSender sender = source.source();
        messageService.sendWorldUsage(sender);
        messageService.sendWorldConfigNotice(sender);
    }

    /**
     * Gets the server's view distance from server.properties
     */
    private int getServerViewDistance() {
        return Bukkit.getServer().getViewDistance();
    }
}
