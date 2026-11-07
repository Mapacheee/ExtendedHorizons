package me.mapacheee.extendedhorizons.view;

import com.mojang.datafixers.util.Pair;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class FarPlayerState {

  private static final EquipmentSlot[] EQUIPMENT = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
    EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.BODY};

  private final Player player;
  private final UUID uuid;
  private final FarPlayerIndex index;
  private final Config.FarPlayers settings;
  private FarPlayerIndex.Cell cell;
  private volatile Snapshot snapshot;
  private volatile long generation;
  private boolean closed;
  private int replicaId;
  private Swing swing;

  FarPlayerState(Player player, FarPlayerIndex index, Config.FarPlayers settings) {
    this.player = player;
    this.uuid = player.getUniqueId();
    this.index = index;
    this.settings = settings;
  }

  Player player() {
    return this.player;
  }

  Snapshot snapshot() {
    return this.snapshot;
  }

  synchronized void capture(Location location, boolean worldEnabled) {
    if (this.closed) {
      return;
    }
    var entity = ((CraftPlayer) this.player).getHandle();
    if (!worldEnabled || this.player.getGameMode() == GameMode.SPECTATOR || entity.isRemoved()
      || !entity.isAlive() || !entity.visibleByDefault || entity.isInvisible()) {
      if (this.snapshot != null) {
        this.reset();
      }
      return;
    }
    World world = location.getWorld();
    int x = location.getBlockX() >> 4;
    int z = location.getBlockZ() >> 4;
    if (this.replicaId == 0) {
      this.replicaId = Bukkit.getUnsafe().nextEntityId(world);
    }
    Snapshot previous = this.snapshot;
    var data = entity.getEntityData();
    byte flags = data.get(Metadata.FLAGS);
    byte hand = data.get(Metadata.HAND);
    Pose pose = data.get(Metadata.POSE);
    var equipment = previous == null ? List.<Pair<EquipmentSlot, ItemStack>>of() : previous.equipment();
    if (this.settings.equipment()) {
      ArrayList<Pair<EquipmentSlot, ItemStack>> changes = null;
      for (int i = 0; i < EQUIPMENT.length; i++) {
        EquipmentSlot slot = EQUIPMENT[i];
        ItemStack current = entity.getItemBySlot(slot);
        if (equipment.isEmpty() || !ItemStack.matches(equipment.get(i).getSecond(), current)) {
          if (changes == null) {
            changes = new ArrayList<>(equipment);
          }
          var item = Pair.of(slot, current.copy());
          if (equipment.isEmpty()) {
            changes.add(item);
          } else {
            changes.set(i, item);
          }
        }
      }
      if (changes != null) {
        equipment = List.copyOf(changes);
      }
    }
    long chunk = ChunkPos.pack(x, z);
    if (previous != null && previous.world() == world && previous.chunk() == chunk
      && previous.x() == location.getX() && previous.y() == location.getY() && previous.z() == location.getZ()
      && previous.yaw() == entity.getYRot() && previous.pitch() == entity.getXRot() && previous.headYaw() == entity.getYHeadRot()
      && previous.onGround() == entity.onGround() && previous.flags() == flags && previous.hand() == hand
      && previous.pose() == pose && previous.equipment() == equipment && previous.swing() == this.swing) {
      return;
    }
    this.snapshot = new Snapshot(this, this.generation, this.uuid, this.replicaId, entity.getId(), world,
      chunk, location.getX(), location.getY(), location.getZ(), entity.getYRot(), entity.getXRot(),
      entity.getYHeadRot(), entity.onGround(), flags, hand, pose, equipment, this.swing);
    var nextCell = this.index.cell(world, x, z);
    if (!nextCell.equals(this.cell)) {
      this.index.move(this, this.cell, nextCell);
      this.cell = nextCell;
    }
  }

  synchronized void swing(InteractionHand hand) {
    if (!this.closed && this.settings.actions()) {
      var animation = ((CraftPlayer) this.player).getHandle().getItemInHand(hand).getAttackAnimation();
      this.swing = new Swing(hand, animation, System.nanoTime());
    }
  }

  synchronized void reset() {
    this.generation++;
    this.snapshot = null;
    this.swing = null;
    this.index.move(this, this.cell, null);
    this.cell = null;
  }

  synchronized void close() {
    this.closed = true;
    this.reset();
  }

  record Snapshot(FarPlayerState owner, long generation, UUID uuid, int id, int nativeId, World world,
    long chunk, double x, double y, double z, float yaw, float pitch, float headYaw, boolean onGround,
    byte flags, byte hand, Pose pose, List<Pair<EquipmentSlot, ItemStack>> equipment, Swing swing) {

    boolean current() {
      return this.generation == this.owner.generation;
    }

    List<SynchedEntityData.DataValue<?>> appearance() {
      return List.of(SynchedEntityData.DataValue.create(Metadata.FLAGS, this.flags),
        SynchedEntityData.DataValue.create(Metadata.HAND, this.hand),
        SynchedEntityData.DataValue.create(Metadata.POSE, this.pose));
    }
  }

  record Swing(InteractionHand hand, SwingAnimation animation, long at) {}

  private static final class Metadata {

    private static final EntityDataAccessor<Byte> FLAGS = accessor(Entity.class, "DATA_SHARED_FLAGS_ID");
    private static final EntityDataAccessor<Byte> HAND = accessor(LivingEntity.class, "DATA_LIVING_ENTITY_FLAGS");
    private static final EntityDataAccessor<Pose> POSE = accessor(Entity.class, "DATA_POSE");

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> accessor(Class<?> owner, String name) {
      try {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (EntityDataAccessor<T>) field.get(null);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalStateException("Cannot resolve player metadata " + name, exception);
      }
    }
  }
}
