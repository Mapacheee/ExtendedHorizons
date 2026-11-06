package me.mapacheee.extendedhorizons.view;

import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChunkOrder {

  private static final int MAX_RADIUS = 128;
  private static final int MAX_CACHED_ORDERS = 32;
  private static final int[] DIRECTION_X = {0, 1, 1, 0, -1, -1, -1, 0, 1};
  private static final int[] DIRECTION_Z = {0, 0, 1, 1, 1, 0, -1, -1, -1};
  private static final Map<Integer, long[]> ORDERS = new LinkedHashMap<>(16, 0.75f, true);

  private ChunkOrder() {
  }

  public static synchronized long[] offsets(int radius, int direction) {
    if (radius < 0 || radius > MAX_RADIUS || direction < 0 || direction > 8) {
      throw new IllegalArgumentException("Invalid chunk radius or direction");
    }
    int key = radius * 9 + direction;
    long[] cached = ORDERS.get(key);
    if (cached != null) {
      return cached;
    }

    int width = radius * 2 + 1;
    Offset[] offsets = new Offset[width * width];
    int index = 0;
    for (int z = -radius; z <= radius; z++) {
      for (int x = -radius; x <= radius; x++) {
        offsets[index++] = new Offset(x, z);
      }
    }
    int directionX = DIRECTION_X[direction];
    int directionZ = DIRECTION_Z[direction];
    Arrays.sort(offsets, Comparator.comparingInt(Offset::ring)
      .thenComparingInt(offset -> -(offset.x * directionX + offset.z * directionZ))
      .thenComparingInt(Offset::distanceSquared)
      .thenComparingInt(Offset::z)
      .thenComparingInt(Offset::x));

    long[] result = new long[offsets.length];
    for (int i = 0; i < offsets.length; i++) {
      Offset offset = offsets[i];
      result[i] = ChunkPos.pack(offset.x, offset.z);
    }
    if (ORDERS.size() >= MAX_CACHED_ORDERS) {
      ORDERS.remove(ORDERS.keySet().iterator().next());
    }
    ORDERS.put(key, result);
    return result;
  }

  public static int direction(double dx, double dz) {
    if (!Double.isFinite(dx) || !Double.isFinite(dz) || dx * dx + dz * dz < 0.000001) {
      return 0;
    }
    int sector = (int) Math.floor(Math.atan2(dz, dx) * 4.0 / Math.PI + 0.5);
    return Math.floorMod(sector, 8) + 1;
  }

  private record Offset(int x, int z) {

    int ring() {
      return Math.max(Math.abs(this.x), Math.abs(this.z));
    }

    int distanceSquared() {
      return this.x * this.x + this.z * this.z;
    }
  }
}
