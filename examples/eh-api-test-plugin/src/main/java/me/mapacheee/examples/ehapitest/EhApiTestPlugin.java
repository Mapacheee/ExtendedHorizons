package me.mapacheee.examples.ehapitest;

import me.mapacheee.extendedhorizons.api.ExtendedHorizonsApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class EhApiTestPlugin extends JavaPlugin implements CommandExecutor {

  private ExtendedHorizonsApi api;

  @Override
  public void onEnable() {
    RegisteredServiceProvider<ExtendedHorizonsApi> provider =
        Bukkit.getServicesManager().getRegistration(ExtendedHorizonsApi.class);
    if (provider != null) {
      api = provider.getProvider();
      getLogger().info("ExtendedHorizons API hooked");
    } else {
      getLogger().warning("ExtendedHorizons API not found");
    }

    PluginCommand cmd = getCommand("ehtest");
    if (cmd != null) {
      cmd.setExecutor(this);
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (api == null) {
      sender.sendMessage("§cExtendedHorizons API not found");
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
        int current = api.getPlayerViewDistance(player);
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
        api.setPlayerViewDistance(player, value);
        sender.sendMessage("§aDistance applied: §f" + value);
      }
      case "reset" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage("§cOnly players");
          return true;
        }
        api.resetPlayerViewDistance(player);
        sender.sendMessage("§aCustom distant reset");
      }
      case "max" -> sender.sendMessage("§aMax global: §f" + api.getServerMaxViewDistance());
      case "world" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage("§cOnly players");
          return true;
        }
        boolean enabled = api.isFakeChunksEnabled(player.getWorld().getName());
        sender.sendMessage("§aFakeChunks in this world: §f" + enabled);
      }
      case "far" -> sender.sendMessage("§aFarPlayers global: §f" + api.isFarPlayersEnabled());
      default -> sender.sendMessage("§eUse: /ehtest <get|set|reset|max|world|far> [value]");
    }
    return true;
  }
}
