package me.mapacheee.extendedhorizons.view;

import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.compat.PacketAccess;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.connection.PaperCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class FarPlayerChannel extends ChannelDuplexHandler {

  static final String NAME = "extendedhorizons_far_players";
  private static final long SWING_MAX_AGE_NANOS = 400_000_000L;

  private final Set<UUID> profiles = new HashSet<>();
  private final Set<UUID> hidden = new HashSet<>();
  private final Set<Integer> nativePlayers = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean nativePlayerRemoved = new AtomicBoolean();
  private final IntOpenHashSet replicaIds = new IntOpenHashSet();
  private final Map<UUID, FarPlayerState.Snapshot> replicas = new HashMap<>();
  private ChannelHandlerContext context;

  static Channel connection(PlayerConfigurationConnection connection) throws IllegalAccessException {
    if (!(connection instanceof PaperCommonConnection<?> paper)) {
      return null;
    }
    var listener = (ServerCommonPacketListenerImpl) ConnectionField.LISTENER.get(paper);
    return listener.connection.channel;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    this.context = ctx;
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    this.clear();
    this.context = null;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
    if (message instanceof ClientboundBundlePacket bundle) {
      ctx.write(this.observeBundle(bundle), promise);
    } else {
      var removal = this.observe(message);
      ctx.write(removal == null ? message : new ClientboundBundlePacket(
        List.of(removal, (ClientboundAddEntityPacket) message)), promise);
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object message) {
    if (FarPlayerPackets.attacksReplica(message, this.replicaIds)
      || message instanceof ServerboundInteractPacket interact && this.replicaIds.contains(PacketAccess.interactionId(interact))) {
      return;
    }
    ctx.fireChannelRead(message);
  }

  private ClientboundBundlePacket observeBundle(ClientboundBundlePacket bundle) {
    List<Packet<? super ClientGamePacketListener>> packets = null;
    int observed = 0;
    for (var packet : bundle.subPackets()) {
      var removal = this.observe(packet);
      if (removal != null) {
        if (packets == null) {
          packets = new ArrayList<>();
          for (var previous : bundle.subPackets()) {
            if (packets.size() == observed) {
              break;
            }
            packets.add(previous);
          }
        }
        packets.add(removal);
      }
      if (packets != null) {
        packets.add(packet);
      }
      observed++;
    }
    return packets == null ? bundle : new ClientboundBundlePacket(packets);
  }

  private ClientboundRemoveEntitiesPacket observe(Object packet) {
    if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
      if (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
        for (var entry : info.entries()) {
          this.profiles.add(entry.profileId());
        }
      }
    } else if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
      for (UUID uuid : remove.profileIds()) {
        this.profiles.remove(uuid);
        this.remove(uuid);
      }
    } else if (packet instanceof ClientboundAddEntityPacket spawn && spawn.getType() == FarPlayerPackets.PLAYER) {
      this.nativePlayers.add(spawn.getId());
      return this.removeReplica(spawn.getUUID());
    } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
      boolean removedPlayer = false;
      var ids = PacketAccess.removedIds(remove).iterator();
      while (ids.hasNext()) {
        removedPlayer |= this.nativePlayers.remove(ids.nextInt());
      }
      if (removedPlayer) {
        this.nativePlayerRemoved.set(true);
      }
    } else if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundStartConfigurationPacket) {
      this.clear();
      this.nativePlayers.clear();
      this.nativePlayerRemoved.set(false);
      if (packet instanceof ClientboundStartConfigurationPacket) {
        this.profiles.clear();
      }
    }
    return null;
  }

  boolean tracksNativePlayer(int id) {
    return this.nativePlayers.contains(id);
  }

  boolean consumeNativePlayerRemoval() {
    return this.nativePlayerRemoved.get() && this.nativePlayerRemoved.getAndSet(false);
  }

  void hidden(UUID target, boolean value) {
    if (value) {
      this.hidden.add(target);
      if (this.remove(target)) {
        this.context.flush();
      }
    } else {
      this.hidden.remove(target);
    }
  }

  String details(FarPlayerState.Snapshot target) {
    var replica = this.replicas.get(target.uuid());
    return ", profile=" + this.profiles.contains(target.uuid()) + ", hidden=" + this.hidden.contains(target.uuid())
      + ", vanilla-entity=" + this.nativePlayers.contains(target.nativeId())
      + ", replica=" + (replica == null ? "absent" : replica.id());
  }

  boolean sync(ChunkStream chunks, List<FarPlayerState.Snapshot> candidates, Config.FarPlayers settings) {
    if (this.context == null || !this.context.channel().isActive()) {
      return false;
    }
    var visible = new HashMap<UUID, FarPlayerState.Snapshot>();
    for (var target : candidates) {
      if (target.current() && chunks.hasPlayerTerrain(target.world(), target.chunk())
        && this.profiles.contains(target.uuid()) && !this.hidden.contains(target.uuid())
        && !this.nativePlayers.contains(target.nativeId())) {
        visible.put(target.uuid(), target);
        if (visible.size() == settings.maxPerViewer()) {
          break;
        }
      }
    }
    var packets = new ArrayList<Packet<? super ClientGamePacketListener>>();
    var iterator = this.replicas.values().iterator();
    while (iterator.hasNext()) {
      var previous = iterator.next();
      var next = visible.get(previous.uuid());
      if (next == null || next.id() != previous.id() || next.generation() != previous.generation()) {
        iterator.remove();
        this.replicaIds.remove(previous.id());
        packets.add(new ClientboundRemoveEntitiesPacket(previous.id()));
      }
    }
    if (this.context.channel().isWritable()) {
      long now = System.nanoTime();
      for (var target : visible.values()) {
        var previous = this.replicas.put(target.uuid(), target);
        if (previous == null) {
          this.replicaIds.add(target.id());
          packets.add(new ClientboundAddEntityPacket(target.id(), target.uuid(), target.x(), target.y(), target.z(),
            target.pitch(), target.yaw(), FarPlayerPackets.PLAYER, 0, Vec3.ZERO, target.headYaw()));
          packets.add(new ClientboundSetEntityDataPacket(target.id(), target.appearance()));
          if (!target.equipment().isEmpty()) {
            packets.add(new ClientboundSetEquipmentPacket(target.id(), target.equipment(), true));
          }
        } else if (previous != target) {
          this.update(previous, target, now, packets);
        }
      }
    }
    if (!packets.isEmpty()) {
      this.context.write(new ClientboundBundlePacket(packets));
      return true;
    }
    return false;
  }

  private void update(FarPlayerState.Snapshot previous, FarPlayerState.Snapshot target, long now,
    List<Packet<? super ClientGamePacketListener>> packets) {
    if (previous.x() != target.x() || previous.y() != target.y() || previous.z() != target.z()
      || previous.yaw() != target.yaw() || previous.pitch() != target.pitch()
      || previous.onGround() != target.onGround()) {
      packets.add(FarPlayerPackets.position(target));
    }
    if (Mth.packDegrees(previous.headYaw()) != Mth.packDegrees(target.headYaw())) {
      var buffer = new FriendlyByteBuf(Unpooled.buffer(6));
      try {
        buffer.writeVarInt(target.id());
        buffer.writeByte(Mth.packDegrees(target.headYaw()));
        packets.add(ClientboundRotateHeadPacket.STREAM_CODEC.decode(buffer));
      } finally {
        buffer.release();
      }
    }
    if (previous.flags() != target.flags() || previous.hand() != target.hand() || previous.pose() != target.pose()
      || previous.skinParts() != target.skinParts()) {
      packets.add(new ClientboundSetEntityDataPacket(target.id(), target.appearance()));
    }
    if (previous.equipment() != target.equipment()) {
      var changes = new ArrayList<Pair<EquipmentSlot, ItemStack>>();
      for (int i = 0; i < target.equipment().size(); i++) {
        var item = target.equipment().get(i);
        if (!ItemStack.matches(previous.equipment().get(i).getSecond(), item.getSecond())) {
          changes.add(item);
        }
      }
      if (!changes.isEmpty()) {
        packets.add(new ClientboundSetEquipmentPacket(target.id(), changes, true));
      }
    }
    var swing = target.swing();
    if (swing != null && swing != previous.swing() && now - swing.at() < SWING_MAX_AGE_NANOS) {
      packets.add(FarPlayerPackets.swing(target.id(), swing));
    }
  }

  void forget(long chunk) {
    if (this.replicas.isEmpty()) {
      return;
    }
    var iterator = this.replicas.values().iterator();
    while (iterator.hasNext()) {
      var target = iterator.next();
      if (target.chunk() == chunk) {
        iterator.remove();
        this.replicaIds.remove(target.id());
        this.context.write(new ClientboundRemoveEntitiesPacket(target.id()));
      }
    }
  }

  void clear() {
    if (!this.replicaIds.isEmpty()) {
      if (this.context.channel().isActive()) {
        this.context.writeAndFlush(new ClientboundRemoveEntitiesPacket(this.replicaIds.toIntArray()));
      }
      this.replicas.clear();
      this.replicaIds.clear();
    }
  }

  private boolean remove(UUID uuid) {
    var removal = this.removeReplica(uuid);
    if (removal == null) {
      return false;
    }
    this.context.write(removal);
    return true;
  }

  private ClientboundRemoveEntitiesPacket removeReplica(UUID uuid) {
    var target = this.replicas.remove(uuid);
    if (target == null) {
      return null;
    }
    this.replicaIds.remove(target.id());
    return new ClientboundRemoveEntitiesPacket(target.id());
  }

  private static final class ConnectionField {

    private static final Field LISTENER = listener();

    private static Field listener() {
      for (Field field : PaperCommonConnection.class.getDeclaredFields()) {
        if (field.getType() == ServerCommonPacketListenerImpl.class) {
          field.setAccessible(true);
          return field;
        }
      }
      throw new IllegalStateException("Cannot resolve the Paper configuration connection");
    }
  }
}
