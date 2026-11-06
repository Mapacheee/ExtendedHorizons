package me.mapacheee.extendedhorizons.view;

import io.netty.channel.Channel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.chunk.ChunkSource;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerView {

  private final Plugin plugin;
  private final ChunkSource source;
  private final Config settings;
  private final Player player;
  private final Channel channel;
  private final ChunkStream stream;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<ViewSnapshot> latest = new AtomicReference<>();
  private final Location location = new Location(null, 0, 0, 0);
  private volatile ScheduledTask task;
  private World lastWorld;
  private ViewSnapshot lastPublished;
  private double lastX;
  private double lastZ;

  public PlayerView(Plugin plugin, ChunkSource source, Config settings, ViewMetrics metrics, SendBudget global,
    CompressionCache compression, Player player) {
    this.plugin = plugin;
    this.source = source;
    this.settings = settings;
    this.player = player;
    this.channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
    this.stream = new ChunkStream(source, settings, metrics, global, compression, new LongOpenHashSet(player.getSentChunkKeys()));
  }

  public void start() {
    this.channel.eventLoop().execute(() -> {
      if (!this.closed.get() && this.channel.isActive()) {
        this.channel.pipeline().addBefore("packet_handler", "extendedhorizons_view", this.stream);
      }
    });
    this.task = this.player.getScheduler().runAtFixedRate(this.plugin, tick -> this.tick(), this::close, 1L, 1L);
    if (this.task == null || this.closed.get()) {
      this.close();
    }
  }

  private void tick() {
    if (this.closed.get()) {
      return;
    }
    this.player.getLocation(this.location);
    World world = this.location.getWorld();
    double x = this.location.getX();
    double z = this.location.getZ();
    int direction = world == this.lastWorld ? ChunkOrder.direction(x - this.lastX, z - this.lastZ) : 0;
    this.lastX = x;
    this.lastZ = z;
    this.lastWorld = world;
    int nativeDistance = this.player.getSendViewDistance();
    if (nativeDistance < 2) {
      nativeDistance = world.getViewDistance();
    }
    int client = Math.max(2, this.player.getClientViewDistance());
    nativeDistance = Math.min(nativeDistance, client);
    int distance = Math.min(this.settings.distance(), client);
    boolean enabled = distance > nativeDistance && !this.settings.disabledWorlds().contains(world.getName())
      && !this.source.protectedWorld(world);
    ViewSnapshot next = new ViewSnapshot(world, this.location.getBlockX() >> 4, this.location.getBlockZ() >> 4,
      nativeDistance, distance, direction, enabled);
    if (next.equals(this.lastPublished) && this.stream.sleeping()) {
      return;
    }
    this.lastPublished = next;
    if (this.latest.getAndSet(next) == null) {
      this.channel.eventLoop().execute(() -> {
        ViewSnapshot snapshot = this.latest.getAndSet(null);
        if (snapshot != null && !this.closed.get()) {
          this.stream.tick(snapshot);
        }
      });
    }
  }

  public void teleport() {
    this.latest.set(null);
    this.channel.eventLoop().execute(this.stream::teleport);
  }

  public void close() {
    ScheduledTask scheduled = this.task;
    if (scheduled != null) {
      scheduled.cancel();
    }
    if (this.closed.compareAndSet(false, true)) {
      this.latest.set(null);
      this.channel.eventLoop().execute(() -> {
        if (this.channel.pipeline().context(this.stream) != null) {
          this.stream.restore();
          this.channel.pipeline().remove(this.stream);
        }
      });
    }
  }
}
