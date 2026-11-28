package me.mapacheee.extendedhorizons.view;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.configurate.module.ConfigurationReloads;
import com.thewinterframework.component.annotation.Component;
import com.thewinterframework.component.annotation.SyncLoading;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import com.thewinterframework.service.annotation.lifecycle.OnReload;
import io.netty.channel.Channel;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mapacheee.extendedhorizons.api.ExtendedHorizonsApi;
import me.mapacheee.extendedhorizons.chunk.ChunkSource;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.hooks.BedrockPlayers;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@SyncLoading
public final class ViewService implements ExtendedHorizonsApi {

  private final Plugin plugin;
  private final Container<Config> config;
  private final Map<UUID, PlayerView> views = new ConcurrentHashMap<>();
  private final Map<UUID, Map<Plugin, Integer>> distanceLimits = new ConcurrentHashMap<>();
  private final Set<Plugin> disabledLimitOwners = Collections.newSetFromMap(new WeakHashMap<>());
  private final ViewMetrics metrics = new ViewMetrics();
  private final BulkChunkUpdates bulkUpdates;
  private final FarPlayerIndex farPlayers = new FarPlayerIndex();
  private final Set<Channel> farConnections = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean farConnectionWarning = new AtomicBoolean();
  private volatile ChunkSource source;
  private volatile Config settings;
  private SendBudget budget;
  private volatile CompressionCache compression;
  private volatile DistancePermissions permissions;
  private boolean apiRegistered;

  @Inject
  public ViewService(Plugin plugin, Container<Config> config) {
    this.plugin = plugin;
    this.config = config;
    this.bulkUpdates = new BulkChunkUpdates(plugin, this.views.values());
  }

  @OnEnable
  public synchronized void start() {
    this.settings = this.config.get();
    this.permissions = new DistancePermissions(this.plugin, this.settings, this::permissionsChanged,
      () -> this.views.values().forEach(PlayerView::permissionsChanged));
    for (int direction = 0; direction <= 8; direction++) {
      ChunkOrder.offsets(this.settings.distance(), direction);
    }
    this.budget = new SendBudget(this.settings.globalBytesPerSecond());
    this.compression = new CompressionCache(this.settings.cacheBytes() / 4);
    this.source = new ChunkSource(this.plugin, this.settings.workers(), this.settings.cacheBytes(),
      this.settings.cacheTtlMillis(), this.settings.globalInFlight(), this.settings.chunkUpdates().enabled(),
      this.settings.antiXray());
    if (this.settings.antiXray().enabled() && this.settings.antiXray().hiddenBlocks().isEmpty()) {
      this.plugin.getSLF4JLogger().warn("ExtendedHorizons Anti-Xray has no additional hidden blocks configured.");
    }
    for (Player player : this.plugin.getServer().getOnlinePlayers()) {
      this.join(player);
    }
    if (!this.apiRegistered) {
      this.apiRegistered = true;
      this.plugin.getServer().getServicesManager().register(ExtendedHorizonsApi.class, this, this.plugin, ServicePriority.Normal);
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

  @OnReload(after = ConfigurationReloads.class)
  public synchronized void reload() {
    if (!this.config.get().equals(this.settings)) {
      this.stopViews();
      this.start();
    } else {
      this.views.values().forEach(PlayerView::permissionsChanged);
    }
  }

  private synchronized void attach(Player player) {
    UUID playerId = player.getUniqueId();
    if (this.source == null || !player.isOnline() || this.views.containsKey(playerId)) {
      return;
    }
    var stream = new ChunkStream(this.source, this.settings, this.metrics, this.budget, this.compression,
      new LongOpenHashSet(player.getSentChunkKeys()), this.settings.chunkUpdates().enabled() && BedrockPlayers.contains(player));
    PlayerView view = new PlayerView(this.plugin, this.source, this.settings, player, stream, this.farPlayers,
      this.permissions, () -> this.distanceLimit(playerId));
    view.start();
    this.views.put(playerId, view);
  }

  @Override
  public Optional<ViewDistance> getViewDistance(UUID playerId) {
    PlayerView view = this.views.get(Objects.requireNonNull(playerId, "playerId"));
    return view == null ? Optional.empty() : Optional.ofNullable(view.viewDistance());
  }

  @Override
  public synchronized boolean setViewDistanceLimit(Plugin owner, UUID playerId, int chunks) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(playerId, "playerId");
    if (chunks < 2 || chunks > 128) {
      throw new IllegalArgumentException("chunks must be between 2 and 128");
    }
    if (!owner.isEnabled() || this.disabledLimitOwners.contains(owner) || !this.views.containsKey(playerId)) {
      return false;
    }
    this.distanceLimits.compute(playerId, (id, previous) -> {
      Map<Plugin, Integer> limits = previous == null ? new HashMap<>() : new HashMap<>(previous);
      limits.put(owner, chunks);
      return Map.copyOf(limits);
    });
    return true;
  }

  @Override
  public synchronized boolean clearViewDistanceLimit(Plugin owner, UUID playerId) {
    Objects.requireNonNull(owner, "owner");
    Map<Plugin, Integer> previous = this.distanceLimits.get(Objects.requireNonNull(playerId, "playerId"));
    if (previous == null || !previous.containsKey(owner)) {
      return false;
    }
    Map<Plugin, Integer> limits = new HashMap<>(previous);
    limits.remove(owner);
    if (limits.isEmpty()) {
      this.distanceLimits.remove(playerId);
    } else {
      this.distanceLimits.put(playerId, Map.copyOf(limits));
    }
    return true;
  }

  synchronized void distanceLimitOwnerEnabled(Plugin owner) {
    this.disabledLimitOwners.remove(owner);
  }

  synchronized void distanceLimitOwnerDisabled(Plugin owner) {
    // Paper fires PluginDisableEvent before changing Plugin.isEnabled().
    this.disabledLimitOwners.add(owner);
    for (UUID playerId : this.distanceLimits.keySet()) {
      this.clearViewDistanceLimit(owner, playerId);
    }
  }

  private int distanceLimit(UUID playerId) {
    int limit = 128;
    Map<Plugin, Integer> limits = this.distanceLimits.get(playerId);
    if (limits != null) {
      for (int chunks : limits.values()) {
        limit = Math.min(limit, chunks);
      }
    }
    return limit;
  }

  void swing(UUID playerId, InteractionHand hand) {
    PlayerView view = this.views.get(playerId);
    if (view != null) {
      view.swing(hand);
    }
  }

  private void permissionsChanged(UUID playerId) {
    PlayerView view = this.views.get(playerId);
    if (view != null) {
      view.permissionsChanged();
    }
  }

  synchronized void permissionProviderChanged(boolean available) {
    if (this.permissions != null) {
      this.permissions.refreshProvider(available);
      this.views.values().forEach(PlayerView::permissionsChanged);
    }
  }

  void hide(UUID viewer, UUID target, boolean hidden) {
    PlayerView view = this.views.get(viewer);
    if (view != null) {
      view.hide(target, hidden);
    }
  }

  public synchronized void detach(UUID playerId) {
    this.distanceLimits.remove(playerId);
    PlayerView view = this.views.remove(playerId);
    if (view != null) {
      view.close();
    }
  }

  public void teleport(UUID playerId, World world, int x, int z) {
    PlayerView view = this.views.get(playerId);
    if (view != null) {
      view.teleport(world, x, z);
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

  public boolean worldEditEnabled(World world) {
    Config config = this.settings;
    ChunkSource current = this.source;
    return current != null && config != null && config.chunkUpdates().enabled() && config.chunkUpdates().worldedit()
      && !config.disabledWorlds().contains(world.getName());
  }

  public void editedChunk(World world, long key) {
    ChunkSource current = this.source;
    if (current == null || !this.worldEditEnabled(world)) {
      return;
    }
    int x = ChunkPos.getX(key);
    int z = ChunkPos.getZ(key);
    current.invalidateEdited(world, x, z);
    this.bulkUpdates.offer(world, key, () -> {
      if (current == this.source) {
        this.refreshChunk(current, world, x, z);
      }
    });
  }

  public int sessionCount() {
    return this.views.size();
  }

  public String farPlayerStatus() {
    Config config = this.settings;
    boolean enabled = config != null && config.farPlayers().enabled();
    return (enabled ? "enabled" : "disabled") + ", " + this.farConnections.size() + " observed connections";
  }

  public String distancePermissionStatus() {
    DistancePermissions current = this.permissions;
    return current == null || !current.enabled() ? "disabled"
      : current.eventDriven() ? "enabled, LuckPerms events" : "enabled, periodic refresh";
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
    this.distanceLimits.clear();
    this.disabledLimitOwners.clear();
    if (this.apiRegistered) {
      this.apiRegistered = false;
      this.plugin.getServer().getServicesManager().unregister(ExtendedHorizonsApi.class, this);
    }
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
    this.bulkUpdates.clear();
    this.views.values().forEach(PlayerView::close);
    this.views.clear();
    if (this.permissions != null) {
      this.permissions.close();
      this.permissions = null;
    }
    if (current != null) {
      current.close();
    }
  }
}
