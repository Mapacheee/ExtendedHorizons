package me.mapacheee.extendedhorizons.compat;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

public final class PacketAccess {

  private static final MethodHandle CHUNK_X = accessor(ClientboundLevelChunkWithLightPacket.class, int.class, "x", "getX");
  private static final MethodHandle CHUNK_Z = accessor(ClientboundLevelChunkWithLightPacket.class, int.class, "z", "getZ");
  private static final MethodHandle LIGHT_X = accessor(ClientboundLightUpdatePacket.class, int.class, "x", "getX");
  private static final MethodHandle LIGHT_Z = accessor(ClientboundLightUpdatePacket.class, int.class, "z", "getZ");
  private static final MethodHandle CHUNK_LIGHT = accessor(ClientboundLevelChunkWithLightPacket.class,
    ClientboundLightUpdatePacketData.class, "lightData", "getLightData");
  private static final MethodHandle CHUNK_DATA = accessor(ClientboundLevelChunkWithLightPacket.class,
    ClientboundLevelChunkPacketData.class, "chunkData", "getChunkData");
  private static final MethodHandle POS_X = accessor(ChunkPos.class, int.class, "x");
  private static final MethodHandle POS_Z = accessor(ChunkPos.class, int.class, "z");
  private static final MethodHandle REMOVED = accessor(ClientboundRemoveEntitiesPacket.class, IntList.class, "entityIds", "getEntityIds");
  private static final MethodHandle INTERACT = accessor(ServerboundInteractPacket.class, int.class, "entityId", "getEntityId");
  private static final MethodHandle HIT_RESULT = accessor(ServerboundUseItemOnPacket.class, BlockHitResult.class, "hitResult", "getHitResult");
  private static final MethodHandle TELEPORT_ID = accessor(ServerboundAcceptTeleportationPacket.class, int.class, "id", "getId");

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

  public static ClientboundLightUpdatePacketData lightData(ClientboundLevelChunkWithLightPacket packet) {
    try {
      return (ClientboundLightUpdatePacketData) CHUNK_LIGHT.invokeExact((Object) packet);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot read chunk light data", failure);
    }
  }

  public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
    try {
      return (ClientboundLevelChunkPacketData) CHUNK_DATA.invokeExact((Object) packet);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot read chunk payload", failure);
    }
  }

  @SuppressWarnings("unchecked")
  public static void forEachBlockEntity(ClientboundLevelChunkPacketData data, int x, int z,
    ClientboundLevelChunkPacketData.BlockEntityTagOutput output) {
    try {
      if (BlockEntities.FOR_EACH != null) {
        BlockEntities.FOR_EACH.invokeExact(data, x, z, output);
      } else {
        ((Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput>) BlockEntities.CONSUMER.invokeExact(data, x, z)).accept(output);
      }
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot read chunk block entities", failure);
    }
  }

  private static final class BlockEntities {
    private static final MethodHandle FOR_EACH = find("forEachBlockEntityTag", MethodType.methodType(void.class,
      int.class, int.class, ClientboundLevelChunkPacketData.BlockEntityTagOutput.class));
    private static final MethodHandle CONSUMER = FOR_EACH == null
      ? find("getBlockEntitiesTagsConsumer", MethodType.methodType(Consumer.class, int.class, int.class)) : null;

    private static MethodHandle find(String name, MethodType type) {
      try {
        return MethodHandles.publicLookup().findVirtual(ClientboundLevelChunkPacketData.class, name, type);
      } catch (NoSuchMethodException | IllegalAccessException ignored) {
        return null;
      }
    }
  }

  public static int interactionId(ServerboundInteractPacket packet) {
    return integer(INTERACT, packet);
  }

  public static BlockHitResult hitResult(ServerboundUseItemOnPacket packet) {
    try {
      return (BlockHitResult) HIT_RESULT.invokeExact((Object) packet);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot read block interaction", failure);
    }
  }

  public static int teleportId(ServerboundAcceptTeleportationPacket packet) {
    return integer(TELEPORT_ID, packet);
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
