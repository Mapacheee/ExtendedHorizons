package me.mapacheee.extendedhorizons.view;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.component.annotation.Component;
import com.thewinterframework.component.annotation.SyncLoading;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnReload;
import me.mapacheee.extendedhorizons.chunk.ChunkSource;
import me.mapacheee.extendedhorizons.config.Config;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@SyncLoading
public final class ViewService {

  private final Plugin plugin;
  private final Container<Config> config;
  private final Map<UUID, PlayerView> views = new ConcurrentHashMap<>();
  private final ViewMetrics metrics = new ViewMetrics();
  private volatile ChunkSource source;
  private Config settings;
  private SendBudget budget;
  private volatile CompressionCache compression;

  @Inject
  public ViewService(Plugin plugin, Container<Config> config) {
    this.plugin = plugin;
    this.config = config;
  }

  @OnEnable
  public synchronized void start() {
    this.settings = this.config.get();
    for (int direction = 0; direction <= 8; direction++) {
      ChunkOrder.offsets(this.settings.distance(), direction);
    }
    this.budget = new SendBudget(this.settings.globalBytesPerSecond());
    this.compression = new CompressionCache(this.settings.cacheBytes() / 4);
    this.source = new ChunkSource(this.plugin, this.settings.workers(), this.settings.cacheBytes(),
      this.settings.cacheTtlMillis(), this.settings.globalInFlight());
    for (World world : this.plugin.getServer().getWorlds()) {
      if (!this.settings.disabledWorlds().contains(world.getName()) && this.source.protectedWorld(world)) {
        this.plugin.getSLF4JLogger().info("Skipping extended chunks in {} because Paper anti-xray is enabled.", world.getName());
      }
    }
    for (Player player : this.plugin.getServer().getOnlinePlayers()) {
      this.join(player);
    }
    this.plugin.getSLF4JLogger().info("Extended view distance: {} chunks", this.settings.distance());
  }

  public void join(Player player) {
    player.getScheduler().run(this.plugin, task -> this.attach(player), null);
  }

  @OnReload
  public synchronized void reload() {
    if (!this.config.reload()) {
      throw new IllegalStateException("Cannot reload config.yml");
    }
    if (!this.config.get().equals(this.settings)) {
      this.stop();
      this.start();
    }
  }

  private synchronized void attach(Player player) {
    if (this.source == null || !player.isOnline() || this.views.containsKey(player.getUniqueId())) {
      return;
    }
    PlayerView view = new PlayerView(this.plugin, this.source, this.settings, this.metrics, this.budget, this.compression, player);
    view.start();
    this.views.put(player.getUniqueId(), view);
  }

  public void detach(UUID playerId) {
    PlayerView view = this.views.remove(playerId);
    if (view != null) {
      view.close();
    }
  }

  public void teleport(UUID playerId) {
    PlayerView view = this.views.get(playerId);
    if (view != null) {
      view.teleport();
    }
  }

  public void invalidate(World world, int x, int z) {
    ChunkSource current = this.source;
    if (current != null) {
      current.invalidate(world, x, z);
    }
  }

  public void invalidate(World world) {
    ChunkSource current = this.source;
    if (current != null) {
      current.invalidate(world);
    }
  }

  public int sessionCount() {
    return this.views.size();
  }

  public Map<String, Long> deliveryStats() {
    var stats = new HashMap<>(this.metrics.snapshot());
    CompressionCache current = this.compression;
    if (current != null) {
      stats.putAll(current.stats());
    }
    return Map.copyOf(stats);
  }

  public Map<String, Long> sourceStats() {
    ChunkSource current = this.source;
    return current == null ? Map.of() : current.stats();
  }

  @OnDisable
  public synchronized void stop() {
    ChunkSource current = this.source;
    this.source = null;
    this.views.values().forEach(PlayerView::close);
    this.views.clear();
    if (current != null) {
      current.close();
    }
  }
}
