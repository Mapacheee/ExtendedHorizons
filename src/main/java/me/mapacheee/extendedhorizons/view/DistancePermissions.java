package me.mapacheee.extendedhorizons.view;

import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.hooks.LuckPermsHook;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class DistancePermissions implements AutoCloseable {

  private static final String[] NODES = new String[129];

  static {
    for (int distance = 1; distance < NODES.length; distance++) {
      NODES[distance] = "extendedhorizons.max." + distance;
    }
  }

  private final Plugin plugin;
  private final Config settings;
  private final Consumer<UUID> changed;
  private final Runnable groupsChanged;
  private final List<Permission> registered = new ArrayList<>();
  private Runnable unsubscribe;
  private volatile boolean eventDriven;

  DistancePermissions(Plugin plugin, Config settings, Consumer<UUID> changed, Runnable groupsChanged) {
    this.plugin = plugin;
    this.settings = settings;
    this.changed = changed;
    this.groupsChanged = groupsChanged;
    if (this.enabled()) {
      var manager = plugin.getServer().getPluginManager();
      for (int distance = 1; distance < NODES.length; distance++) {
        if (manager.getPermission(NODES[distance]) == null) {
          Permission permission = new Permission(NODES[distance], PermissionDefault.FALSE);
          manager.addPermission(permission);
          this.registered.add(permission);
        }
      }
      this.refreshProvider(plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms"));
    }
  }

  boolean enabled() {
    return this.settings.distancePermissions().enabled();
  }

  boolean eventDriven() {
    return this.eventDriven;
  }

  void refreshProvider(boolean available) {
    this.eventDriven = false;
    if (this.unsubscribe != null) {
      this.unsubscribe.run();
      this.unsubscribe = null;
    }
    if (this.enabled() && available) {
      try {
        this.unsubscribe = LuckPermsHook.register(this.plugin, this.changed, this.groupsChanged);
        this.eventDriven = true;
      } catch (RuntimeException | LinkageError error) {
        this.plugin.getSLF4JLogger().warn("Cannot observe LuckPerms changes; using periodic permission refresh.", error);
      }
    }
  }

  int resolve(Player player) {
    if (player.hasPermission("extendedhorizons.bypass")) {
      return this.settings.distance();
    }
    for (int distance = NODES.length - 1; distance >= 1; distance--) {
      if (player.hasPermission(NODES[distance])) {
        return Math.max(2, Math.min(distance, this.settings.distance()));
      }
    }
    int fallback = this.settings.distancePermissions().defaultDistance();
    return fallback == -1 ? this.settings.distance() : Math.min(fallback, this.settings.distance());
  }

  @Override
  public void close() {
    this.eventDriven = false;
    if (this.unsubscribe != null) {
      this.unsubscribe.run();
      this.unsubscribe = null;
    }
    var manager = this.plugin.getServer().getPluginManager();
    for (Permission permission : this.registered) {
      if (manager.getPermission(permission.getName()) == permission) {
        manager.removePermission(permission);
      }
    }
    this.registered.clear();
  }
}
