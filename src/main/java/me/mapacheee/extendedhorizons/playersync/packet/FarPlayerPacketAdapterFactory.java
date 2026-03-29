package me.mapacheee.extendedhorizons.playersync.packet;

import me.mapacheee.extendedhorizons.playersync.packet.v1_21.V121NmsPacketAdapter;

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
    public boolean spawn(org.bukkit.entity.Player viewer, org.bukkit.entity.Player target) {
      return false;
    }

    @Override
    public boolean update(org.bukkit.entity.Player viewer, org.bukkit.entity.Player target) {
      return false;
    }

    @Override
    public boolean animateSwing(org.bukkit.entity.Player viewer, org.bukkit.entity.Player target) {
      return false;
    }

    @Override
    public void despawn(org.bukkit.entity.Player viewer, java.util.UUID targetId, int entityId) {
    }
  }
}
