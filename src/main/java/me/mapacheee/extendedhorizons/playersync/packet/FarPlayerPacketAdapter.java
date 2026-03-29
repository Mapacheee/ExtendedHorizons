package me.mapacheee.extendedhorizons.playersync.packet;

import java.util.UUID;
import org.bukkit.entity.Player;

public interface FarPlayerPacketAdapter {

  boolean isReady();

  boolean spawn(Player viewer, Player target);

  boolean update(Player viewer, Player target);

  boolean animateSwing(Player viewer, Player target);

  void despawn(Player viewer, UUID targetId, int entityId);
}
