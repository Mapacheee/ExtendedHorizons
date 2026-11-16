package me.mapacheee.extendedhorizons.hooks;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.context.ContextUpdateEvent;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.ArrayList;
import java.util.function.Consumer;

public final class LuckPermsHook {

  private LuckPermsHook() {
  }

  public static Runnable register(Plugin plugin, Consumer<UUID> changed, Runnable groupsChanged) {
    var events = LuckPermsProvider.get().getEventBus();
    var subscriptions = new ArrayList<EventSubscription<?>>();
    try {
      subscriptions.add(events.subscribe(plugin, UserDataRecalculateEvent.class, event -> changed.accept(event.getUser().getUniqueId())));
      subscriptions.add(events.subscribe(plugin, NodeMutateEvent.class, event -> {
        if (event.isGroup()) {
          groupsChanged.run();
        }
      }));
      subscriptions.add(events.subscribe(plugin, GroupDataRecalculateEvent.class, event -> groupsChanged.run()));
      subscriptions.add(events.subscribe(plugin, ContextUpdateEvent.class, event -> {
        if (event.getSubject() instanceof Player player) {
          changed.accept(player.getUniqueId());
        }
      }));
      return () -> subscriptions.forEach(EventSubscription::close);
    } catch (RuntimeException | LinkageError error) {
      subscriptions.forEach(EventSubscription::close);
      throw error;
    }
  }
}
