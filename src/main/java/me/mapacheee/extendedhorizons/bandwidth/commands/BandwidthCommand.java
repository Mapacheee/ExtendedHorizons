package me.mapacheee.extendedhorizons.bandwidth.commands;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import me.mapacheee.extendedhorizons.shared.service.MessageService;
import me.mapacheee.extendedhorizons.viewdistance.service.bandwidth.BandwidthController;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerChunkState;
import me.mapacheee.extendedhorizons.viewdistance.service.player.PlayerStateManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;

import java.util.UUID;

@CommandComponent
public class BandwidthCommand {

    private final BandwidthController bandwidthController;
    private final PlayerStateManager playerStateManager;
    private final MessageService messageService;

    @Inject
    public BandwidthCommand(BandwidthController bandwidthController, PlayerStateManager playerStateManager,
            MessageService messageService) {
        this.bandwidthController = bandwidthController;
        this.playerStateManager = playerStateManager;
        this.messageService = messageService;
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd bandwidth set <player> <kbps>")
    @Permission("extendedhorizons.admin")
    public void onSet(Source source, @Argument("player") Player target, @Argument("kbps") int kbps) {
        CommandSender sender = source.source();

        bandwidthController.setPlayerBandwidth(target.getUniqueId(), kbps);
        messageService.sendBandwidthSet(sender, target.getName(), kbps);
    }

    @Command("eh|extendedhorizons|horizons|viewdistance|vd bandwidth check <player>")
    @Permission("extendedhorizons.admin")
    public void onCheck(Source source, @Argument("player") Player target) {
        CommandSender sender = source.source();

        UUID id = target.getUniqueId();
        PlayerChunkState state = playerStateManager.get(id).orElse(null);

        if (state == null) {
            messageService.sendBandwidthStateNotFound(sender);
            return;
        }

        long tickLimit = state.getMaxBytesPerTick();
        long secLimit = state.getMaxBytesPerSecond();

        boolean isCustom = tickLimit > 0;
        int kbps;
        long bytesLimit;

        if (isCustom) {
            kbps = (int) (secLimit / 1024);
            bytesLimit = tickLimit;
        } else {
            long globalTick = bandwidthController.getDefaultMaxBytesPerTick();
            kbps = (int) ((globalTick * 20) / 1024);
            bytesLimit = globalTick;
        }

        messageService.sendBandwidthCheckInfo(sender, target.getName(), isCustom, kbps, bytesLimit);
    }
}
