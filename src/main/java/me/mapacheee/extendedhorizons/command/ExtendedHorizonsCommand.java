package me.mapacheee.extendedhorizons.command;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.ReloadServiceManager;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;

@CommandComponent
public class ExtendedHorizonsCommand {

    private final ReloadServiceManager reloadServiceManager;
    private final FakeChunkService fakeChunkService;
    private final Container<Config> configContainer;
    private final Messages messages;

    @Inject
    public ExtendedHorizonsCommand(
            ReloadServiceManager reloadServiceManager,
            FakeChunkService fakeChunkService,
            Container<Config> configContainer,
            Messages messages
    ) {
        this.reloadServiceManager = reloadServiceManager;
        this.fakeChunkService = fakeChunkService;
        this.configContainer = configContainer;
        this.messages = messages;
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd reload")
    @Permission("extendedhorizons.admin")
    public void reload(Source source) {
        reloadServiceManager.reload();
        messages.sendReloaded(source.source());
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd setme <distance>")
    @Permission("extendedhorizons.use")
    public void setme(Source source, @Argument("distance") int distance) {
        CommandSender sender = source.source();
        if (!(sender instanceof Player player)) {
            messages.sendPlayerOnly(sender);
            return;
        }

        int minDistance = getServerMinDistance(player);
        int maxDistance = getSetMeMaxDistance(player);

        if (distance < minDistance) {
            messages.sendMinDistance(player, minDistance);
            return;
        }
        if (distance > maxDistance) {
            messages.sendMaxDistance(player, maxDistance);
            return;
        }

        fakeChunkService.applyDistancePreference(player, distance);
        messages.sendSetMeUpdated(player, distance);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd set <player> <distance>")
    @Permission("extendedhorizons.admin")
    public void set(Source source, @Argument("player") Player target, @Argument("distance") int distance) {
        CommandSender sender = source.source();
        if (target == null || !target.isOnline()) {
            messages.sendPlayerNotFound(sender, target == null ? "unknown" : target.getName());
            return;
        }

        int minDistance = getServerMinDistance(target);
        int maxDistance = config().fakeTargetViewDistance();

        if (distance < minDistance) {
            messages.sendMinDistance(sender, minDistance);
            return;
        }
        if (distance > maxDistance) {
            messages.sendMaxDistance(sender, maxDistance);
            return;
        }

        fakeChunkService.applyDistancePreference(target, distance);
        messages.sendSetUpdated(sender, target.getName(), distance);
    }

    private int getServerMinDistance(Player player) {
        int global = Bukkit.getServer().getViewDistance();
        int playerDistance = player.getViewDistance();
        int min = playerDistance > 0 ? Math.min(global, playerDistance) : global;
        return Math.max(2, min);
    }

    private int getSetMeMaxDistance(Player player) {
        int defaultMax = config().fakeTargetViewDistance();
        if (player == null) return defaultMax;
        int permissionMax = Integer.MIN_VALUE;
        String prefix = "extendedhorizons.max.";

        for (PermissionAttachmentInfo permissionInfo : player.getEffectivePermissions()) {
            if (permissionInfo == null || !permissionInfo.getValue()) continue;
            String permission = permissionInfo.getPermission();
            if (permission == null || !permission.startsWith(prefix)) continue;
            String rawValue = permission.substring(prefix.length());
            try {
                int value = Integer.parseInt(rawValue);
                if (value >= 2 && value > permissionMax) {
                    permissionMax = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return permissionMax == Integer.MIN_VALUE ? defaultMax : permissionMax;
    }

    private Config config() {
        Config cfg = configContainer.get();
        return cfg == null ? new Config(null, null, null) : cfg;
    }
}
