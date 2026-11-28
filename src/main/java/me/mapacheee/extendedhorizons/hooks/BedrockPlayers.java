package me.mapacheee.extendedhorizons.hooks;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

public final class BedrockPlayers {

  private BedrockPlayers() {}

  public static boolean contains(Player player) {
    var plugins = player.getServer().getPluginManager();
    UUID id = player.getUniqueId();
    return contains(plugins.getPlugin("floodgate"), "org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", id)
      || contains(plugins.getPlugin("Geyser-Spigot"), "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", id);
  }

  private static boolean contains(Plugin plugin, String className, String instance, String lookup, UUID id) {
    if (plugin == null || !plugin.isEnabled()) {
      return false;
    }
    try {
      Class<?> api = Class.forName(className, true, plugin.getClass().getClassLoader());
      return Boolean.TRUE.equals(api.getMethod(lookup, UUID.class).invoke(api.getMethod(instance).invoke(null), id));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      return false;
    }
  }
}
