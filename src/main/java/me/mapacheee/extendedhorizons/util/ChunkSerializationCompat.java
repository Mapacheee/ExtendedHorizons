package me.mapacheee.extendedhorizons.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

public final class ChunkSerializationCompat {

  private static final MethodType WRITER_TYPE = MethodType.methodType(void.class, Object.class, FriendlyByteBuf.class);
  private static final MethodHandle SECTION_WRITER = createWriter(LevelChunkSection.class);
  private static final MethodHandle PALETTE_WRITER = createWriter(PalettedContainerRO.class);
  private static final MethodHandle CHUNK_WRITER = createWriter(ClientboundLevelChunkPacketData.class);
  private static final MethodHandle LIGHT_WRITER = createWriter(ClientboundLightUpdatePacketData.class);

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
