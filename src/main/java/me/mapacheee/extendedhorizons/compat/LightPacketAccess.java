package me.mapacheee.extendedhorizons.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.BitSet;
import java.util.List;

public final class LightPacketAccess {

  private static final MethodHandle LEGACY_WRITE = legacyWriter();

  private LightPacketAccess() {}

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
