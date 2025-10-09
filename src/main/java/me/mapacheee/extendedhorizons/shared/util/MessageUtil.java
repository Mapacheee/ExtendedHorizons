package me.mapacheee.extendedhorizons.shared.util;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.shared.config.Messages;
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
        Messages messages = configService.getMessages();
        String prefixed = messages.prefix() + message;
        sendMessage(sender, prefixed);
    }

    public void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) {
        String processed = replacePlaceholders(message, placeholders);
        sendMessage(sender, processed);
    }

    public void sendMessageWithPrefix(CommandSender sender, String message, Map<String, String> placeholders) {
        Messages messages = configService.getMessages();
        String prefixed = messages.prefix() + message;
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
        StringBuffer buffer = new StringBuffer();

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
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.general().noPermission());
    }

    public void sendPlayerNotFound(CommandSender sender, String playerName) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.general().playerNotFound(),
                            Map.of("player", playerName));
    }

    public void sendConfigReloaded(CommandSender sender) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.general().configReloaded());
    }

    public void sendCurrentDistance(Player player, int distance) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(player, messages.viewDistance().currentDistance(),
                            Map.of("distance", String.valueOf(distance)));
    }

    public void sendDistanceChanged(Player player, int distance) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(player, messages.viewDistance().distanceChanged(),
                            Map.of("distance", String.valueOf(distance)));
    }

    public void sendDistanceSetOther(CommandSender sender, String playerName, int distance) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.viewDistance().distanceSetOther(),
                            Map.of("player", playerName, "distance", String.valueOf(distance)));
    }

    public void sendMaxDistanceExceeded(CommandSender sender, int maxDistance) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.viewDistance().maxDistanceExceeded(),
                            Map.of("max", String.valueOf(maxDistance)));
    }

    public void sendPermissionRequired(CommandSender sender, String permission) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.viewDistance().permissionRequired(),
                            Map.of("permission", permission));
    }

    public void sendLowTpsWarning(CommandSender sender, double tps) {
        Messages messages = configService.getMessages();
        sendMessageWithPrefix(sender, messages.performance().lowTpsWarning(),
                            Map.of("tps", String.format("%.1f", tps)));
    }

    public void sendHelpMessage(CommandSender sender) {
        Messages messages = configService.getMessages();
        Messages.HelpMessages help = messages.help();

        sendMessage(sender, help.header());
        sendMessage(sender, help.info());
        sendMessage(sender, help.distance());
        sendMessage(sender, help.reset());
        sendMessage(sender, help.reload());
        sendMessage(sender, help.stats());
        sendMessage(sender, help.debug());
        sendMessage(sender, help.world());
        sendMessage(sender, help.footer());
    }
}
