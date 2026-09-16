package me.mapacheee.extendedhorizons.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.BitSet;

public final class ChunkSerializationCompat {

  private static final MethodType WRITER_TYPE = MethodType.methodType(void.class, Object.class, FriendlyByteBuf.class);
  private static final MethodHandle SECTION_WRITER = createWriter(LevelChunkSection.class);
  private static final MethodHandle PALETTE_WRITER = createWriter(PalettedContainerRO.class);
  private static final MethodHandle CHUNK_WRITER = createWriter(ClientboundLevelChunkPacketData.class);
  private static final MethodHandle LIGHT_WRITER = createWriter(ClientboundLightUpdatePacketData.class);
  private static final StreamCodec<ByteBuf, BitSet> LIGHT_MASK_CODEC = lightMaskCodec();

  private ChunkSerializationCompat() {
  }

  public static void writeSection(LevelChunkSection section, FriendlyByteBuf out) {
    write(SECTION_WRITER, section, out);
  }

  public static void writePalette(PalettedContainerRO<?> palette, FriendlyByteBuf out) {
    write(PALETTE_WRITER, palette, out);
  }

  public static void writeChunkData(ClientboundLevelChunkPacketData data, FriendlyByteBuf out) {
    write(CHUNK_WRITER, data, out);
  }

  public static void writeLightData(ClientboundLightUpdatePacketData data, FriendlyByteBuf out) {
    write(LIGHT_WRITER, data, out);
  }

  public static void writeLightMask(ByteBuf out, BitSet mask) {
    if (LIGHT_MASK_CODEC != null) {
      LIGHT_MASK_CODEC.encode(out, mask);
    } else {
      FriendlyByteBuf.writeLongArray(out, mask.toLongArray());
    }
  }

  public static int lightMaskSize(BitSet mask) {
    int elements = LIGHT_MASK_CODEC != null ? (mask.length() + 7) / 8 : (mask.length() + 63) / 64;
    return VarInt.getByteSize(elements) + elements * (LIGHT_MASK_CODEC != null ? 1 : Long.BYTES);
  }

  @SuppressWarnings("unchecked")
  private static StreamCodec<ByteBuf, BitSet> lightMaskCodec() {
    try {
      return (StreamCodec<ByteBuf, BitSet>) ByteBufCodecs.class.getField("BIT_SET").get(null);
    } catch (NoSuchFieldException exception) {
      return null;
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Unable to resolve light mask codec", exception);
    }
  }

  private static MethodHandle createWriter(Class<?> type) {
    try {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      for (Method method : type.getMethods()) {
        if (!method.getName().equals("write") || method.getReturnType() != void.class) {
          continue;
        }
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length == 0 || !ByteBuf.class.isAssignableFrom(parameters[0])) {
          continue;
        }
        MethodHandle writer = lookup.unreflect(method);
        if (parameters.length == 1) {
          return writer.asType(WRITER_TYPE);
        }
        if (parameters.length == 3 && !parameters[1].isPrimitive() && parameters[2] == int.class) {
          return MethodHandles.insertArguments(writer, 2, null, 0).asType(WRITER_TYPE);
        }
      }
      Object codec = type.getField("STREAM_CODEC").get(null);
      MethodHandle encode = lookup.findVirtual(StreamCodec.class, "encode",
        MethodType.methodType(void.class, Object.class, Object.class)).bindTo(codec);
      return MethodHandles.permuteArguments(encode, encode.type(), 1, 0).asType(WRITER_TYPE);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Unsupported chunk serialization API: " + type.getName(), exception);
    }
  }

  private static void write(MethodHandle writer, Object value, FriendlyByteBuf out) {
    try {
      writer.invokeExact(value, out);
    } catch (Throwable throwable) {
      throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName(), throwable);
    }
  }
}
