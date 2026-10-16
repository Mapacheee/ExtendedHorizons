package me.mapacheee.extendedhorizons.fakechunks.planner;

import com.thewinterframework.service.annotation.Service;
import it.unimi.dsi.fastutil.ints.IntArrays;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

@Service
public final class ChunkPlannerService {

  public static final int MAX_CHUNK_DISTANCE = 128;
  private static final int MAX_RADIUS_INDEX = MAX_CHUNK_DISTANCE + 2;
  private static final int EDGE_BUFFER = 2;
  private static final double DIRECTION_WEIGHT = 0.3d;
  private static final long[][] RADIUS_ITERATION_LIST = new long[MAX_RADIUS_INDEX + 1][];
  private static final Cache<Direction, long[]> DIRECTIONAL_ITERATION_LIST = Caffeine.newBuilder()
    .maximumWeight(8 * 1024 * 1024)
    .weigher((Direction direction, long[] offsets) -> Math.max(1024, offsets.length * Long.BYTES))
    .build();

  static {
    for (int radius = 0; radius < RADIUS_ITERATION_LIST.length; radius++) {
      int current = radius;
      List<Integer> range = IntStream.rangeClosed(-radius - 1, radius + 1).boxed().toList();
      RADIUS_ITERATION_LIST[radius] = range.stream()
        .flatMap(x -> range.stream().map(z -> ChunkKeyCodec.pack(x, z)))
        .filter(key -> isWithinRange(ChunkKeyCodec.x(key), ChunkKeyCodec.z(key), current))
        .sorted(Comparator.comparingInt(key -> {
          int x = ChunkKeyCodec.x(key);
          int z = ChunkKeyCodec.z(key);
          return x * x + z * z;
        }))
        .mapToLong(Long::longValue)
        .toArray();
    }
  }

  public static long[] radiusIterationList(int radius) {
    int index = Math.clamp(radius, 0, MAX_RADIUS_INDEX);
    return RADIUS_ITERATION_LIST[index];
  }

  public static long[] directionalIterationList(int radius, double directionX, double directionZ) {
    return DIRECTIONAL_ITERATION_LIST.get(new Direction(Math.clamp(radius, 0, MAX_RADIUS_INDEX), directionX, directionZ),
      direction -> {
        long[] base = radiusIterationList(direction.radius());
        int[] indices = new int[base.length];
        double[] priorities = new double[base.length];
        for (int i = 0; i < base.length; i++) {
          indices[i] = i;
          priorities[i] = chunkPriority(ChunkKeyCodec.x(base[i]), ChunkKeyCodec.z(base[i]), direction.x(), direction.z());
        }
        IntArrays.mergeSort(indices, (left, right) -> {
          int order = Double.compare(priorities[left], priorities[right]);
          return order != 0 ? order : Integer.compare(left, right);
        });
        long[] sorted = new long[base.length];
        for (int i = 0; i < base.length; i++) sorted[i] = base[indices[i]];
        return sorted;
      });
  }

  public static double chunkPriority(int offsetX, int offsetZ, double directionX, double directionZ) {
    double distance = Math.sqrt((double) offsetX * offsetX + (double) offsetZ * offsetZ);
    if (distance == 0.0d) return -1.0d;
    if (directionX == 0.0d && directionZ == 0.0d) return distance;
    double alignment = (offsetX * directionX + offsetZ * directionZ) / distance;
    return distance * (1.0d - DIRECTION_WEIGHT * alignment);
  }

  private record Direction(int radius, double x, double z) {
  }

  public static boolean isWithinRange(int posX, int posZ, int viewDistance) {
    int absX = Math.abs(posX);
    int absZ = Math.abs(posZ);
    int outerBound = Math.max(absX, absZ);
    if (outerBound > viewDistance + 1) {
      return false;
    }
    long effectiveX = Math.max(0, absX - EDGE_BUFFER);
    long effectiveZ = Math.max(0, absZ - EDGE_BUFFER);
    long distSq = effectiveX * effectiveX + effectiveZ * effectiveZ;
    return distSq < (long) viewDistance * viewDistance;
  }
}

