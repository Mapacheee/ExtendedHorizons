package me.mapacheee.extendedhorizons.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import io.netty.buffer.PooledByteBufAllocator;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.BitSet;
import java.util.List;

public final class LightPacketAccess {

  private static final MethodHandle LEGACY_WRITE = legacyWriter();
  private static final MethodHandle PACKET_CONSTRUCTOR = packetConstructor();

  private LightPacketAccess() {}

  public static boolean copiesData() {
    return PACKET_CONSTRUCTOR == null;
  }

  public static ClientboundLightUpdatePacket packet(int x, int z, ClientboundLightUpdatePacketData data) {
    if (PACKET_CONSTRUCTOR != null) {
      try {
        return (ClientboundLightUpdatePacket) PACKET_CONSTRUCTOR.invokeExact(x, z, data);
      } catch (Throwable failure) {
        throw new IllegalStateException("Cannot construct light update", failure);
      }
    }
    var buffer = new FriendlyByteBuf(PooledByteBufAllocator.DEFAULT.directBuffer(4096, 8 * 1024 * 1024));
    try {
      buffer.writeVarInt(x);
      buffer.writeVarInt(z);
      write(buffer, data);
      return ClientboundLightUpdatePacket.STREAM_CODEC.decode(buffer);
    } finally {
      buffer.release();
    }
  }

  private static MethodHandle packetConstructor() {
    try {
      return MethodHandles.publicLookup().findConstructor(ClientboundLightUpdatePacket.class,
        MethodType.methodType(void.class, int.class, int.class, ClientboundLightUpdatePacketData.class));
    } catch (NoSuchMethodException | IllegalAccessException ignored) {
      return null;
    }
  }

  public static void write(FriendlyByteBuf output, ClientboundLightUpdatePacketData light) {
    if (LEGACY_WRITE == null) {
      ClientboundLightUpdatePacketData.STREAM_CODEC.encode(output, light);
      return;
    }
    try {
      LEGACY_WRITE.invokeExact(light, output);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot encode chunk light", failure);
    }
  }

  public static void write(FriendlyByteBuf output, BitSet sky, BitSet block, BitSet emptySky, BitSet emptyBlock,
    List<byte[]> skyUpdates, List<byte[]> blockUpdates) {
    if (LEGACY_WRITE == null) {
      write(output, new ClientboundLightUpdatePacketData(sky, block, emptySky, emptyBlock, skyUpdates, blockUpdates));
      return;
    }
    output.writeBitSet(sky);
    output.writeBitSet(block);
    output.writeBitSet(emptySky);
    output.writeBitSet(emptyBlock);
    writeLayers(output, skyUpdates);
    writeLayers(output, blockUpdates);
  }

  private static void writeLayers(FriendlyByteBuf output, List<byte[]> layers) {
    output.writeVarInt(layers.size());
    for (byte[] layer : layers) {
      output.writeVarInt(layer.length);
      output.writeBytes(layer);
    }
  }

  private static MethodHandle legacyWriter() {
    try {
      return MethodHandles.publicLookup().findVirtual(ClientboundLightUpdatePacketData.class, "write",
        MethodType.methodType(void.class, FriendlyByteBuf.class));
    } catch (NoSuchMethodException ignored) {
      return null;
    } catch (IllegalAccessException failure) {
      throw new IllegalStateException("Cannot access chunk light writer", failure);
    }
  }
}
