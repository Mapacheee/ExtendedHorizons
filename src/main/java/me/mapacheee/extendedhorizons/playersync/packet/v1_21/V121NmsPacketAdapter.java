package me.mapacheee.extendedhorizons.playersync.packet.v1_21;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.mapacheee.extendedhorizons.playersync.packet.FarPlayerPacketAdapter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public class V121NmsPacketAdapter implements FarPlayerPacketAdapter {

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public boolean spawn(Player viewer, Player target) {
    ServerPlayer viewerHandle = handle(viewer);
    ServerPlayer targetHandle = handle(target);
    if (viewerHandle == null || targetHandle == null) return false;
    try {
      send(viewerHandle, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(targetHandle)));
      send(viewerHandle, createAddEntityPacket(targetHandle));
      ClientboundSetEntityDataPacket metadata = createEntityDataPacket(targetHandle);
      if (metadata != null) send(viewerHandle, metadata);
      send(viewerHandle, createEquipmentPacket(targetHandle));
      send(viewerHandle, new ClientboundRotateHeadPacket(targetHandle, yawByte(targetHandle)));
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  @Override
  public boolean update(Player viewer, Player target) {
    ServerPlayer viewerHandle = handle(viewer);
    ServerPlayer targetHandle = handle(target);
    if (viewerHandle == null || targetHandle == null) return false;
    try {
      send(
          viewerHandle,
          new ClientboundEntityPositionSyncPacket(
              targetHandle.getId(),
              PositionMoveRotation.of(targetHandle),
              targetHandle.onGround()));
      ClientboundSetEntityDataPacket metadata = createEntityDataPacket(targetHandle);
      if (metadata != null) send(viewerHandle, metadata);
      send(viewerHandle, createEquipmentPacket(targetHandle));
      send(viewerHandle, new ClientboundRotateHeadPacket(targetHandle, yawByte(targetHandle)));
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  @Override
  public boolean animateSwing(Player viewer, Player target) {
    ServerPlayer viewerHandle = handle(viewer);
    ServerPlayer targetHandle = handle(target);
    if (viewerHandle == null || targetHandle == null) return false;
    try {
      send(viewerHandle, new ClientboundAnimatePacket(targetHandle, 0));
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  @Override
  public void despawn(Player viewer, UUID targetId, int entityId) {
    ServerPlayer viewerHandle = handle(viewer);
    if (viewerHandle == null || targetId == null) return;
    try {
      send(viewerHandle, new ClientboundRemoveEntitiesPacket(entityId));
      send(viewerHandle, new ClientboundPlayerInfoRemovePacket(List.of(targetId)));
    } catch (Throwable ignored) {
    }
  }

  private ServerPlayer handle(Player player) {
    if (!(player instanceof CraftPlayer craftPlayer)) return null;
    try {
      return craftPlayer.getHandle();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private void send(ServerPlayer viewer, Packet<?> packet) {
    if (viewer == null || packet == null) return;
    viewer.connection.send(packet);
  }

  private ClientboundAddEntityPacket createAddEntityPacket(ServerPlayer target) {
    return new ClientboundAddEntityPacket(
        target.getId(),
        target.getUUID(),
        target.getX(),
        target.getY(),
        target.getZ(),
        target.getXRot(),
        target.getYRot(),
        target.getType(),
        0,
        target.getDeltaMovement(),
        target.getYHeadRot()
    );
  }

  private ClientboundSetEntityDataPacket createEntityDataPacket(ServerPlayer target) {
    SynchedEntityData entityData = target.getEntityData();
    List<SynchedEntityData.DataValue<?>> packed = entityData.packAll();
    if (packed == null || packed.isEmpty()) return null;
    return new ClientboundSetEntityDataPacket(target.getId(), packed);
  }

  private ClientboundSetEquipmentPacket createEquipmentPacket(ServerPlayer target) {
    List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> list = new ArrayList<>(6);
    list.add(Pair.of(EquipmentSlot.MAINHAND, target.getItemBySlot(EquipmentSlot.MAINHAND)));
    list.add(Pair.of(EquipmentSlot.OFFHAND, target.getItemBySlot(EquipmentSlot.OFFHAND)));
    list.add(Pair.of(EquipmentSlot.FEET, target.getItemBySlot(EquipmentSlot.FEET)));
    list.add(Pair.of(EquipmentSlot.LEGS, target.getItemBySlot(EquipmentSlot.LEGS)));
    list.add(Pair.of(EquipmentSlot.CHEST, target.getItemBySlot(EquipmentSlot.CHEST)));
    list.add(Pair.of(EquipmentSlot.HEAD, target.getItemBySlot(EquipmentSlot.HEAD)));
    return new ClientboundSetEquipmentPacket(target.getId(), list);
  }

  private byte yawByte(ServerPlayer player) {
    return (byte) ((int) (player.getYHeadRot() * 256.0F / 360.0F));
  }
}
