package me.mapacheee.extendedhorizons.view;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.chunk.ChunkData;
import me.mapacheee.extendedhorizons.chunk.ChunkSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class ChunkStream extends ChannelDuplexHandler {

  private static final int RETAIN_MARGIN = 2;

  private final ChunkSource source;
  private final ChunkCompression compression;
  private final Config settings;
  private final ViewMetrics metrics;
  private final SendBudget globalBudget;
  private final SendBudget playerBudget;
  private final SendBudget.Share share;
  private final LongOpenHashSet nativeChunks;
  private final LongOpenHashSet extended = new LongOpenHashSet();
  private final LongOpenHashSet pending = new LongOpenHashSet();
  private final Long2LongOpenHashMap retries = new Long2LongOpenHashMap();
  private final LongArrayFIFOQueue edges = new LongArrayFIFOQueue();
  private final ArrayDeque<ReadyChunk> ready = new ArrayDeque<>();
  private final Long2ObjectLinkedOpenHashMap<ChunkData> updates;
  private ChannelHandlerContext context;
  private volatile FarPlayerChannel farPlayers;
  private ResourceKey<?> expectedDimension;
  private ViewSnapshot view;
  private long epoch;
  private int advertised = -1;
  private int cursor;
  private long nextRetry = Long.MAX_VALUE;
  private volatile long sleepUntil;
  private boolean needsFlush;

  ChunkStream(ChunkSource source, Config settings, ViewMetrics metrics, SendBudget global,
    CompressionCache compression, LongOpenHashSet nativeChunks) {
    this.source = source;
    this.compression = new ChunkCompression(compression, metrics);
    this.settings = settings;
    this.metrics = metrics;
    this.globalBudget = global;
    this.share = global.share();
    this.playerBudget = new SendBudget(settings.playerBytesPerSecond());
    this.nativeChunks = nativeChunks;
    this.updates = settings.chunkUpdates().enabled() ? new Long2ObjectLinkedOpenHashMap<>() : null;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    this.context = ctx;
    if (this.settings.farPlayers().enabled()) {
      this.farPlayers = (FarPlayerChannel) ctx.pipeline().get(FarPlayerChannel.NAME);
    }
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    this.reset(false, false);
    this.compression.remove(ctx);
    this.globalBudget.remove(this.share);
    this.context = null;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
    this.compression.invalidate(message);
    boolean transition = message instanceof ClientboundLevelChunkWithLightPacket chunk
      && this.extended.contains(ChunkPos.pack(chunk.x(), chunk.z()));
    Object outgoing = message instanceof ClientboundBundlePacket bundle ? this.bundle(bundle) : this.observe(message);
    if (outgoing == null) {
      promise.trySuccess();
    } else if (outgoing instanceof ClientboundLevelChunkWithLightPacket chunk) {
      this.compression.writeChunk(ctx, chunk, promise, transition);
    } else {
      ctx.write(outgoing, promise);
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object message) {
    if (message instanceof ServerboundPlayerActionPacket || message instanceof ServerboundUseItemOnPacket
      || message instanceof ServerboundUseItemPacket) {
      this.compression.clear();
    }
    ctx.fireChannelRead(message);
  }

  private Object bundle(ClientboundBundlePacket bundle) {
    for (var sub : bundle.subPackets()) {
      if (sub instanceof ClientboundForgetLevelChunkPacket || sub instanceof ClientboundSetChunkCacheRadiusPacket
        || sub instanceof ClientboundLevelChunkWithLightPacket || sub instanceof ClientboundRespawnPacket
        || this.suppressMaskedUpdate(sub)) {
        var packets = new ArrayList<Packet<? super ClientGamePacketListener>>();
        for (var packet : bundle.subPackets()) {
          Object result = this.observe(packet);
          if (result == packet) {
            packets.add(packet);
          } else if (result instanceof ClientboundSetChunkCacheRadiusPacket radius) {
            packets.add(radius);
          }
        }
        return packets.isEmpty() ? null : new ClientboundBundlePacket(packets);
      }
    }
    return bundle;
  }

  private Object observe(Object packet) {
    if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
      long key = ChunkPos.pack(chunk.x(), chunk.z());
      this.nativeChunks.add(key);
      this.extended.remove(key);
      if (this.updates != null) {
        this.updates.remove(key);
      }
    } else if (packet instanceof ClientboundForgetLevelChunkPacket(ChunkPos pos)) {
      long key = pos.pack();
      boolean known = this.nativeChunks.remove(key);
      if (known && this.retained(key) && this.source.masking()) {
        this.retries.remove(key);
        this.edges.enqueue(key);
        this.sleepUntil = 0;
      } else if (this.retained(key) && (known || this.extended.contains(key))) {
        if (Math.abs(pos.x() - this.view.x()) <= this.view.nativeDistance()
          && Math.abs(pos.z() - this.view.z()) <= this.view.nativeDistance()) {
          this.compression.forget(key);
        }
        this.extended.add(key);
        return null;
      }
      this.extended.remove(key);
      if (this.updates != null) {
        this.updates.remove(key);
      }
      this.compression.forget(key);
      if (this.farPlayers != null) {
        this.farPlayers.forget(key);
      }
    } else if (packet instanceof ClientboundSetChunkCacheRadiusPacket radius) {
      this.compression.clear();
      this.advertised = radius.getRadius();
      if (this.view != null && this.view.enabled() && this.advertised < this.view.distance()) {
        this.advertised = this.view.distance();
        return new ClientboundSetChunkCacheRadiusPacket(this.advertised);
      }
    } else if (packet instanceof ClientboundRespawnPacket respawn) {
      this.reset(false, false);
      this.view = null;
      this.expectedDimension = respawn.commonPlayerSpawnInfo().dimension();
      this.advertised = -1;
      this.sleepUntil = 0;
    } else if (this.suppressMaskedUpdate(packet)) {
      return null;
    }
    return packet;
  }

  private boolean suppressMaskedUpdate(Object packet) {
    if (!this.source.masking() || this.extended.isEmpty()) {
      return false;
    }
    if (packet instanceof ClientboundBlockUpdatePacket update) {
      return this.maskedChunk(update.getPos());
    }
    if (packet instanceof ClientboundBlockEntityDataPacket update) {
      return this.maskedChunk(update.getPos());
    }
    if (packet instanceof ClientboundBlockEventPacket event) {
      return this.maskedChunk(event.getPos());
    }
    if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
      boolean[] result = new boolean[2];
      update.runUpdates((pos, state) -> {
        if (!result[0]) {
          result[0] = true;
          result[1] = this.extended.contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        }
      });
      return result[1];
    }
    return false;
  }

  private boolean maskedChunk(BlockPos pos) {
    return this.extended.contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
  }

  void tick(ViewSnapshot next, List<FarPlayerState.Snapshot> candidates) {
    if (this.context == null || !this.context.channel().isActive()) {
      return;
    }
    if (this.expectedDimension != null && this.expectedDimension != ((CraftWorld) next.world()).getHandle().dimension()) {
      return;
    }
    this.expectedDimension = null;
    ViewSnapshot previous = this.view;
    if (previous != null && previous.world() != next.world()) {
      this.reset(false, false);
      this.advertised = -1;
      previous = null;
    }
    this.view = next;
    if (!next.enabled()) {
      if (previous != null && previous.enabled()) {
        this.reset(true, true);
        this.setRadius(next.nativeDistance());
        this.flush();
      }
      this.sleepUntil = Long.MAX_VALUE;
      return;
    }
    this.setRadius(next.distance());
    if (previous == null || !previous.enabled() || previous.x() != next.x() || previous.z() != next.z()
      || previous.distance() != next.distance() || previous.nativeDistance() != next.nativeDistance()) {
      boolean incremental = previous != null && previous.enabled() && previous.distance() == next.distance()
        && previous.nativeDistance() == next.nativeDistance() && Math.abs(previous.x() - next.x()) <= 1
        && Math.abs(previous.z() - next.z()) <= 1;
      if (previous != null && previous.enabled()) {
        this.trim(previous, next);
      }
      if (incremental) {
        this.queueEdges(previous, next);
      } else {
        this.edges.clear();
      }
      if (!incremental || this.cursor < this.area()) {
        this.cursor = 0;
      }
    } else if (previous.direction() != next.direction() && this.cursor < this.area()) {
      this.cursor = 0;
    }
    long now = System.nanoTime();
    if (this.cursor >= this.area() && now >= this.nextRetry) {
      this.nextRetry = Long.MAX_VALUE;
      this.cursor = 0;
    }
    if (this.context.channel().isWritable()) {
      int limit = this.settings.sendsPerTick();
      int sent = this.updates == null ? 0 : this.sendUpdates(Math.max(1, limit / 4), now);
      sent += this.sendReady(limit - sent, now);
      this.fill(now);
      this.sendReady(limit - sent, now);
    } else {
      this.metrics.throttled.increment();
    }
    if (candidates != null && this.farPlayers != null && this.farPlayers.sync(this, candidates, this.settings.farPlayers())) {
      this.needsFlush = true;
    }
    this.flush();
    boolean updating = this.updates != null && !this.updates.isEmpty();
    this.sleepUntil = !updating && this.pending.isEmpty() && this.edges.isEmpty() && this.cursor >= this.area() ? this.nextRetry : 0;
  }

  boolean sleeping() {
    return System.nanoTime() < this.sleepUntil;
  }

  private void flush() {
    if (this.needsFlush) {
      this.needsFlush = false;
      this.context.flush();
    }
  }

  private void setRadius(int distance) {
    if (this.advertised != distance) {
      this.advertised = distance;
      this.context.write(new ClientboundSetChunkCacheRadiusPacket(distance));
      this.needsFlush = true;
    }
  }

  private void fill(long now) {
    int end = this.area();
    if ((this.cursor >= end && this.edges.isEmpty()) || this.pending.size() >= this.settings.inFlightPerPlayer()) {
      return;
    }
    long[] offsets = ChunkOrder.offsets(this.settings.distance(), this.view.direction());
    int scanEnd = Math.min(end, this.cursor + 1024);
    int starts = 0;
    int edgeStarts = 0;
    while ((this.cursor < scanEnd || !this.edges.isEmpty()) && starts < this.settings.inFlightPerPlayer()
      && this.pending.size() < this.settings.inFlightPerPlayer()) {
      boolean edge = !this.edges.isEmpty() && (this.cursor >= scanEnd || edgeStarts < Math.max(1, this.settings.inFlightPerPlayer() / 4));
      long key;
      if (edge) {
        key = this.edges.dequeueLong();
        if (!this.desired(key)) {
          continue;
        }
      } else {
        long offset = offsets[this.cursor++];
        key = ChunkPos.pack(this.view.x() + ChunkPos.getX(offset), this.view.z() + ChunkPos.getZ(offset));
      }
      int x = ChunkPos.getX(key);
      int z = ChunkPos.getZ(key);
      if (!this.desired(key)) {
        continue;
      }
      int dx = x - this.view.x();
      int dz = z - this.view.z();
      if (dx * dx + dz * dz <= this.view.nativeDistance() * this.view.nativeDistance()) {
        continue;
      }
      if (this.nativeChunks.contains(key) || this.extended.contains(key) || this.pending.contains(key)) {
        continue;
      }
      long retry = this.retries.get(key);
      if (retry > now) {
        this.nextRetry = Math.min(this.nextRetry, retry);
        continue;
      }
      CompletableFuture<ChunkData> future = this.source.tryRead(this.view.world(), x, z);
      if (future == null) {
        if (edge) {
          this.edges.enqueueFirst(key);
        } else {
          this.cursor--;
        }
        break;
      }
      if (edge) {
        edgeStarts++;
      }
      this.retries.remove(key);
      this.pending.add(key);
      starts++;
      long version = this.epoch;
      ChannelHandlerContext ctx = this.context;
      future.whenComplete((data, error) -> {
        if (ctx.executor().inEventLoop()) {
          this.complete(key, version, data, error);
        } else {
          ctx.executor().execute(() -> this.complete(key, version, data, error));
        }
      });
    }
  }

  private void complete(long key, long version, ChunkData data, Throwable error) {
    if (this.epoch != version) {
      return;
    }
    if (!this.desired(key) || this.nativeChunks.contains(key)) {
      this.pending.remove(key);
      this.metrics.obsolete.increment();
    } else if (data != null && !data.current()) {
      this.retryObsolete(key);
    } else if (data == null || error != null) {
      this.pending.remove(key);
      long retry = System.nanoTime() + 2_000_000_000L;
      this.retries.put(key, retry);
      this.nextRetry = Math.min(this.nextRetry, retry);
      if (error == null) {
        this.metrics.unavailable.increment();
      } else {
        this.metrics.failures.increment();
      }
    } else {
      this.ready.addLast(new ReadyChunk(key, data));
    }
  }

  private int sendReady(int limit, long now) {
    int sent = 0;
    while (sent < limit && !this.ready.isEmpty() && this.context.channel().isWritable()) {
      ReadyChunk item = this.ready.getFirst();
      if (!this.desired(item.key()) || this.nativeChunks.contains(item.key()) || this.extended.contains(item.key())) {
        this.pending.remove(item.key());
        this.ready.removeFirst();
        this.metrics.obsolete.increment();
        continue;
      }
      if (!item.data().current()) {
        this.ready.removeFirst();
        this.retryObsolete(item.key());
        continue;
      }
      if (!this.takeBudget(item.data().bytes(), now)) {
        break;
      }
      this.ready.removeFirst();
      this.pending.remove(item.key());
      this.extended.add(item.key());
      this.writeChunk(item.key(), item.data(), false);
      sent++;
    }
    return sent;
  }

  private void retryObsolete(long key) {
    this.pending.remove(key);
    this.edges.enqueue(key);
    this.sleepUntil = 0;
    this.metrics.obsolete.increment();
  }

  void updateChunk(World world, long key, ChunkData data) {
    if (this.watchesUpdate(world, key) && data.current()) {
      this.updates.put(key, data);
      this.sleepUntil = 0;
    }
  }

  boolean watchesUpdate(World world, long key) {
    return this.updates != null && this.context != null && this.view != null && this.view.world() == world && this.canUpdate(key);
  }

  private boolean canUpdate(long key) {
    return this.extended.contains(key) && !this.nativeChunks.contains(key) && this.retained(key);
  }

  private int sendUpdates(int limit, long now) {
    int sent = 0;
    while (sent < limit && !this.updates.isEmpty() && this.context.channel().isWritable()) {
      long key = this.updates.firstLongKey();
      ChunkData data = this.updates.get(key);
      if (!data.current() || !this.canUpdate(key)) {
        this.updates.removeFirst();
        continue;
      }
      if (!this.takeBudget(data.bytes(), now)) {
        break;
      }
      this.updates.removeFirst();
      this.writeChunk(key, data, true);
      sent++;
    }
    return sent;
  }

  private boolean takeBudget(int bytes, long now) {
    if (this.playerBudget.take(bytes, now)) {
      if (this.globalBudget.take(bytes, now, this.share)) {
        return true;
      }
      this.playerBudget.refund(bytes);
    }
    this.metrics.throttled.increment();
    return false;
  }

  private void writeChunk(long key, ChunkData data, boolean update) {
    long version = this.epoch;
    this.needsFlush = true;
    ChannelPromise promise = this.context.newPromise();
    var packet = data.packet();
    this.compression.writeChunk(this.context, packet, promise);
    promise.addListener(result -> {
      if (result.isSuccess()) {
        if (update) {
          this.metrics.updated.increment();
          this.metrics.updateBytes.add(data.bytes());
        } else {
          this.metrics.sent.increment();
          this.metrics.bytes.add(data.bytes());
        }
      } else if (version == this.epoch) {
        if (!update) {
          this.extended.remove(key);
          this.cursor = 0;
          this.sleepUntil = 0;
        }
        this.metrics.failures.increment();
      }
    });
    packet.getExtraPackets().forEach(extra -> {
      this.compression.invalidate(extra);
      this.context.write(extra);
    });
  }

  private boolean desired(long key) {
    return this.view != null && this.view.enabled()
      && ChunkTrackingView.isWithinDistance(this.view.x(), this.view.z(), this.view.distance(),
        ChunkPos.getX(key), ChunkPos.getZ(key), false);
  }

  boolean hasPlayerTerrain(org.bukkit.World world, long key) {
    return this.view != null && this.view.world() == world && this.desired(key)
      && (this.extended.contains(key) || this.nativeChunks.contains(key));
  }

  boolean tracksNativePlayer(int id) {
    var players = this.farPlayers;
    return players != null && players.tracksNativePlayer(id);
  }

  boolean consumeNativePlayerRemoval() {
    var players = this.farPlayers;
    return players != null && players.consumeNativePlayerRemoval();
  }

  String farPlayerDetails(FarPlayerState.Snapshot target) {
    ViewSnapshot current = this.view;
    if (this.context == null || current == null) {
      return "view not ready";
    }
    boolean sameWorld = current.world() == target.world();
    int x = ChunkPos.getX(target.chunk());
    int z = ChunkPos.getZ(target.chunk());
    return "same-world=" + sameWorld + ", offset=[" + (x - current.x()) + ", " + (z - current.z()) + "]"
      + ", radius=" + current.nativeDistance() + "→" + current.distance()
      + ", in-range=" + (sameWorld && this.desired(target.chunk()))
      + ", extended-sent=" + (sameWorld && this.desired(target.chunk()) && this.extended.contains(target.chunk()))
      + ", terrain-ready=" + this.hasPlayerTerrain(target.world(), target.chunk())
      + ", snapshot-current=" + target.current()
      + ", writable=" + this.context.channel().isWritable()
      + (this.farPlayers == null ? ", profile-observer=missing" : this.farPlayers.details(target));
  }

  void hideFar(UUID target, boolean hidden) {
    if (this.context != null && this.farPlayers != null) {
      this.farPlayers.hidden(target, hidden);
    }
  }

  private boolean retained(long key) {
    return this.view != null && retained(this.view, ChunkPos.getX(key), ChunkPos.getZ(key));
  }

  static boolean retained(ViewSnapshot view, int x, int z) {
    return view.enabled() && Math.abs(x - view.x()) <= view.distance() + RETAIN_MARGIN
      && Math.abs(z - view.z()) <= view.distance() + RETAIN_MARGIN;
  }

  private int area() {
    int width = this.view.distance() * 2 + 1;
    return width * width;
  }

  private void queueEdges(ViewSnapshot old, ViewSnapshot next) {
    int radius = next.distance();
    if (this.edges.size() > 8 * radius + 4) {
      this.edges.clear();
      this.cursor = 0;
    }
    for (int index = 0; index <= 2 * radius; index++) {
      int offset = (index & 1) == 0 ? index / 2 : -(index + 1) / 2;
      int z = next.z() + offset;
      int extent = rowExtent(radius, offset);
      int previousExtent = rowExtent(radius, z - old.z());
      int first = next.x() - extent;
      int last = next.x() + extent;
      if (previousExtent < 0) {
        this.enqueue(first, last, z);
      } else {
        this.enqueue(first, Math.min(last, old.x() - previousExtent - 1), z);
        this.enqueue(Math.max(first, old.x() + previousExtent + 1), last, z);
      }
    }
  }

  private static int rowExtent(int radius, int z) {
    if (Math.abs(z) > radius) {
      return -1;
    }
    int inner = Math.max(0, Math.abs(z) - 1);
    return Math.min(radius, 1 + (int) Math.sqrt(radius * radius - 1 - inner * inner));
  }

  private void enqueue(int first, int last, int z) {
    for (int x = first; x <= last; x++) {
      this.edges.enqueue(ChunkPos.pack(x, z));
    }
  }

  private void trim(ViewSnapshot old, ViewSnapshot next) {
    int dx = next.x() - old.x();
    int dz = next.z() - old.z();
    int radius = old.distance() + RETAIN_MARGIN;
    if (radius == next.distance() + RETAIN_MARGIN && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
      for (int offset = -radius; offset <= radius; offset++) {
        if (dx != 0) {
          this.leave(ChunkPos.pack(old.x() - dx * radius, old.z() + offset));
        }
        if (dz != 0) {
          this.leave(ChunkPos.pack(old.x() + offset, old.z() - dz * radius));
        }
      }
    } else {
      var iterator = this.extended.iterator();
      while (iterator.hasNext()) {
        long key = iterator.nextLong();
        if (!this.retained(key)) {
          this.forget(key);
          iterator.remove();
        }
      }
      this.retries.keySet().removeIf((long key) -> !this.retained(key));
    }
  }

  private void leave(long key) {
    this.retries.remove(key);
    if (this.extended.remove(key)) {
      this.forget(key);
    }
  }

  private void forget(long key) {
    if (this.updates != null) {
      this.updates.remove(key);
    }
    this.compression.forget(key);
    if (this.farPlayers != null) {
      this.farPlayers.forget(key);
    }
    this.context.write(new ClientboundForgetLevelChunkPacket(ChunkPos.unpack(key)));
    this.needsFlush = true;
  }

  void teleport() {
    this.compression.clear();
    if (this.farPlayers != null) {
      this.farPlayers.clear();
    }
    this.sleepUntil = 0;
    if (this.context != null) {
      this.epoch++;
      this.pending.clear();
      this.edges.clear();
      this.ready.clear();
      this.retries.clear();
      this.nextRetry = Long.MAX_VALUE;
      this.cursor = 0;
    }
  }

  void restore() {
    if (this.view != null && this.view.enabled() && this.context.channel().isActive()) {
      this.reset(true, true);
      this.setRadius(this.view.nativeDistance());
      this.flush();
    }
  }

  private void reset(boolean unload, boolean keepNative) {
    this.compression.clear();
    if (this.farPlayers != null) {
      this.farPlayers.clear();
    }
    this.epoch++;
    if (unload) {
      this.extended.forEach(this::forget);
    }
    this.extended.clear();
    if (this.updates != null) {
      this.updates.clear();
    }
    this.edges.clear();
    this.pending.clear();
    this.ready.clear();
    this.retries.clear();
    if (!keepNative) {
      this.nativeChunks.clear();
    }
    this.cursor = 0;
    this.nextRetry = Long.MAX_VALUE;
  }

  private record ReadyChunk(long key, ChunkData data) {
  }
}
