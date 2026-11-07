package me.mapacheee.extendedhorizons.view;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.component.annotation.Component;
import com.thewinterframework.component.annotation.SyncLoading;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnReload;
import io.netty.channel.Channel;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mapacheee.extendedhorizons.chunk.ChunkSource;
import me.mapacheee.extendedhorizons.config.Config;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@SyncLoading
public final class ViewService {

  private final Plugin plugin;
  private final Container<Config> config;
  private final Map<UUID, PlayerView> views = new ConcurrentHashMap<>();
  private final ViewMetrics metrics = new ViewMetrics();
  private final FarPlayerIndex farPlayers = new FarPlayerIndex();
  private final Set<Channel> farConnections = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean farConnectionWarning = new AtomicBoolean();
  private volatile ChunkSource source;
  private volatile Config settings;
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
      this.settings.cacheTtlMillis(), this.settings.globalInFlight(), this.settings.chunkUpdates().enabled());
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

  void configure(PlayerConfigurationConnection connection) {
    if (this.source == null) {
      return;
    }
    try {
      Channel channel = FarPlayerChannel.connection(connection);
      if (channel != null) {
        channel.eventLoop().execute(() -> {
          if (this.source != null && channel.isActive() && channel.pipeline().get(FarPlayerChannel.NAME) == null) {
            channel.pipeline().addBefore("packet_handler", FarPlayerChannel.NAME, new FarPlayerChannel());
            this.farConnections.add(channel);
            channel.closeFuture().addListener(ignored -> this.farConnections.remove(channel));
          }
        });
      }
    } catch (RuntimeException | LinkageError | ReflectiveOperationException error) {
      if (this.farConnectionWarning.compareAndSet(false, true)) {
        this.plugin.getSLF4JLogger().warn("Cannot observe far player profiles", error);
      }
    }
  }

  @OnReload
  public synchronized void reload() {
    if (!this.config.reload()) {
      throw new IllegalStateException("Cannot reload config.yml");
    }
    if (!this.config.get().equals(this.settings)) {
      this.stopViews();
      this.start();
    }
  }

  private synchronized void attach(Player player) {
    if (this.source == null || !player.isOnline() || this.views.containsKey(player.getUniqueId())) {
      return;
    }
    var stream = new ChunkStream(this.source, this.settings, this.metrics, this.budget, this.compression,
      new LongOpenHashSet(player.getSentChunkKeys()));
    PlayerView view = new PlayerView(this.plugin, this.source, this.settings, player, stream, this.farPlayers);
    view.start();
    this.views.put(player.getUniqueId(), view);
  }

  void swing(UUID playerId, InteractionHand hand) {
    PlayerView view = this.views.get(playerId);
    if (view != null) {
      view.swing(hand);
    }
  }

  void hide(UUID viewer, UUID target, boolean hidden) {
    PlayerView view = this.views.get(viewer);
    if (view != null) {
      view.hide(target, hidden);
    }
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

  void chunkChanged(Block block) {
    this.blockChanged(block.getWorld(), block.getX(), block.getZ());
  }

  void blockChanged(World world, int blockX, int blockZ) {
    ChunkSource current = this.source;
    Config config = this.settings;
    if (current == null || config == null || !config.chunkUpdates().enabled() || config.disabledWorlds().contains(world.getName())) {
      return;
    }
    int x = blockX >> 4;
    int z = blockZ >> 4;
    this.refreshChunk(current, world, x, z);
    int edgeX = (blockX & 15) == 0 ? -1 : (blockX & 15) == 15 ? 1 : 0;
    int edgeZ = (blockZ & 15) == 0 ? -1 : (blockZ & 15) == 15 ? 1 : 0;
    if (edgeX != 0) {
      this.refreshChunk(current, world, x + edgeX, z);
    }
    if (edgeZ != 0) {
      this.refreshChunk(current, world, x, z + edgeZ);
    }
  }

  private void refreshChunk(ChunkSource current, World world, int x, int z) {
    var result = current.refresh(world, x, z);
    if (result != null) {
      this.metrics.refreshes.increment();
      result.thenAccept(data -> {
        if (data != null && data.current() && current == this.source) {
          for (PlayerView view : this.views.values()) {
            view.updateChunk(world, x, z, data);
          }
        }
      });
    }
  }

  public int sessionCount() {
    return this.views.size();
  }

  public String farPlayerStatus() {
    Config config = this.settings;
    boolean enabled = config != null && config.farPlayers().enabled();
    return (enabled ? "enabled" : "disabled") + ", " + this.farConnections.size() + " observed connections";
  }

  public void inspectFarPlayer(Player observer, String targetName) {
    var viewer = this.views.get(observer.getUniqueId());
    var player = this.plugin.getServer().getPlayerExact(targetName);
    var target = player == null ? null : this.views.get(player.getUniqueId());
    if (viewer == null || target == null || viewer == target) {
      observer.sendMessage("Far players: select another online player with an active EH session.");
      return;
    }
    viewer.inspectFarPlayer(target);
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
    this.stopViews();
    for (Channel channel : this.farConnections) {
      channel.eventLoop().execute(() -> {
        if (channel.pipeline().get(FarPlayerChannel.NAME) != null) {
          channel.pipeline().remove(FarPlayerChannel.NAME);
        }
      });
    }
    this.farConnections.clear();
  }

  private void stopViews() {
    ChunkSource current = this.source;
    this.source = null;
    this.views.values().forEach(PlayerView::close);
    this.views.clear();
    if (current != null) {
      current.close();
    }
  }
}
