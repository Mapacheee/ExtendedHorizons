package me.mapacheee.extendedhorizons.util;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.IntList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

public final class NmsCompat {

  private static final Logger LOGGER = LoggerFactory.getLogger(NmsCompat.class);

  public static final Object PLAYER_ENTITY_TYPE;
  private static final MethodHandle SPAWN_PACKET_CONSTRUCTOR;
  private static final MethodHandle GET_ENTITY_TYPE;
  private static final MethodHandle CHUNK_X = accessor(ClientboundLevelChunkWithLightPacket.class, "x", "getX");
  private static final MethodHandle CHUNK_Z = accessor(ClientboundLevelChunkWithLightPacket.class, "z", "getZ");
  private static final MethodHandle REMOVED_IDS = accessor(ClientboundRemoveEntitiesPacket.class, "entityIds", "getEntityIds");

  static {
    Object playerType = null;
    try {
      Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");
      playerType = entityTypeClass.getField("PLAYER").get(null);
      LOGGER.debug("NMS Compat: resolved PLAYER_ENTITY_TYPE from EntityType.PLAYER");
    } catch (Throwable t1) {
      try {
        Class<?> entityTypesClass = Class.forName("net.minecraft.world.entity.EntityTypes");
        playerType = entityTypesClass.getField("PLAYER").get(null);
        LOGGER.debug("NMS Compat: resolved PLAYER_ENTITY_TYPE from EntityTypes.PLAYER");
      } catch (Throwable t2) {
        LOGGER.error("NMS Compat: failed to resolve PLAYER_ENTITY_TYPE!", t2);
      }
    }
    PLAYER_ENTITY_TYPE = playerType;

    MethodHandle spawnHandle = null;
    MethodHandle typeGetter = null;
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      for (Constructor<?> ctor : ClientboundAddEntityPacket.class.getConstructors()) {
        if (ctor.getParameterCount() == 11) {
          ctor.setAccessible(true);
          spawnHandle = lookup.unreflectConstructor(ctor);
          break;
        }
      }
      if (spawnHandle == null) {
        LOGGER.error("NMS Compat: failed to find 11-param constructor in ClientboundAddEntityPacket!");
      }
    } catch (Throwable t) {
      LOGGER.error("NMS Compat: failed to bind ClientboundAddEntityPacket constructor", t);
    }
    SPAWN_PACKET_CONSTRUCTOR = spawnHandle;

    try {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      for (Method m : ClientboundAddEntityPacket.class.getMethods()) {
        if (m.getName().equals("getType") && m.getParameterCount() == 0) {
          typeGetter = lookup.unreflect(m);
          break;
        }
      }
    } catch (Throwable t) {
      LOGGER.warn("NMS Compat: failed to bind getType method", t);
    }
    GET_ENTITY_TYPE = typeGetter;
  }

  public static boolean isPlayer(ClientboundAddEntityPacket packet) {
    if (packet == null) {
      return false;
    }
    try {
      Object type = GET_ENTITY_TYPE != null ? GET_ENTITY_TYPE.invoke(packet) : null;
      if (type != null) {
        if (PLAYER_ENTITY_TYPE != null && PLAYER_ENTITY_TYPE.equals(type)) {
          return true;
        }
        String str = type.toString().toLowerCase();
        return str.contains("player");
      }
    } catch (Throwable t) {
      // fallback
    }
    return false;
  }

  public static int chunkX(ClientboundLevelChunkWithLightPacket packet) {
    return (int) read(CHUNK_X, packet);
  }

  public static int chunkZ(ClientboundLevelChunkWithLightPacket packet) {
    return (int) read(CHUNK_Z, packet);
  }

  public static IntList removedEntityIds(ClientboundRemoveEntitiesPacket packet) {
    return (IntList) read(REMOVED_IDS, packet);
  }

  public static Object createPositionSyncPacket(int entityId, double x, double y, double z, float yaw, float pitch) {
    try {
      Vec3 position = new Vec3(x, y, z);
      if (Movement.POSITION_PATH != null) {
        return Movement.CONSTRUCTOR.invoke(entityId, Movement.POSITION_PATH.invoke(position), yaw, pitch, true);
      }
      return Movement.CONSTRUCTOR.invoke(entityId, new PositionMoveRotation(position, Vec3.ZERO, yaw, pitch), true);
    } catch (Throwable throwable) {
      throw new IllegalStateException("Failed to construct player position packet", throwable);
    }
  }

  private static MethodHandle accessor(Class<?> type, String name, String legacyName) {
    try {
      Method method;
      try {
        method = type.getMethod(name);
      } catch (NoSuchMethodException exception) {
        method = type.getMethod(legacyName);
      }
      return MethodHandles.publicLookup().unreflect(method);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Unsupported packet accessor: " + type.getName() + "." + name, exception);
    }
  }

  private static Object read(MethodHandle accessor, Object packet) {
    try {
      return accessor.invoke(packet);
    } catch (Throwable throwable) {
      throw new IllegalStateException("Failed to read packet " + packet.getClass().getSimpleName(), throwable);
    }
  }

  private static final class Movement {
    private static final MethodHandle POSITION_PATH;
    private static final MethodHandle CONSTRUCTOR;

    static {
      try {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> pathType;
        try {
          pathType = Class.forName("net.minecraft.world.entity.PositionPath");
        } catch (ClassNotFoundException exception) {
          pathType = null;
        }
        if (pathType != null) {
          POSITION_PATH = lookup.unreflect(pathType.getMethod("of", Vec3.class));
          CONSTRUCTOR = lookup.unreflectConstructor(ClientboundEntityPositionSyncPacket.class.getConstructor(
            int.class, pathType, float.class, float.class, boolean.class));
        } else {
          POSITION_PATH = null;
          CONSTRUCTOR = lookup.unreflectConstructor(ClientboundEntityPositionSyncPacket.class.getConstructor(
            int.class, PositionMoveRotation.class, boolean.class));
        }
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Unsupported player position packet", exception);
      }
    }
  }

  public static Object createAddPlayerPacket(
    int entityId,
    UUID uuid,
    double x,
    double y,
    double z,
    float pitch,
    float yaw,
    float headYaw
  ) {
    if (SPAWN_PACKET_CONSTRUCTOR == null || PLAYER_ENTITY_TYPE == null) {
      LOGGER.warn("NMS Compat: spawn packet constructor or player type not available");
      return null;
    }
    try {
      return SPAWN_PACKET_CONSTRUCTOR.invoke(
        entityId,
        uuid,
        x,
        y,
        z,
        pitch,
        yaw,
        PLAYER_ENTITY_TYPE,
        0,
        Vec3.ZERO,
        (double) headYaw
      );
    } catch (Throwable t) {
      try {
        return SPAWN_PACKET_CONSTRUCTOR.invoke(
          entityId,
          uuid,
          x,
          y,
          z,
          pitch,
          yaw,
          PLAYER_ENTITY_TYPE,
          0,
          Vec3.ZERO,
          headYaw
        );
      } catch (Throwable t2) {
        LOGGER.error("NMS Compat: failed to construct ClientboundAddEntityPacket", t2);
        return null;
      }
    }
  }

  private NmsCompat() {
  }
}
