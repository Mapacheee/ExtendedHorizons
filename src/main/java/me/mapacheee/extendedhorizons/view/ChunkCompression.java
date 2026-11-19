package me.mapacheee.extendedhorizons.view;

import me.mapacheee.extendedhorizons.compat.PacketAccess;
import me.mapacheee.extendedhorizons.chunk.ChunkData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChunkCompression extends ChannelOutboundHandlerAdapter {

  private static final Logger ENCODER_LOGGER = LoggerFactory.getLogger(PacketEncoder.class);

  private final CompressionCache cache;
  private final ViewMetrics metrics;
  private final boolean reuseEncodedChunks;
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
  private Object sharedPacket;
  private ChannelHandlerContext encoder;
  private int packetId = -1;

  ChunkCompression(CompressionCache cache, ViewMetrics metrics, boolean reuseEncodedChunks) {
    this.cache = cache;
    this.metrics = metrics;
    this.reuseEncodedChunks = reuseEncodedChunks;
  }

  void writeChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket packet, ChannelPromise promise) {
    this.writeChunk(ctx, packet, promise, false);
  }

  void writeChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket packet, ChannelPromise promise, boolean deduplicate) {
    this.writeChunk(ctx, packet, promise, deduplicate, false);
  }

  void writeChunk(ChannelHandlerContext ctx, ChunkData data, ChannelPromise promise, boolean deduplicate) {
    this.writeChunk(ctx, data.packet(), promise, deduplicate, true);
  }

  private void writeChunk(ChannelHandlerContext ctx, ClientboundLevelChunkWithLightPacket packet, ChannelPromise promise,
    boolean deduplicate, boolean shared) {
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
    if (this.encoder != encoder) {
      this.encoder = encoder;
      this.packetId = -1;
    }
    boolean canonical = this.reuseEncodedChunks && shared && !ENCODER_LOGGER.isDebugEnabled()
      && this.canonical(ctx, compressor, encoder);
    if (this.reuseEncodedChunks && shared && !canonical) {
      this.cache.forgetPacket(packet);
    }
    long key = PacketAccess.chunkKey(packet);
    if (canonical && this.packetId >= 0) {
      var lookup = this.cache.lookup(packet, ((CompressionEncoder) compressor.handler()).getThreshold(), this.packetId);
      if (lookup != null) {
        if (deduplicate && this.duplicate(key, lookup)) {
          this.metrics.suppressed.increment();
          promise.trySuccess();
        } else {
          JvmProfiler.INSTANCE.onPacketSent(ConnectionProtocol.PLAY, packet.type(), ctx.channel().remoteAddress(), lookup.key().size());
          this.writeCached(compressor, lookup.data(), this.track(key, lookup, promise));
        }
        return;
      }
    }
    boolean previous = this.chunk;
    boolean previousDeduplicate = this.deduplicate;
    long previousPosition = this.position;
    Object previousPacket = this.sharedPacket;
    this.chunk = true;
    this.deduplicate = deduplicate;
    this.position = key;
    this.sharedPacket = canonical ? packet : null;
    try {
      ctx.write(packet, promise);
    } finally {
      this.chunk = previous;
      this.deduplicate = previousDeduplicate;
      this.position = previousPosition;
      this.sharedPacket = previousPacket;
    }
  }

  private boolean canonical(ChannelHandlerContext ctx, ChannelHandlerContext compressor, ChannelHandlerContext encoder) {
    if (!(ctx.channel() instanceof SocketChannel) || compressor.handler().getClass() != CompressionEncoder.class
      || encoder.handler().getClass() != PacketEncoder.class) {
      return false;
    }
    var pipeline = ctx.pipeline();
    var names = pipeline.names();
    int index = names.indexOf(compressor.name()) + 1;
    if (index <= 0 || index + 2 >= names.size() || pipeline.get(names.get(index++)) != this
      || pipeline.get(names.get(index++)) != encoder.handler()) {
      return false;
    }
    for (; index < names.size(); index++) {
      var handler = pipeline.get(names.get(index));
      if (handler == ctx.handler()) {
        return true;
      }
      if (handler == null) {
        return false;
      }
      Class<?> type = handler.getClass();
      if (type != PacketBundleUnpacker.class && type != FarPlayerChannel.class
        && !(names.get(index).equals("hackfix") && type.getEnclosingClass() == Connection.class
          && type.getSuperclass() == ChannelOutboundHandlerAdapter.class)) {
        return false;
      }
    }
    return false;
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
    this.packetId = packetId(source);
    if (this.sharedPacket != null) {
      this.cache.remember(this.sharedPacket, this.packetId, lookup.key());
    }
    if (this.deduplicate && this.duplicate(this.position, lookup)) {
      source.release();
      this.metrics.suppressed.increment();
      promise.trySuccess();
      return;
    }
    promise = this.track(this.position, lookup, promise);
    if (lookup.data() != null) {
      source.release();
      this.writeCached(compressor, lookup.data(), promise);
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

  private boolean duplicate(long key, CompressionCache.Lookup lookup) {
    Delivery known = this.delivered.get(key);
    return known != null && known.confirmed && lookup.key().equals(known.key);
  }

  boolean confirmed(long key) {
    Delivery known = this.delivered.get(key);
    return known != null && known.confirmed;
  }

  private ChannelPromise track(long key, CompressionCache.Lookup lookup, ChannelPromise promise) {
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
    return promise;
  }

  private void writeCached(ChannelHandlerContext compressor, byte[] data, ChannelPromise promise) {
    ByteBuf copy = compressor.alloc().ioBuffer(data.length, data.length).writeBytes(data);
    CompressionCache.Lookup previous = this.pending;
    this.pending = null;
    try {
      compressor.write(copy, promise);
    } finally {
      this.pending = previous;
    }
  }

  private static int packetId(ByteBuf buffer) {
    int value = 0;
    for (int i = 0; i < 5; i++) {
      int next = buffer.getUnsignedByte(buffer.readerIndex() + i);
      value |= (next & 127) << (i * 7);
      if ((next & 128) == 0) {
        return value;
      }
    }
    throw new IllegalStateException("Invalid chunk packet ID");
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
