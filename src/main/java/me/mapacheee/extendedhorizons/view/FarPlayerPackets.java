package me.mapacheee.extendedhorizons.view;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftEntityType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class FarPlayerPackets {

  static final EntityType<?> PLAYER = CraftEntityType.bukkitToMinecraft(org.bukkit.entity.EntityType.PLAYER);

  private static final String GAME = "net.minecraft.network.protocol.game.";
  private static final boolean SEPARATE_ATTACK = exists(GAME + "ServerboundAttackPacket");
  private static final boolean SWING_PACKET = exists(GAME + "ClientboundSwingAnimationPacket");
  private static final MethodHandle LEGACY_POSITION = legacyPosition();
  private static final MethodHandle LEGACY_ENTITY_ID = legacyEntityId();

  private FarPlayerPackets() {}

  static int nextEntityId(World world) {
    UnsafeValues unsafe = Bukkit.getUnsafe();
    if (LEGACY_ENTITY_ID == null) {
      return unsafe.nextEntityId(world);
    }
    try {
      return (int) LEGACY_ENTITY_ID.invokeExact(unsafe);
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot reserve remote player entity ID", failure);
    }
  }

  static boolean attacksReplica(Object message, IntSet replicas) {
    return SEPARATE_ATTACK && message instanceof ServerboundAttackPacket attack && replicas.contains(attack.entityId());
  }

  static SwingAnimation animation(ItemStack item) {
    return SWING_PACKET ? item.getAttackAnimation() : null;
  }

  static Packet<? super ClientGamePacketListener> swing(int id, FarPlayerState.Swing swing) {
    if (SWING_PACKET) {
      return new ClientboundSwingAnimationPacket(id, swing.hand(), swing.animation());
    }
    var buffer = new FriendlyByteBuf(Unpooled.buffer(6));
    try {
      buffer.writeVarInt(id);
      buffer.writeByte(swing.hand() == InteractionHand.MAIN_HAND ? 0 : 3);
      return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
    } finally {
      buffer.release();
    }
  }

  static ClientboundEntityPositionSyncPacket position(FarPlayerState.Snapshot target) {
    var position = new Vec3(target.x(), target.y(), target.z());
    if (LEGACY_POSITION == null) {
      return new ClientboundEntityPositionSyncPacket(target.id(), PositionPath.of(position),
        target.yaw(), target.pitch(), target.onGround());
    }
    var movement = new PositionMoveRotation(position, Vec3.ZERO, target.yaw(), target.pitch());
    try {
      return (ClientboundEntityPositionSyncPacket) LEGACY_POSITION.invokeExact(target.id(), movement, target.onGround());
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot create remote player position", failure);
    }
  }

  private static boolean exists(String name) {
    try {
      Class.forName(name, false, FarPlayerPackets.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  private static MethodHandle legacyPosition() {
    try {
      return MethodHandles.publicLookup().findConstructor(ClientboundEntityPositionSyncPacket.class,
        MethodType.methodType(void.class, int.class, PositionMoveRotation.class, boolean.class));
    } catch (NoSuchMethodException ignored) {
      return null;
    } catch (IllegalAccessException failure) {
      throw new IllegalStateException("Cannot access remote player position constructor", failure);
    }
  }

  private static MethodHandle legacyEntityId() {
    try {
      return MethodHandles.publicLookup().findVirtual(UnsafeValues.class, "nextEntityId", MethodType.methodType(int.class));
    } catch (NoSuchMethodException ignored) {
      return null;
    } catch (IllegalAccessException failure) {
      throw new IllegalStateException("Cannot access remote player entity ID allocator", failure);
    }
  }
}
