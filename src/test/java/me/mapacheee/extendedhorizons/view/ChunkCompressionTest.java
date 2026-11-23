package me.mapacheee.extendedhorizons.view;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import me.mapacheee.extendedhorizons.chunk.ChunkData;
import me.mapacheee.extendedhorizons.compat.LightPacketAccess;
import me.mapacheee.extendedhorizons.compat.PacketAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCompressionTest {

  @Test
  void countsEncodedFramesForFreshCachedAndLightOnlyWrites() {
    var channel = new EmbeddedChannel();
    try {
      var pipeline = channel.pipeline();
      pipeline.addLast("prepender", new Varint21LengthFieldPrepender());
      pipeline.addLast("compress", new CompressionEncoder(64));
      pipeline.addLast("encoder", new MessageToByteEncoder<Packet<?>>() {
        @Override
        protected void encode(ChannelHandlerContext ctx, Packet<?> message, ByteBuf out) {
          out.writeByte(1);
          out.writeZero(4096);
        }
      });
      pipeline.addLast("stream", new ChannelOutboundHandlerAdapter());
      var ctx = pipeline.context("stream");
      var metrics = new ViewMetrics();
      var cache = new CompressionCache(4096);
      var compression = new ChunkCompression(cache, metrics, true);
      var packet = emptyChunk();
      var light = LightPacketAccess.packet(0, 0, PacketAccess.lightData(packet));
      var data = new ChunkData(packet, 4096, null, null, light);

      int first = writeAndCheckSize(channel, ctx, compression, data, false);
      assertTrue(first < data.bytes());
      assertEquals(first, writeAndCheckSize(channel, ctx, compression, data, false));
      assertEquals(1L, cache.stats().get("compressionHits"));

      assertEquals(0, compression.writeTerrain(ctx, data, ctx.newPromise(), false, true, data.bytes()));
      channel.flushOutbound();
      assertNull(channel.readOutbound());

      int lightBytes = writeAndCheckSize(channel, ctx, compression, data, true);
      assertEquals(2L * first + lightBytes, metrics.measuredTerrainBytes.sum());
      assertEquals(0, metrics.estimatedTerrainBytes.sum());

      compression.remove(ctx);
      pipeline.remove("compress");
      int estimate = compression.writeTerrain(ctx, data, ctx.newPromise(), false, false, data.bytes());
      channel.flushOutbound();
      ByteBuf frame = channel.readOutbound();
      try {
        assertTrue(estimate >= frame.readableBytes());
      } finally {
        frame.release();
      }
      assertEquals(estimate, metrics.estimatedTerrainBytes.sum());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private static int writeAndCheckSize(EmbeddedChannel channel, ChannelHandlerContext ctx, ChunkCompression compression,
    ChunkData data, boolean lightOnly) {
    int bytes = compression.writeTerrain(ctx, data, ctx.newPromise(), lightOnly, false, data.bytes());
    channel.flushOutbound();
    ByteBuf frame = channel.readOutbound();
    try {
      assertEquals(frame.readableBytes(), bytes);
    } finally {
      frame.release();
    }
    return bytes;
  }

  private static ClientboundLevelChunkWithLightPacket emptyChunk() {
    var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    try {
      buf.writeInt(0);
      buf.writeInt(0);
      // Empty heightmaps, section data, block entities, light masks and light arrays.
      buf.writeZero(9);
      return ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
    } finally {
      buf.release();
    }
  }
}
