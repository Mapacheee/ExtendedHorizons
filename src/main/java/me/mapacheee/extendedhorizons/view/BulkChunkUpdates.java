package me.mapacheee.extendedhorizons.view;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class BulkChunkUpdates {

  private static final int CHUNKS_PER_TICK = 16;

  private final Plugin plugin;
  private final Collection<PlayerView> views;
  private final LinkedHashMap<Key, Edit> pending = new LinkedHashMap<>();
  private ScheduledTask task;

  BulkChunkUpdates(Plugin plugin, Collection<PlayerView> views) {
    this.plugin = plugin;
    this.views = views;
  }

  synchronized void offer(World world, long key, Runnable capture) {
    if (this.views.isEmpty() || !this.plugin.isEnabled()) {
      return;
    }
    var position = new Key(world, key);
    this.pending.computeIfAbsent(position, ignored -> new Edit(position, capture));
    if (this.task == null) {
      this.schedule();
    }
  }

  private void schedule() {
    try {
      this.task = this.plugin.getServer().getGlobalRegionScheduler().runDelayed(this.plugin, this::drain, 1);
    } catch (IllegalPluginAccessException ignored) {
      this.pending.clear();
    }
  }

  private void drain(ScheduledTask scheduled) {
    var batch = new ArrayList<Edit>(CHUNKS_PER_TICK);
    synchronized (this) {
      if (this.task != scheduled) {
        return;
      }
      var entries = this.pending.values().iterator();
      while (entries.hasNext() && batch.size() < CHUNKS_PER_TICK) {
        batch.add(entries.next());
        entries.remove();
      }
      this.task = null;
      if (!this.pending.isEmpty()) {
        this.schedule();
      }
    }
    for (PlayerView view : this.views) {
      view.checkEdits(batch);
    }
  }

  synchronized void clear() {
    if (this.task != null) {
      this.task.cancel();
      this.task = null;
    }
    this.pending.clear();
  }

  private record Key(World world, long key) {}

  static final class Edit {
    private final Key position;
    private final Runnable capture;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private Edit(Key position, Runnable capture) {
      this.position = position;
      this.capture = capture;
    }

    World world() {
      return this.position.world();
    }

    long key() {
      return this.position.key();
    }

    void request() {
      if (!this.claimed.get() && this.claimed.compareAndSet(false, true)) {
        this.capture.run();
      }
    }
  }
}
