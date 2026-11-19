package me.mapacheee.extendedhorizons.view;

import me.mapacheee.extendedhorizons.compat.PacketAccess;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.flush.FlushConsolidationHandler;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
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
  private static final long CENTER_GRACE_NANOS = 100_000_000L;
  private static final long TELEPORT_TIMEOUT_NANOS = 1_000_000_000L;

  private final ChunkSource source;
  private final ChunkCompression compression;
  private final Config settings;
  private final ViewMetrics metrics;
  private final SendBudget globalBudget;
  private final SendBudget playerBudget;
  private final SendBudget.Share share;
  private final LongOpenHashSet nativeChunks;
  private final LongOpenHashSet extended = new LongOpenHashSet();
  private final LongOpenHashSet needsFullChunk = new LongOpenHashSet();
  private final LongOpenHashSet pending = new LongOpenHashSet();
  private final Long2LongOpenHashMap retries = new Long2LongOpenHashMap();
  private final ChunkPlan plan = new ChunkPlan();
  private final ArrayDeque<ReadyChunk> ready = new ArrayDeque<>();
  private final Long2ObjectLinkedOpenHashMap<ChunkData> updates;
  private final Long2ObjectOpenHashMap<ChunkData.Signature> snapshots;
  private ChannelHandlerContext context;
  private volatile FarPlayerChannel farPlayers;
  private ResourceKey<?> expectedDimension;
  private ViewSnapshot view;
  private ClientboundSetChunkCacheCenterPacket cacheCenter;
  private long centerMismatchSince;
  private Integer awaitingTeleportId;
  private long teleportDeadline;
  private boolean replanAfterTeleport;
  private long epoch;
  private int advertised = -1;
  private long nextRetry = Long.MAX_VALUE;
  private volatile long sleepUntil;
  private boolean needsFlush;
  private FlushConsolidationHandler flushConsolidation;

  ChunkStream(ChunkSource source, Config settings, ViewMetrics metrics, SendBudget global,
    CompressionCache compression, LongOpenHashSet nativeChunks) {
    this.source = source;
    this.compression = new ChunkCompression(compression, metrics, settings.reuseEncodedChunks());
    this.settings = settings;
    this.metrics = metrics;
    this.globalBudget = global;
    this.share = global.share();
    this.playerBudget = new SendBudget(settings.playerBytesPerSecond());
    this.nativeChunks = nativeChunks;
    this.updates = settings.chunkUpdates().enabled() ? new Long2ObjectLinkedOpenHashMap<>() : null;
    this.snapshots = this.updates == null ? null : new Long2ObjectOpenHashMap<>();
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
    this.consolidateFlushes(false);
    this.compression.remove(ctx);
    this.globalBudget.remove(this.share);
    this.context = null;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
    if (message instanceof ClientboundLevelChunkWithLightPacket packet) {
      var center = this.centerForNativeChunk(packet);
      if (center != null) {
        ctx.write(center);
      }
    }
    boolean transition = message instanceof ClientboundLevelChunkWithLightPacket chunk
      && this.extended.contains(PacketAccess.chunkKey(chunk));
    Object outgoing = message instanceof ClientboundBundlePacket bundle ? this.bundle(bundle) : this.observe(message);
    if (outgoing == null) {
      promise.trySuccess();
    } else if (outgoing instanceof ClientboundLevelChunkWithLightPacket chunk) {
      this.invalidate(outgoing);
      this.compression.writeChunk(ctx, chunk, promise, transition);
    } else {
      this.invalidate(outgoing);
      ctx.write(outgoing, promise);
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object message) {
    if (message instanceof ServerboundAcceptTeleportationPacket ack && this.awaitingTeleportId != null
      && this.awaitingTeleportId == PacketAccess.teleportId(ack)) {
      this.awaitingTeleportId = null;
      this.teleportDeadline = 0;
      this.sleepUntil = 0;
    }
    if (message instanceof ServerboundPlayerActionPacket || message instanceof ServerboundUseItemOnPacket
      || message instanceof ServerboundUseItemPacket) {
      this.compression.clear();
      if (this.snapshots != null) {
        this.snapshots.clear();
      }
    }
    ctx.fireChannelRead(message);
  }

  private void invalidate(Object packet) {
    this.compression.invalidate(packet);
    this.invalidateSnapshot(packet);
  }

  private void invalidateSnapshot(Object packet) {
    if (this.snapshots == null || this.snapshots.isEmpty()) {
      return;
    }
    if (packet instanceof ClientboundBlockUpdatePacket update) {
      this.snapshots.remove(PacketAccess.chunkKey(update.getPos().getX() >> 4, update.getPos().getZ() >> 4));
    } else if (packet instanceof ClientboundBlockEntityDataPacket update) {
      this.snapshots.remove(PacketAccess.chunkKey(update.getPos().getX() >> 4, update.getPos().getZ() >> 4));
    } else if (packet instanceof ClientboundLightUpdatePacket update) {
      long key = PacketAccess.chunkKey(update);
      var previous = this.snapshots.get(key);
      if (previous != null) {
        this.snapshots.put(key, previous.withoutLight());
      }
    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
      update.runUpdates((pos, state) -> this.snapshots.remove(PacketAccess.chunkKey(pos.getX() >> 4, pos.getZ() >> 4)));
    } else if (packet instanceof ClientboundChunksBiomesPacket || packet instanceof ClientboundExplodePacket
      || packet instanceof ClientboundBlockEventPacket) {
      this.snapshots.clear();
    } else if (packet instanceof ClientboundBundlePacket bundle) {
      bundle.subPackets().forEach(this::invalidateSnapshot);
    }
  }

  private Object bundle(ClientboundBundlePacket bundle) {
    for (var sub : bundle.subPackets()) {
      if (sub instanceof ClientboundForgetLevelChunkPacket || sub instanceof ClientboundSetChunkCacheRadiusPacket
        || sub instanceof ClientboundSetChunkCacheCenterPacket || sub instanceof ClientboundPlayerPositionPacket
        || sub instanceof ClientboundLevelChunkWithLightPacket || sub instanceof ClientboundRespawnPacket
        || this.suppressMaskedUpdate(sub)) {
        var packets = new ArrayList<Packet<? super ClientGamePacketListener>>();
        for (var packet : bundle.subPackets()) {
          if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            var center = this.centerForNativeChunk(chunk);
            if (center != null) {
              packets.add(center);
            }
          }
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
      long key = PacketAccess.chunkKey(chunk);
      if (this.outsideClientCache(key)) {
        // An obsolete native batch may still be draining. The client discards this packet.
        this.nativeChunks.remove(key);
      } else {
        this.nativeChunks.add(key);
      }
      this.extended.remove(key);
      this.needsFullChunk.remove(key);
      if (this.updates != null) {
        this.updates.remove(key);
        this.snapshots.remove(key);
      }
    } else if (packet instanceof ClientboundForgetLevelChunkPacket(ChunkPos pos)) {
      long key = PacketAccess.chunkKey(PacketAccess.x(pos), PacketAccess.z(pos));
      boolean known = this.nativeChunks.remove(key);
      if (known && this.source.masking()) {
        if (this.desired(key)) {
          this.extended.add(key);
          this.needsFullChunk.add(key);
          this.retries.remove(key);
          this.plan.add(key);
          this.sleepUntil = 0;
          return null;
        }
      } else if (this.retained(key) && (known || this.extended.contains(key))) {
        if (Math.abs(PacketAccess.x(pos) - this.view.x()) <= this.view.nativeDistance()
          && Math.abs(PacketAccess.z(pos) - this.view.z()) <= this.view.nativeDistance()) {
          this.compression.forget(key);
        }
        this.extended.add(key);
        return null;
      }
      this.extended.remove(key);
      this.needsFullChunk.remove(key);
      if (this.updates != null) {
        this.updates.remove(key);
        this.snapshots.remove(key);
      }
      this.compression.forget(key);
      if (this.farPlayers != null) {
        this.farPlayers.forget(key);
      }
    } else if (packet instanceof ClientboundPlayerPositionPacket position) {
      if (this.teleportDeadline != 0 || this.replanAfterTeleport) {
        this.awaitingTeleportId = position.id();
        this.teleportDeadline = System.nanoTime() + TELEPORT_TIMEOUT_NANOS;
      }
    } else if (packet instanceof ClientboundSetChunkCacheCenterPacket center) {
      if (!this.centeredAt(center.getX(), center.getZ())) {
        this.cacheCenter = center;
        this.invalidateOutsideClientCache();
      }
      this.sleepUntil = 0;
    } else if (packet instanceof ClientboundSetChunkCacheRadiusPacket radius) {
      this.compression.clear();
      this.advertised = radius.getRadius();
      if (this.view != null && this.view.enabled() && this.advertised < this.view.distance()) {
        this.advertised = this.view.distance();
        return new ClientboundSetChunkCacheRadiusPacket(this.advertised);
      }
    } else if (packet instanceof ClientboundRespawnPacket respawn) {
      this.reset(false, false);
      this.cacheCenter = null;
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
          result[1] = this.extended.contains(PacketAccess.chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
        }
      });
      return result[1];
    }
    return false;
  }

  private boolean maskedChunk(BlockPos pos) {
    return this.extended.contains(PacketAccess.chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
  }

  void tick(ViewSnapshot next, List<FarPlayerState.Snapshot> candidates) {
    this.tick(next, candidates, System.nanoTime());
  }

  void tick(ViewSnapshot next, List<FarPlayerState.Snapshot> candidates, long now) {
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
      this.cacheCenter = null;
      this.advertised = -1;
      previous = null;
    }
    if (next.enabled()) {
      if (this.teleportDeadline != 0 && now < this.teleportDeadline) {
        this.sleepUntil = this.teleportDeadline;
        return;
      }
      this.teleportDeadline = 0;
      this.awaitingTeleportId = null;
      if (!this.alignCenter(next, now)) {
        this.sleepUntil = 0;
        return;
      }
      if (this.replanAfterTeleport) {
        next = new ViewSnapshot(next.world(), next.x(), next.z(), next.nativeDistance(), next.distance(), 0, true);
      }
    }
    this.view = next;
    this.consolidateFlushes(next.enabled());
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
    if (this.replanAfterTeleport || previous == null || !previous.enabled() || previous.x() != next.x() || previous.z() != next.z()
      || previous.distance() != next.distance() || previous.nativeDistance() != next.nativeDistance()) {
      boolean incremental = !this.replanAfterTeleport && previous != null && previous.enabled() && previous.distance() == next.distance()
        && previous.nativeDistance() == next.nativeDistance() && Math.abs(previous.x() - next.x()) < next.distance()
        && Math.abs(previous.z() - next.z()) < next.distance();
      if (previous != null && previous.enabled()) {
        this.trim(previous, next);
      }
      if (incremental) {
        this.plan.move(previous, next);
      } else {
        this.rebuildPlan();
      }
      this.replanAfterTeleport = false;
    }
    if (now >= this.nextRetry) {
      this.nextRetry = Long.MAX_VALUE;
      var iterator = this.retries.long2LongEntrySet().fastIterator();
      while (iterator.hasNext()) {
        var retry = iterator.next();
        if (now >= retry.getLongValue()) {
          this.plan.add(retry.getLongKey());
          iterator.remove();
        } else {
          this.nextRetry = Math.min(this.nextRetry, retry.getLongValue());
        }
      }
    }
    if (this.context.channel().isWritable()) {
      int limit = this.settings.sendsPerTick();
      int sent = this.updates == null ? 0 : this.sendUpdates(Math.max(1, limit / 4), now);
      sent += this.sendReady(limit - sent, now);
      this.fill();
      this.sendReady(limit - sent, now);
    } else {
      this.metrics.throttled.increment();
    }
    if (candidates != null && this.farPlayers != null && this.farPlayers.sync(this, candidates, this.settings.farPlayers())) {
      this.needsFlush = true;
    }
    this.flush();
    boolean updating = this.updates != null && !this.updates.isEmpty();
    this.sleepUntil = !updating && this.pending.isEmpty() && this.plan.isEmpty() ? this.nextRetry : 0;
    if (this.centerMismatchSince != 0) {
      this.sleepUntil = Math.min(this.sleepUntil, this.centerMismatchSince + CENTER_GRACE_NANOS);
    }
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

  private void consolidateFlushes(boolean enabled) {
    var pipeline = this.context.pipeline();
    if (enabled && this.settings.consolidateFlushes() && this.context.channel() instanceof SocketChannel) {
      if (this.flushConsolidation == null) {
        var handler = new FlushConsolidationHandler(256, true);
        pipeline.addFirst("extendedhorizons_flush", handler);
        this.flushConsolidation = handler;
      }
    } else if (this.flushConsolidation != null) {
      if (pipeline.context(this.flushConsolidation) != null) {
        pipeline.remove(this.flushConsolidation);
      }
      this.flushConsolidation = null;
    }
  }

  private void setRadius(int distance) {
    if (this.advertised != distance) {
      this.advertised = distance;
      this.context.write(new ClientboundSetChunkCacheRadiusPacket(distance));
      this.needsFlush = true;
    }
  }

  private boolean centeredAt(int x, int z) {
    return this.cacheCenter != null && this.cacheCenter.getX() == x && this.cacheCenter.getZ() == z;
  }

  private boolean outsideClientCache(long key) {
    if (this.cacheCenter == null || this.view == null || !this.view.enabled() || this.advertised < 0) {
      return false;
    }
    int radius = this.clientCacheRadius();
    return Math.abs(ChunkPos.getX(key) - this.cacheCenter.getX()) > radius
      || Math.abs(ChunkPos.getZ(key) - this.cacheCenter.getZ()) > radius;
  }

  private int clientCacheRadius() {
    return Math.max(2, Math.min(this.advertised, this.view.distance())) + 3;
  }

  private ClientboundSetChunkCacheCenterPacket centerForNativeChunk(ClientboundLevelChunkWithLightPacket packet) {
    long key = PacketAccess.chunkKey(packet);
    if (this.outsideClientCache(key) && this.desired(key)) {
      // Keep the correction and the native chunk in wire order, including inside bundles.
      this.cacheCenter = new ClientboundSetChunkCacheCenterPacket(this.view.x(), this.view.z());
      this.sleepUntil = 0;
      return this.cacheCenter;
    }
    return null;
  }

  private boolean alignCenter(ViewSnapshot next, long now) {
    if (!this.centeredAt(next.x(), next.z())) {
      if (this.cacheCenter != null && !this.replanAfterTeleport) {
        // Paper can publish a movement center just before the player scheduler samples it.
        // Pause extended sends briefly, then recover even when the player stands still.
        if (this.centerMismatchSince == 0) {
          this.centerMismatchSince = now;
        }
        if (now - this.centerMismatchSince < CENTER_GRACE_NANOS) {
          // One chunk of normal movement still fits inside the client's cache margin.
          return Math.abs(this.cacheCenter.getX() - next.x()) <= 1 && Math.abs(this.cacheCenter.getZ() - next.z()) <= 1;
        }
      }
      this.setCenter(next.x(), next.z());
    }
    this.centerMismatchSince = 0;
    return true;
  }

  private void invalidateOutsideClientCache() {
    if (this.view == null || !this.view.enabled() || this.advertised < 0) {
      return;
    }
    int x = this.cacheCenter.getX();
    int z = this.cacheCenter.getZ();
    int radius = this.clientCacheRadius();
    int retained = this.view.distance() + RETAIN_MARGIN;
    if (Math.abs(x - this.view.x()) + retained <= radius && Math.abs(z - this.view.z()) + retained <= radius) {
      return;
    }
    // The client can reuse these slots before another center packet moves them back into view.
    for (var it = this.extended.iterator(); it.hasNext();) {
      long key = it.nextLong();
      if (Math.abs(ChunkPos.getX(key) - x) > radius || Math.abs(ChunkPos.getZ(key) - z) > radius) {
        this.compression.forget(key);
        this.needsFullChunk.add(key);
        if (this.desired(key)) {
          this.plan.add(key);
        }
      }
    }
  }

  private void setCenter(int x, int z) {
    this.cacheCenter = new ClientboundSetChunkCacheCenterPacket(x, z);
    this.context.write(this.cacheCenter);
    this.needsFlush = true;
  }

  private void fill() {
    int limit = this.settings.inFlightPerPlayer();
    int starts = 0;
    int scanned = 0;
    while (!this.plan.isEmpty() && starts < limit && this.pending.size() < limit && scanned++ < 1024) {
      long key = this.plan.first();
      int x = ChunkPos.getX(key);
      int z = ChunkPos.getZ(key);
      int dx = x - this.view.x();
      int dz = z - this.view.z();
      int nativeDistance = this.view.nativeDistance();
      boolean nativeRange = dx * dx + dz * dz <= nativeDistance * nativeDistance;
      if (!this.desired(key) || this.nativeChunks.contains(key) || this.pending.contains(key)
        || (!this.needsFullChunk.contains(key) && (this.extended.contains(key) || nativeRange))
        || this.retries.containsKey(key)) {
        this.plan.advance();
        continue;
      }
      CompletableFuture<ChunkData> future = this.source.tryRead(this.view.world(), x, z);
      if (future == null) {
        break;
      }
      this.plan.advance();
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
      if (!this.desired(item.key()) || this.nativeChunks.contains(item.key())
        || (this.extended.contains(item.key()) && !this.needsFullChunk.contains(item.key()))) {
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
    this.plan.add(key);
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
    return this.extended.contains(key) && !this.needsFullChunk.contains(key) && !this.nativeChunks.contains(key) && this.retained(key);
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
      var previous = this.snapshots.get(key);
      boolean lightOnly = previous != null && data.signature() != null && previous.sameTerrain(data.signature());
      if (lightOnly && previous.sameLight(data.signature())) {
        this.updates.removeFirst();
        this.metrics.suppressed.increment();
        continue;
      }
      if (!this.takeBudget(lightOnly ? data.signature().lightBytes() + 10 : data.bytes(), now)) {
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
    boolean resend = this.needsFullChunk.remove(key);
    var previous = this.snapshots == null ? null : this.snapshots.get(key);
    boolean lightOnly = update && previous != null && data.signature() != null && previous.sameTerrain(data.signature());
    int bytes = lightOnly ? data.signature().lightBytes() + 10 : data.bytes();
    if (this.snapshots != null) {
      if (data.signature() == null) {
        this.snapshots.remove(key);
      } else {
        this.snapshots.put(key, data.signature());
      }
    }
    promise.addListener(result -> {
      if (result.isSuccess()) {
        if (update) {
          this.metrics.updated.increment();
          this.metrics.updateBytes.add(bytes);
        } else {
          this.metrics.sent.increment();
          this.metrics.bytes.add(data.bytes());
        }
      } else if (version == this.epoch) {
        if (this.snapshots != null && this.snapshots.get(key) == data.signature()) {
          this.snapshots.remove(key);
        }
        if (!update) {
          this.extended.remove(key);
          this.plan.add(key);
          this.sleepUntil = 0;
        }
        this.metrics.failures.increment();
      }
    });
    if (lightOnly) {
      this.compression.invalidate(data.lightPacket());
      this.context.write(data.lightPacket(), promise);
    } else {
      this.compression.writeChunk(this.context, data, promise, update || resend);
      packet.getExtraPackets().forEach(extra -> {
        this.invalidate(extra);
        this.context.write(extra);
      });
    }
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

  private void rebuildPlan() {
    this.plan.rebuild(this.view);
    this.needsFullChunk.forEach(this.plan::add);
  }

  private void trim(ViewSnapshot old, ViewSnapshot next) {
    int dx = next.x() - old.x();
    int dz = next.z() - old.z();
    int radius = old.distance() + RETAIN_MARGIN;
    if (radius == next.distance() + RETAIN_MARGIN && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
      for (int offset = -radius; offset <= radius; offset++) {
        if (dx != 0) {
          this.leave(PacketAccess.chunkKey(old.x() - dx * radius, old.z() + offset));
        }
        if (dz != 0) {
          this.leave(PacketAccess.chunkKey(old.x() + offset, old.z() - dz * radius));
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
    this.plan.remove(key);
    this.retries.remove(key);
    if (this.extended.remove(key)) {
      this.forget(key);
    }
  }

  private void forget(long key) {
    this.needsFullChunk.remove(key);
    if (this.updates != null) {
      this.updates.remove(key);
      this.snapshots.remove(key);
    }
    this.compression.forget(key);
    if (this.farPlayers != null) {
      this.farPlayers.forget(key);
    }
    this.context.write(new ClientboundForgetLevelChunkPacket(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key))));
    this.needsFlush = true;
  }

  void teleport(World world, int x, int z) {
    if (this.snapshots != null) {
      this.snapshots.clear();
    }
    if (this.farPlayers != null) {
      this.farPlayers.clear();
    }
    this.sleepUntil = 0;
    if (this.context != null) {
      boolean interrupted = !this.plan.isEmpty() || !this.pending.isEmpty() || !this.retries.isEmpty();
      this.epoch++;
      this.pending.clear();
      this.plan.clear();
      this.ready.clear();
      this.retries.clear();
      this.nextRetry = Long.MAX_VALUE;
      this.replanAfterTeleport = true;
      this.awaitingTeleportId = null;
      this.teleportDeadline = System.nanoTime() + TELEPORT_TIMEOUT_NANOS;
      var old = this.view;
      this.centerMismatchSince = 0;
      if (old != null && old.enabled() && old.world() == world) {
        // Trim each hop, even when several teleports happen between player ticks.
        this.view = new ViewSnapshot(world, x, z, old.nativeDistance(), old.distance(), 0, true);
        this.trim(old, this.view);
        if (interrupted && (old.x() != x || old.z() != z)) {
          // Confirmed chunks inside both cache windows keep their slots. Preserve
          // earlier invalidations and retry writes whose delivery is still uncertain.
          for (var it = this.extended.iterator(); it.hasNext();) {
            long key = it.nextLong();
            if (this.outsideClientCache(key) || !this.compression.confirmed(key)) {
              this.needsFullChunk.add(key);
            }
          }
        }
        if (!this.centeredAt(x, z)) {
          this.setCenter(x, z);
        }
        this.flush();
      }
    }
    this.compression.clear();
  }

  void restore() {
    if (this.view != null && this.view.enabled() && this.context.channel().isActive()) {
      this.reset(true, true);
      this.setRadius(this.view.nativeDistance());
      this.flush();
    }
  }

  private void reset(boolean unload, boolean keepNative) {
    this.awaitingTeleportId = null;
    this.teleportDeadline = 0;
    this.centerMismatchSince = 0;
    this.compression.clear();
    if (this.farPlayers != null) {
      this.farPlayers.clear();
    }
    this.epoch++;
    if (unload) {
      this.extended.forEach(this::forget);
    }
    this.extended.clear();
    this.needsFullChunk.clear();
    if (this.updates != null) {
      this.updates.clear();
      this.snapshots.clear();
    }
    this.plan.clear();
    this.pending.clear();
    this.ready.clear();
    this.retries.clear();
    if (!keepNative) {
      this.nativeChunks.clear();
    }
    this.nextRetry = Long.MAX_VALUE;
  }

  private record ReadyChunk(long key, ChunkData data) {
  }
}
