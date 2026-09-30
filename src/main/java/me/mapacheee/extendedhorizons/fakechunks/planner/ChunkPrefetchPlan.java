package me.mapacheee.extendedhorizons.fakechunks.planner;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;

public record ChunkPrefetchPlan(long[] chunks) {

  private static final double WORLD_BLOCK_LIMIT = 30_000_000.0;
  private static final int WORLD_MIN_CHUNK = -1_875_000;
  private static final int WORLD_MAX_CHUNK = 1_874_999;
  private static final double DIRECTION_WEIGHT = 0.3;

  public static ChunkPrefetchPlan create(
    long currentCenter,
    long predictedCenter,
    int distance,
    int maxExtraDistance,
    double borderMinX,
    double borderMinZ,
    double borderMaxX,
    double borderMaxZ
  ) {
    int radius = Math.clamp(distance, 1, ChunkPlannerService.MAX_CHUNK_DISTANCE);
    int extraDistance = Math.clamp(maxExtraDistance, 1, 8);
    int currentX = ChunkKeyCodec.x(currentCenter);
    int currentZ = ChunkKeyCodec.z(currentCenter);
    int predictedX = ChunkKeyCodec.x(predictedCenter);
    int predictedZ = ChunkKeyCodec.z(predictedCenter);
    long shiftX = (long) predictedX - currentX;
    long shiftZ = (long) predictedZ - currentZ;
    if ((shiftX == 0 && shiftZ == 0)
      || Math.abs(shiftX) > extraDistance + 1 || Math.abs(shiftZ) > extraDistance + 1) {
      return new ChunkPrefetchPlan(new long[0]);
    }
    double minX = Math.max(-WORLD_BLOCK_LIMIT, borderMinX);
    double minZ = Math.max(-WORLD_BLOCK_LIMIT, borderMinZ);
    double maxX = Math.min(WORLD_BLOCK_LIMIT, borderMaxX);
    double maxZ = Math.min(WORLD_BLOCK_LIMIT, borderMaxZ);
    if (!(minX < maxX) || !(minZ < maxZ)) {
      return new ChunkPrefetchPlan(new long[0]);
    }

    LongArrayList candidates = new LongArrayList();
    long firstX = Math.max(WORLD_MIN_CHUNK, (long) predictedX - radius - 1);
    long lastX = Math.min(WORLD_MAX_CHUNK, (long) predictedX + radius + 1);
    for (long x = firstX; x <= lastX; x++) {
      double chunkMinX = x * 16.0;
      if (chunkMinX + 16.0 <= minX || chunkMinX >= maxX) {
        continue;
      }
      int predictedHalfWidth = halfWidth((int) (x - predictedX), radius);
      if (predictedHalfWidth < 0) {
        continue;
      }
      long predictedMinZ = (long) predictedZ - predictedHalfWidth;
      long predictedMaxZ = (long) predictedZ + predictedHalfWidth;
      int currentHalfWidth = halfWidth((int) (x - currentX), radius);
      if (currentHalfWidth < 0) {
        append(candidates, (int) x, predictedMinZ, predictedMaxZ, currentX, currentZ,
          shiftX, shiftZ, radius + extraDistance, minZ, maxZ);
        continue;
      }
      long currentMinZ = (long) currentZ - currentHalfWidth;
      long currentMaxZ = (long) currentZ + currentHalfWidth;
      append(candidates, (int) x, predictedMinZ, Math.min(predictedMaxZ, currentMinZ - 1),
        currentX, currentZ, shiftX, shiftZ, radius + extraDistance, minZ, maxZ);
      append(candidates, (int) x, Math.max(predictedMinZ, currentMaxZ + 1), predictedMaxZ,
        currentX, currentZ, shiftX, shiftZ, radius + extraDistance, minZ, maxZ);
    }

    long[] chunks = candidates.toLongArray();
    double shiftLength = Math.hypot(shiftX, shiftZ);
    double directionX = shiftX / shiftLength;
    double directionZ = shiftZ / shiftLength;
    LongArrays.quickSort(chunks, (left, right) -> {
      int comparison = Double.compare(priority(left, currentX, currentZ, directionX, directionZ),
        priority(right, currentX, currentZ, directionX, directionZ));
      if (comparison != 0) {
        return comparison;
      }
      comparison = Integer.compare(ChunkKeyCodec.x(left), ChunkKeyCodec.x(right));
      return comparison != 0 ? comparison : Integer.compare(ChunkKeyCodec.z(left), ChunkKeyCodec.z(right));
    });
    return new ChunkPrefetchPlan(chunks);
  }

  private static int halfWidth(int offsetX, int radius) {
    if (!ChunkPlannerService.isWithinRange(offsetX, 0, radius)) {
      return -1;
    }
    int low = 0;
    int high = radius + 1;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (ChunkPlannerService.isWithinRange(offsetX, middle, radius)) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  private static void append(
    LongArrayList candidates,
    int chunkX,
    long firstZ,
    long lastZ,
    int currentX,
    int currentZ,
    long shiftX,
    long shiftZ,
    int maxRadius,
    double borderMinZ,
    double borderMaxZ
  ) {
    long offsetX = (long) chunkX - currentX;
    for (long z = Math.max(WORLD_MIN_CHUNK, firstZ); z <= Math.min(WORLD_MAX_CHUNK, lastZ); z++) {
      long offsetZ = z - currentZ;
      if (offsetX * shiftX + offsetZ * shiftZ <= 0
        || !ChunkPlannerService.isWithinRange((int) offsetX, (int) offsetZ, maxRadius)) {
        continue;
      }
      double chunkMinZ = z * 16.0;
      if (chunkMinZ + 16.0 > borderMinZ && chunkMinZ < borderMaxZ) {
        candidates.add(ChunkKeyCodec.pack(chunkX, (int) z));
      }
    }
  }

  private static double priority(long key, int currentX, int currentZ, double directionX, double directionZ) {
    double offsetX = (long) ChunkKeyCodec.x(key) - currentX;
    double offsetZ = (long) ChunkKeyCodec.z(key) - currentZ;
    return Math.hypot(offsetX, offsetZ) - DIRECTION_WEIGHT * (offsetX * directionX + offsetZ * directionZ);
  }
}
