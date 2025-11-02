package me.mapacheee.extendedhorizons.shared.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.MessageConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.regex.Pattern;

/*
 *   Centralized message sender with one method per message in use
 *   Parses MiniMessage and applies the configured prefix consistently
 *   Provides a help sender that filters [ADMIN] lines when needed
*/
@Service
public class MessageService {

    private static final Pattern HEX_AMP = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final ConfigService configService;
    private final MiniMessage miniMessage;

    @Inject
    public MessageService(ConfigService configService) {
        this.configService = configService;
        this.miniMessage = MiniMessage.miniMessage();
    }

    private String preprocess(String message) {
        if (message == null) return "";
        return HEX_AMP.matcher(message).replaceAll("<#$1>");
    }

    private void sendPrefixed(CommandSender sender, String message) {
        MessageConfig messages = configService.messages();
        String formatted = messages.prefix() + message;
        sender.sendMessage(miniMessage.deserialize(preprocess(formatted)));
    }

    private void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(miniMessage.deserialize(preprocess(message)));
    }

    public void sendNoPermission(CommandSender sender) {
        sendPrefixed(sender, configService.messages().general().noPermission());
    }

    public void sendPlayerOnly(CommandSender sender) {
        sendPrefixed(sender, configService.messages().general().playerOnly());
    }

    public void sendConfigReloaded(CommandSender sender) {
        sendPrefixed(sender, configService.messages().general().configReloaded());
    }

    public void sendPluginInfo(CommandSender sender, String plugin, String version, String author) {
        String msg = configService.messages().general().pluginInfo()
                .replace("{plugin}", plugin)
                .replace("{version}", version)
                .replace("{author}", author);
        sendPrefixed(sender, msg);
    }

    public void sendCurrentDistance(CommandSender sender, int distance) {
        sendPrefixed(sender, configService.messages().viewDistance().currentDistance().replace("{distance}", String.valueOf(distance)));
    }

    public void sendNoViewData(CommandSender sender) {
        sendPrefixed(sender, configService.messages().viewDistance().noViewData());
    }

    public void sendDistanceChanged(CommandSender sender, int distance) {
        sendPrefixed(sender, configService.messages().viewDistance().distanceChanged().replace("{distance}", String.valueOf(distance)));
    }

    public void sendMinDistanceError(CommandSender sender, int min) {
        sendPrefixed(sender, configService.messages().viewDistance().minDistanceError().replace("{min}", String.valueOf(min)));
    }

    public void sendMaxDistanceExceeded(CommandSender sender, int max) {
        sendPrefixed(sender, configService.messages().viewDistance().maxDistanceExceeded().replace("{max}", String.valueOf(max)));
    }

    public void sendReset(CommandSender sender, int def) {
        sendPrefixed(sender, configService.messages().viewDistance().reset().replace("{distance}", String.valueOf(def)));
    }

    public void sendOtherCurrentDistance(CommandSender sender, String playerName, int distance) {
        String msg = configService.messages().viewDistance().otherCurrentDistance()
                .replace("{player}", playerName)
                .replace("{distance}", String.valueOf(distance));
        sendPrefixed(sender, msg);
    }

    public void sendNoViewDataOther(CommandSender sender, String playerName) {
        sendPrefixed(sender, configService.messages().viewDistance().noViewDataOther().replace("{player}", playerName));
    }

    public void sendDistanceSetOther(CommandSender sender, String playerName, int distance) {
        String msg = configService.messages().viewDistance().distanceSetOther()
                .replace("{player}", playerName)
                .replace("{distance}", String.valueOf(distance));
        sendPrefixed(sender, msg);
    }

    public void sendStats(CommandSender sender, int online, int maxPlayers, int averageDistance, int cachedChunks, int maxCached, int cacheSizeMb) {
        java.util.List<String> lines = configService.messages().stats();
        if (lines == null) return;

        double cacheUsagePercent = maxCached > 0 ? (cachedChunks * 100.0 / maxCached) : 0.0;

        for (String line : lines) {
            String out = line
                    .replace("{online}", String.valueOf(online))
                    .replace("{max}", String.valueOf(maxPlayers))
                    .replace("{distance}", String.valueOf(averageDistance))
                    .replace("{cached_chunks}", String.valueOf(cachedChunks))
                    .replace("{max_cached}", String.valueOf(maxCached))
                    .replace("{cache_usage}", String.format("%.1f%%", cacheUsagePercent))
                    .replace("{size}", String.valueOf(cacheSizeMb));
            sendRaw(sender, out);
        }
    }

    public void sendWorldNotFound(CommandSender sender, String world) {
        sendPrefixed(sender, configService.messages().world().notFound().replace("{world}", world));
    }
    public void sendWorldMaxDistanceInfo(CommandSender sender, String world, int distance) {
        sendPrefixed(sender, configService.messages().world().maxDistanceInfo().replace("{world}", world).replace("{distance}", String.valueOf(distance)));
    }
    public void sendWorldUsage(CommandSender sender) {
        sendPrefixed(sender, configService.messages().world().usage());
    }
    public void sendWorldConfigNotice(CommandSender sender) {
        sendPrefixed(sender, configService.messages().world().configNotice());
    }

    public void sendHelp(CommandSender sender, boolean isAdmin) {
        List<String> lines = configService.messages().help();
        if (lines == null) return;
        for (String line : lines) {
            boolean adminLine = line.startsWith("[ADMIN]");
            if (adminLine) {
                if (!isAdmin) continue;
                line = line.substring("[ADMIN]".length()).trim();
            }
            sendRaw(sender, line);
        }
    }

    public void sendWelcome(CommandSender sender, int distance) {
        var m = configService.messages();
        if (m.messages() == null || m.messages().welcomeMessage() == null || m.messages().welcomeMessage().text() == null) return;
        String txt = m.messages().welcomeMessage().text().replace("{distance}", String.valueOf(distance));
        sendPrefixed(sender, txt);
    }
}
