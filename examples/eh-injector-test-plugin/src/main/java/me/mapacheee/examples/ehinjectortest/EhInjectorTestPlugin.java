package me.mapacheee.examples.ehinjectortest;

import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.PlayerDistancePreferenceService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class EhInjectorTestPlugin extends JavaPlugin implements CommandExecutor {

  private FakeChunkService fakeChunkService;
  private ConfigService configService;
  private PlayerDistancePreferenceService preferenceService;

  @Override
  public void onEnable() {
    try {
      ExtendedHorizonsPlugin extendedHorizonsPlugin =
          JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
      fakeChunkService = extendedHorizonsPlugin.getInjector().getInstance(FakeChunkService.class);
      configService = extendedHorizonsPlugin.getInjector().getInstance(ConfigService.class);
      preferenceService =
          extendedHorizonsPlugin.getInjector().getInstance(PlayerDistancePreferenceService.class);
      getLogger().info("ExtendedHorizons services hooked");
    } catch (Throwable t) {
      getLogger().warning("ExtendedHorizons services not found");
    }

    PluginCommand cmd = getCommand("ehtest");
    if (cmd != null) {
      cmd.setExecutor(this);
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (fakeChunkService == null || configService == null || preferenceService == null) {
      sender.sendMessage("§cExtendedHorizons services not found");
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage("§eUso: /ehtest <get|set|reset|max|world|far> [value]");
      return true;
    }

    String sub = args[0].toLowerCase();
    switch (sub) {
      case "get" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage("§cOnly players");
          return true;
        }
        Integer current = preferenceService.get(player.getUniqueId());
        sender.sendMessage("§aCustom distance: §f" + current);
      }
      case "set" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage("§cOnly playerss");
          return true;
        }
        if (args.length < 2) {
          sender.sendMessage("§eUse: /ehtest set <chunks>");
          return true;
        }
        int value;
        try {
          value = Integer.parseInt(args[1]);
        } catch (NumberFormatException ignored) {
          sender.sendMessage("§cInvalid number");
          return true;
        }
        fakeChunkService.applyDistancePreference(player, value);
        sender.sendMessage("§aDistance applied: §f" + value);
      }
      case "reset" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage("§cOnly players");
          return true;
        }
        preferenceService.remove(player.getUniqueId());
        sender.sendMessage("§aCustom distant reset");
      }
      case "max" -> {
        Config config = configService.get();
        sender.sendMessage(
            "§aMax global: §f" + (config == null ? "n/a" : config.fakeTargetViewDistance()));
      }
      case "world" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage("§cOnly players");
          return true;
        }
        Config config = configService.get();
        boolean enabled = config != null && config.fakeChunksEnabledForWorld(player.getWorld().getName());
        sender.sendMessage("§aFakeChunks in this world: §f" + enabled);
      }
      case "far" -> {
        Config config = configService.get();
        sender.sendMessage("§aFarPlayers global: §f" + (config != null && config.farPlayersEnabled()));
      }
      default -> sender.sendMessage("§eUse: /ehtest <get|set|reset|max|world|far> [value]");
    }
    return true;
  }
}
