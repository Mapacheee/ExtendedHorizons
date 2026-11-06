package me.mapacheee.extendedhorizons.view;

import org.bukkit.World;

public record ViewSnapshot(
  World world,
  int x,
  int z,
  int nativeDistance,
  int distance,
  int direction,
  boolean enabled
) {}
