package me.mapacheee.extendedhorizons.compat;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.level.ChunkPos;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class PacketAccess {

  private static final MethodHandle CHUNK_X = accessor(ClientboundLevelChunkWithLightPacket.class, int.class, "x", "getX");
  private static final MethodHandle CHUNK_Z = accessor(ClientboundLevelChunkWithLightPacket.class, int.class, "z", "getZ");
  private static final MethodHandle LIGHT_X = accessor(ClientboundLightUpdatePacket.class, int.class, "x", "getX");
  private static final MethodHandle LIGHT_Z = accessor(ClientboundLightUpdatePacket.class, int.class, "z", "getZ");
  private static final MethodHandle POS_X = accessor(ChunkPos.class, int.class, "x");
  private static final MethodHandle POS_Z = accessor(ChunkPos.class, int.class, "z");
  private static final MethodHandle REMOVED = accessor(ClientboundRemoveEntitiesPacket.class, IntList.class, "entityIds", "getEntityIds");
  private static final MethodHandle INTERACT = accessor(ServerboundInteractPacket.class, int.class, "entityId", "getEntityId");

  private PacketAccess() {}

  public static long chunkKey(int x, int z) {
    return (x & 0xffffffffL) | ((long) z << 32);
  }

  public static int x(ChunkPos pos) {
    return integer(POS_X, pos);
  }

  public static int z(ChunkPos pos) {
    return integer(POS_Z, pos);
  }

  public static long chunkKey(ClientboundLevelChunkWithLightPacket packet) {
    return chunkKey(integer(CHUNK_X, packet), integer(CHUNK_Z, packet));
  }

  public static long chunkKey(ClientboundLightUpdatePacket packet) {
    return chunkKey(integer(LIGHT_X, packet), integer(LIGHT_Z, packet));
  }

  public static int interactionId(ServerboundInteractPacket packet) {
    return integer(INTERACT, packet);
  }

  public static IntList removedIds(ClientboundRemoveEntitiesPacket packet) {
    try {
      return (IntList) REMOVED.invokeExact((Object) packet);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot read removed entity IDs", failure);
    }
  }

  private static int integer(MethodHandle accessor, Object packet) {
    try {
      return (int) accessor.invokeExact(packet);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot read Minecraft packet field", failure);
    }
  }

  private static MethodHandle accessor(Class<?> owner, Class<?> type, String... names) {
    var lookup = MethodHandles.publicLookup();
    var signature = MethodType.methodType(type, Object.class);
    for (String name : names) {
      try {
        return lookup.findVirtual(owner, name, MethodType.methodType(type)).asType(signature);
      } catch (NoSuchMethodException | IllegalAccessException ignored) {
      }
    }
    for (String name : names) {
      try {
        return lookup.findGetter(owner, name, type).asType(signature);
      } catch (NoSuchFieldException | IllegalAccessException ignored) {
      }
    }
    throw new IllegalStateException("Missing " + owner.getName() + "." + names[0]);
  }
}
