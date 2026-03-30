package me.mapacheee.extendedhorizons.playersync.packet;

import java.util.UUID;
import me.mapacheee.extendedhorizons.playersync.packet.v1_21.V121NmsPacketAdapter;
import org.bukkit.entity.Player;

public final class FarPlayerPacketAdapterFactory {

  private FarPlayerPacketAdapterFactory() {
  }

  public static FarPlayerPacketAdapter create() {
    FarPlayerPacketAdapter adapter = new V121NmsPacketAdapter();
    if (adapter.isReady()) return adapter;
    return new NoopFarPlayerPacketAdapter();
  }

  private static final class NoopFarPlayerPacketAdapter implements FarPlayerPacketAdapter {

    @Override
    public boolean isReady() {
      return false;
    }

    @Override
    public boolean spawn(Player viewer, Player target) {
      return false;
    }

    @Override
    public boolean update(Player viewer, Player target) {
      return false;
    }

    @Override
    public boolean animateSwing(Player viewer, Player target) {
      return false;
    }

    @Override
    public MountMirror syncMount(
        Player viewer,
        Player target,
        MountMirror currentMirror) {
      return null;
    }

    @Override
    public void clearMount(Player viewer, MountMirror currentMirror) {
    }

    @Override
    public void despawn(Player viewer, UUID targetId, int entityId) {
    }
  }
}
