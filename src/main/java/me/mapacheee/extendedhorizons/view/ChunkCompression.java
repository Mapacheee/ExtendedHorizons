package me.mapacheee.extendedhorizons.view;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.CompressionEncoder;

final class ChunkCompression extends ChannelOutboundHandlerAdapter {

  private final CompressionCache cache;
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

  ChunkCompression(CompressionCache cache) {
    this.cache = cache;
  }

  void writeChunk(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
    var pipeline = ctx.pipeline();
    ChannelHandlerContext compressor = pipeline.context("compress");
    if (compressor == null || !(compressor.handler() instanceof CompressionEncoder)
      || pipeline.context("encoder") == null || !compressor.executor().inEventLoop()
      || !pipeline.context("encoder").executor().inEventLoop()) {
      ctx.write(packet, promise);
      return;
    }
    if (pipeline.context(this) == null) {
      pipeline.addBefore("compress", "extendedhorizons_compressed", this.capture);
      pipeline.addAfter("compress", "extendedhorizons_compression", this);
    }
    boolean previous = this.chunk;
    this.chunk = true;
    try {
      ctx.write(packet, promise);
    } finally {
      this.chunk = previous;
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
    ChannelHandlerContext compressor = ctx.pipeline().context("compress");
    if (!this.chunk || !(message instanceof ByteBuf source) || compressor == null
      || !(compressor.handler() instanceof CompressionEncoder encoder)) {
      ctx.write(message, promise);
      return;
    }
    CompressionCache.Lookup lookup = this.cache.lookup(source, encoder.getThreshold());
    if (lookup.data() != null) {
      ByteBuf copy = Unpooled.wrappedBuffer(lookup.data().clone());
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

  void remove(ChannelHandlerContext ctx) {
    var pipeline = ctx.pipeline();
    if (pipeline.context(this) != null) {
      pipeline.remove(this);
    }
    if (pipeline.context(this.capture) != null) {
      pipeline.remove(this.capture);
    }
  }
}
