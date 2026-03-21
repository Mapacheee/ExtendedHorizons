package me.mapacheee.extendedhorizons.messages;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.config.ConfigService;
import org.bukkit.command.CommandSender;

@Service
public class Messages {

    private final ConfigService configService;

    @Inject
    public Messages(ConfigService configService) {
        this.configService = configService;
    }

    public void sendReloaded(CommandSender sender) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.commands() == null) return;
        sendPrefixed(sender, cfg.commands().reloaded());
    }

    public void sendPlayerOnly(CommandSender sender) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.commands() == null) return;
        sendPrefixed(sender, cfg.commands().playerOnly());
    }

    public void sendSetMeUpdated(CommandSender sender, int distance) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.commands() == null) return;
        sendPrefixed(sender, withDistance(cfg.commands().setmeUpdated(), distance));
    }

    public void sendSetUpdated(CommandSender sender, String playerName, int distance) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.commands() == null) return;
        String message = withDistance(cfg.commands().setUpdated(), distance).replace("{player}", playerName);
        sendPrefixed(sender, message);
    }

    public void sendMinDistance(CommandSender sender, int minDistance) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.commands() == null) return;
        sendPrefixed(sender, withDistance(cfg.commands().minDistance(), minDistance));
    }

    public void sendMaxDistance(CommandSender sender, int maxDistance) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.commands() == null) return;
        sendPrefixed(sender, withDistance(cfg.commands().maxDistance(), maxDistance));
    }

    public void sendPlayerNotFound(CommandSender sender, String playerName) {
        MessagesConfig cfg = config();
        if (cfg == null || cfg.general() == null) return;
        String message = cfg.general().playerNotFound().replace("{player}", playerName);
        sendPrefixed(sender, message);
    }

    private void sendPrefixed(CommandSender sender, String message) {
        if (sender == null || message == null) return;
        MessagesConfig config = config();
        if (config == null || config.prefix() == null) return;
        sender.sendRichMessage(config.prefix() + message);
    }

    private String withDistance(String message, int distance) {
        if (message == null) return "";
        return message.replace("{distance}", String.valueOf(distance));
    }

    private MessagesConfig config() {
        return configService.messages();
    }
}
