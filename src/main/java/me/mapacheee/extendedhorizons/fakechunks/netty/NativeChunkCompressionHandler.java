package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import me.mapacheee.extendedhorizons.fakechunks.cache.CompressedPayloadCacheService;

final class NativeChunkCompressionHandler extends ChannelOutboundHandlerAdapter {
  private final CompressedPayloadCacheService cache;
  private final ChannelInjectionService injection;
  private final EhPacketHandler sessionHandler;
  private CompressedPayloadCacheService.NativeLookup pending;

  NativeChunkCompressionHandler(CompressedPayloadCacheService cache, ChannelInjectionService injection,
    EhPacketHandler sessionHandler) {
    this.cache = cache;
    this.injection = injection;
    this.sessionHandler = sessionHandler;
  }

  ChannelOutboundHandlerAdapter captureHandler() {
    return new ChannelOutboundHandlerAdapter() {
      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        var lookup = pending;
        pending = null;
        if (lookup != null && msg instanceof ByteBuf encoded) {
          try {
            cache.storeNative(lookup, encoded);
          } catch (RuntimeException ignored) {
          }
        }
        ctx.write(msg, promise);
      }
    };
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    var session = this.sessionHandler.session();
    if (!(msg instanceof ByteBuf source) || session == null || !session.enabled() || !isLevelChunk(source)) {
      ctx.write(msg, promise);
      return;
    }
    var route = this.injection.compressionRoute(ctx.channel());
    if (route == null) {
      ctx.write(msg, promise);
      return;
    }
    CompressedPayloadCacheService.NativeLookup lookup;
    try {
      lookup = this.cache.lookupNative(source, route.threshold());
    } catch (RuntimeException exception) {
      ctx.write(msg, promise);
      return;
    }
    if (lookup == null) {
      ctx.write(msg, promise);
      return;
    }
    ByteBuf encoded = lookup.payload();
    if (encoded != null) {
      ByteBuf copy;
      try {
        copy = encoded.copy();
      } catch (RuntimeException exception) {
        ctx.write(msg, promise);
        return;
      } finally {
        encoded.release();
      }
      source.release();
      var previous = this.pending;
      this.pending = null;
      try {
        route.context().write(copy, promise);
      } finally {
        this.pending = previous;
      }
      return;
    }
    var previous = this.pending;
    this.pending = lookup;
    try {
      ctx.write(msg, promise);
    } finally {
      this.pending = previous;
    }
  }

  private static boolean isLevelChunk(ByteBuf source) {
    if (!PacketIdRegistry.hasLevelChunkWithLightId()) return false;
    int id = 0;
    for (int i = 0; i < 5 && i < source.readableBytes(); i++) {
      int next = source.getUnsignedByte(source.readerIndex() + i);
      id |= (next & 0x7F) << (i * 7);
      if ((next & 0x80) == 0) return id == PacketIdRegistry.getLevelChunkWithLightId();
    }
    return false;
  }
}
