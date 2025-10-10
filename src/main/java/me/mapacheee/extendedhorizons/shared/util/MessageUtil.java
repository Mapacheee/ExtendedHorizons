package me.mapacheee.extendedhorizons.shared.util;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Message utility for handling formatted messages with HEX color support
 * Provides centralized message sending with placeholder replacement
 */

@Service
public class MessageUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacySection();

    private final ConfigService configService;

    @Inject
    public MessageUtil(ConfigService configService) {
        this.configService = configService;
    }

    public void sendMessage(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) return;

        String formatted = formatMessage(message);
        Component component = SERIALIZER.deserialize(formatted);
        sender.sendMessage(component);
    }

    public void sendMessageWithPrefix(CommandSender sender, String message) {
        String prefix = configService.getPrefix();
        String prefixed = prefix + message;
        sendMessage(sender, prefixed);
    }

    public void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) {
        String processed = replacePlaceholders(message, placeholders);
        sendMessage(sender, processed);
    }

    public void sendMessageWithPrefix(CommandSender sender, String message, Map<String, String> placeholders) {
        String prefix = configService.getPrefix();
        String prefixed = prefix + message;
        sendMessage(sender, prefixed, placeholders);
    }

    public String formatMessage(String message) {
        if (message == null) return "";

        String formatted = translateHexColors(message);
        formatted = formatted.replace("&", "§");
        return formatted;
    }

    public String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (message == null || placeholders == null) return message;

        String result = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String translateHexColors(String message) {
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder buffer = new StringBuilder();

        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(buffer, "§x§" + hex.charAt(0) + "§" + hex.charAt(1) +
                                    "§" + hex.charAt(2) + "§" + hex.charAt(3) +
                                    "§" + hex.charAt(4) + "§" + hex.charAt(5));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public void sendNoPermission(CommandSender sender) {
        String message = configService.getNoPermissionMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendPlayerNotFound(CommandSender sender, String playerName) {
        String message = configService.getPlayerNotFoundMessage();
        sendMessageWithPrefix(sender, message, Map.of("player", playerName));
    }

    public void sendConfigReloaded(CommandSender sender) {
        String message = configService.getConfigReloadedMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendCurrentDistance(Player player, int distance) {
        String message = configService.getCurrentDistanceMessage();
        sendMessageWithPrefix(player, message, Map.of("distance", String.valueOf(distance)));
    }

    public void sendDistanceChanged(Player player, int distance) {
        String message = configService.getDistanceChangedMessage();
        sendMessageWithPrefix(player, message, Map.of("distance", String.valueOf(distance)));
    }

    public void sendMaxDistanceExceeded(CommandSender sender, int maxDistance) {
        String message = configService.getMaxDistanceExceededMessage();
        sendMessageWithPrefix(sender, message, Map.of("max", String.valueOf(maxDistance)));
    }

    public void sendMinDistanceError(CommandSender sender, int minDistance) {
        String message = configService.getMinDistanceErrorMessage();
        sendMessageWithPrefix(sender, message, Map.of("min", String.valueOf(minDistance)));
    }

    public void sendDistanceSetOther(CommandSender sender, String playerName, int distance) {
        String message = configService.getDistanceSetOtherMessage();
        sendMessageWithPrefix(sender, message, Map.of("player", playerName, "distance", String.valueOf(distance)));
    }

    public void sendHelpMessage(CommandSender sender) {
        sendMessage(sender, configService.getHelpHeaderMessage());
        sendMessage(sender, configService.getHelpInfoMessage());
        sendMessage(sender, configService.getHelpDistanceMessage());
        sendMessage(sender, configService.getHelpResetMessage());

        if (sender.hasPermission("extendedhorizons.admin")) {
            sendMessage(sender, configService.getHelpAdminHeaderMessage());
            sendMessage(sender, configService.getHelpReloadMessage());
            sendMessage(sender, configService.getHelpStatsMessage());
            sendMessage(sender, configService.getHelpDebugMessage());
            sendMessage(sender, configService.getHelpWorldMessage());
        }

        sendMessage(sender, configService.getHelpFooterMessage());
    }

    // Additional utility methods for the command
    public void sendNoViewData(CommandSender sender) {
        String message = configService.getNoViewDataMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendInvalidDistance(CommandSender sender, String distance) {
        String message = configService.getInvalidDistanceMessage();
        sendMessageWithPrefix(sender, message, Map.of("distance", distance));
    }

    public void sendViewDistanceReset(CommandSender sender, int distance) {
        String message = configService.getViewDistanceResetMessage();
        sendMessageWithPrefix(sender, message, Map.of("distance", String.valueOf(distance)));
    }

    public void sendConfigError(CommandSender sender) {
        String message = configService.getConfigErrorMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendWorldNotFound(CommandSender sender, String worldName) {
        String message = configService.getWorldNotFoundMessage();
        sendMessageWithPrefix(sender, message, Map.of("world", worldName));
    }

    public void sendWorldUsage(CommandSender sender) {
        String message = configService.getWorldUsageMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendWorldConfigNotice(CommandSender sender) {
        String message = configService.getWorldConfigNoticeMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendDebugStatus(CommandSender sender, boolean enabled) {
        String message = enabled ? configService.getDebugEnabledMessage() : configService.getDebugDisabledMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendUnknownCommand(CommandSender sender) {
        String message = configService.getUnknownCommandMessage();
        sendMessageWithPrefix(sender, message);
    }

    public void sendWelcomeMessage(Player player, int distance) {
        String message = configService.getWelcomeMessage();
        sendMessageWithPrefix(player, message, Map.of("distance", String.valueOf(distance)));
    }

    // Stats message methods
    public void sendStatsHeader(CommandSender sender) {
        String message = configService.getStatsHeaderMessage();
        sendMessage(sender, message);
    }

    public void sendStatsPlayersOnline(CommandSender sender, int online, int max) {
        String message = configService.getStatsPlayersOnlineMessage();
        sendMessage(sender, message, Map.of("online", String.valueOf(online), "max", String.valueOf(max)));
    }

    public void sendStatsAverageDistance(CommandSender sender, double distance) {
        String message = configService.getStatsAverageDistanceMessage();
        sendMessage(sender, message, Map.of("distance", String.format("%.1f", distance)));
    }

    public void sendStatsChunksSent(CommandSender sender, int chunks) {
        String message = configService.getStatsChunksSentMessage();
        sendMessage(sender, message, Map.of("chunks", String.valueOf(chunks)));
    }

    public void sendStatsFakeChunksSent(CommandSender sender, int chunks) {
        String message = configService.getStatsFakeChunksSentMessage();
        sendMessage(sender, message, Map.of("chunks", String.valueOf(chunks)));
    }

    public void sendStatsCacheSize(CommandSender sender, double size) {
        String message = configService.getStatsCacheSizeMessage();
        sendMessage(sender, message, Map.of("size", String.valueOf(size)));
    }

    public void sendStatsFooter(CommandSender sender) {
        String message = configService.getStatsFooterMessage();
        sendMessage(sender, message);
    }
}
