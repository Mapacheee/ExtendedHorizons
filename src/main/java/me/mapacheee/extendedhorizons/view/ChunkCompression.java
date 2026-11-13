package me.mapacheee.extendedhorizons.view;

import me.mapacheee.extendedhorizons.compat.PacketAccess;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

final class ChunkCompression extends ChannelOutboundHandlerAdapter {

  private final CompressionCache cache;
  private final ViewMetrics metrics;
  private final Long2ObjectOpenHashMap<Delivery> delivered = new Long2ObjectOpenHashMap<>();
  private final ChannelOutboundHandlerAdapter capture = new ChannelOutboundHandlerAdapter() {
    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
      CompressionCache.Lookup lookup = pending;
      pending = null;
      if (lookup != null && message instanceof ByteBuf compressed) {
        cache.store(lookup, compressed);
      }
      ctx.write(message, promise);
    }
  };
  private CompressionCache.Lookup pending;
  private boolean chunk;
  private boolean deduplicate;
  private long position;

  ChunkCompression(CompressionCache cache, ViewMetrics metrics) {
    this.cache = cache;
    this.metrics = metrics;
  }

  void writeChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket packet, ChannelPromise promise) {
    this.writeChunk(ctx, packet, promise, false);
  }

  void writeChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket packet, ChannelPromise promise, boolean deduplicate) {
    var pipeline = ctx.pipeline();
    ChannelHandlerContext compressor = pipeline.context("compress");
    ChannelHandlerContext encoder = pipeline.context("encoder");
    if (compressor == null || !(compressor.handler() instanceof CompressionEncoder)
      || encoder == null || !compressor.executor().inEventLoop() || !encoder.executor().inEventLoop()) {
      this.forget(PacketAccess.chunkKey(packet));
      ctx.write(packet, promise);
      return;
    }
    if (pipeline.context(this) == null) {
      pipeline.addBefore("compress", "extendedhorizons_compressed", this.capture);
      pipeline.addAfter("compress", "extendedhorizons_compression", this);
    }
    boolean previous = this.chunk;
    boolean previousDeduplicate = this.deduplicate;
    long previousPosition = this.position;
    this.chunk = true;
    this.deduplicate = deduplicate;
    this.position = PacketAccess.chunkKey(packet);
    try {
      ctx.write(packet, promise);
    } finally {
      this.chunk = previous;
      this.deduplicate = previousDeduplicate;
      this.position = previousPosition;
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
    if (!this.chunk || !(message instanceof ByteBuf source)) {
      ctx.write(message, promise);
      return;
    }
    ChannelHandlerContext compressor = ctx.pipeline().context("compress");
    if (compressor == null || !(compressor.handler() instanceof CompressionEncoder encoder)) {
      ctx.write(message, promise);
      return;
    }
    CompressionCache.Lookup lookup = this.cache.lookup(source, encoder.getThreshold());
    Delivery known = this.delivered.get(this.position);
    if (this.deduplicate && known != null && known.confirmed && lookup.key().equals(known.key)) {
      source.release();
      this.metrics.suppressed.increment();
      promise.trySuccess();
      return;
    }
    long key = this.position;
    Delivery delivery = new Delivery(lookup.key());
    this.delivered.put(key, delivery);
    promise = promise.unvoid();
    promise.addListener(result -> {
      if (this.delivered.get(key) == delivery) {
        if (result.isSuccess()) {
          delivery.confirmed = true;
        } else {
          this.delivered.remove(key);
        }
      }
    });
    if (lookup.data() != null) {
      byte[] data = lookup.data();
      ByteBuf copy = ctx.alloc().ioBuffer(data.length, data.length).writeBytes(data);
      source.release();
      compressor.write(copy, promise);
      return;
    }
    CompressionCache.Lookup previous = this.pending;
    this.pending = lookup;
    try {
      ctx.write(message, promise);
    } finally {
      this.pending = previous;
    }
  }

  void invalidate(Object packet) {
    if (packet instanceof ClientboundBlockUpdatePacket update) {
      this.forget(PacketAccess.chunkKey(update.getPos().getX() >> 4, update.getPos().getZ() >> 4));
    } else if (packet instanceof ClientboundBlockEntityDataPacket update) {
      this.forget(PacketAccess.chunkKey(update.getPos().getX() >> 4, update.getPos().getZ() >> 4));
    } else if (packet instanceof ClientboundLightUpdatePacket update) {
      this.forget(PacketAccess.chunkKey(update));
    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
      update.runUpdates((pos, state) -> this.forget(PacketAccess.chunkKey(pos.getX() >> 4, pos.getZ() >> 4)));
    } else if (packet instanceof ClientboundChunksBiomesPacket || packet instanceof ClientboundExplodePacket
      || packet instanceof ClientboundBlockEventPacket) {
      this.clear();
    } else if (packet instanceof ClientboundBundlePacket bundle) {
      bundle.subPackets().forEach(this::invalidate);
    }
  }

  void forget(long key) {
    this.delivered.remove(key);
  }

  void clear() {
    this.delivered.clear();
  }

  void remove(ChannelHandlerContext ctx) {
    var pipeline = ctx.pipeline();
    if (pipeline.context(this) != null) {
      pipeline.remove(this);
    }
    if (pipeline.context(this.capture) != null) {
      pipeline.remove(this.capture);
    }
  }

  private static final class Delivery {
    private final CompressionCache.Key key;
    private boolean confirmed;

    private Delivery(CompressionCache.Key key) {
      this.key = key;
    }
  }
}
