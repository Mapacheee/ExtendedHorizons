package me.mapacheee.extendedhorizons.hooks;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import me.mapacheee.extendedhorizons.view.ViewService;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldEditHook {

  private final Plugin plugin;
  private final ViewService views;
  private final boolean fawe;
  private final AtomicBoolean warned = new AtomicBoolean();
  private final AtomicBoolean warnedDirectPlacement = new AtomicBoolean();

  private WorldEditHook(Plugin plugin, ViewService views, boolean fawe) {
    this.plugin = plugin;
    this.views = views;
    this.fawe = fawe;
  }

  static Runnable register(Plugin plugin, ViewService views, boolean fawe) {
    var hook = new WorldEditHook(plugin, views, fawe);
    var events = WorldEdit.getInstance().getEventBus();
    events.register(hook);
    return () -> events.unregister(hook);
  }

  @Subscribe
  public void onEdit(EditSessionEvent event) {
    var stage = this.fawe ? EditSession.Stage.BEFORE_HISTORY : EditSession.Stage.BEFORE_CHANGE;
    if (event.getStage() != stage || event.getWorld() == null || event.getExtent() == null) {
      return;
    }
    try {
      var world = BukkitAdapter.adapt(event.getWorld());
      if (world == null || !this.views.worldEditEnabled(world)) {
        return;
      }
      if (this.fawe) {
        if (!FaweChunkProcessor.attach(event, this.plugin, this.views, world)
          && this.warnedDirectPlacement.compareAndSet(false, true)) {
          this.plugin.getSLF4JLogger().warn("FAWE direct placement requires {} in extent.allowed-plugins to refresh extended chunks.",
            ChangedChunkExtent.class.getName());
        }
      } else {
        event.setExtent(new ChangedChunkExtent(event.getExtent(), this.plugin, this.views, world));
      }
    } catch (RuntimeException | LinkageError error) {
      if (this.warned.compareAndSet(false, true)) {
        this.plugin.getSLF4JLogger().warn("Could not track editor changes for extended chunks.", error);
      }
    }
  }
}
