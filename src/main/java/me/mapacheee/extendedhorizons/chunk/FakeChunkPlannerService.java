package me.mapacheee.extendedhorizons.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;

public class FakeChunkPlannerService {

  private final ConcurrentHashMap<Long, long[]> sortedOffsetsCache = new ConcurrentHashMap<>();
  private volatile long lastOffsetsCacheKey = Long.MIN_VALUE;
  private volatile long[] lastOffsetsCacheValue;

  public record PlanInput(
      int chunkX,
      int chunkZ,
      int viewDistance,
      int serverDistance,
      double safeSquareFactor,
      int cursorStart,
      int maxAdds,
      Set<Long> sentChunksSnapshot,
      LongPredicate sentContains,
      LongPredicate temporarilyUnavailable,
      Deque<Long> currentQueue,
      Set<Long> queuedSet,
      Set<Long> inflightSet) {}

  public record PlanResult(
      int safeSquareRadius,
      int neededCount,
      List<Long> chunksToUnload,
      Deque<Long> rebuiltQueue,
      Set<Long> rebuiltQueuedSet,
      List<Long> toAdd,
      int kept,
      int nextCursor) {}

  public PlanResult build(PlanInput input) {
    int chunkX = input.chunkX();
    int chunkZ = input.chunkZ();
    int effectiveRadius = input.viewDistance() + 1;
    int safeSquareRadius = (int) Math.floor(input.serverDistance() * input.safeSquareFactor());
    if (safeSquareRadius < 2) safeSquareRadius = 2;
    int effectiveRadiusSq = effectiveRadius * effectiveRadius;

    long[] sortedOffsets = getSortedOffsets(effectiveRadius, safeSquareRadius);
    int offsetCount = sortedOffsets.length;

    Set<Long> sentChunks = input.sentChunksSnapshot();
    LongPredicate sentContains = input.sentContains();
    LongPredicate temporarilyUnavailable = input.temporarilyUnavailable();
    Set<Long> queuedSet = input.queuedSet();
    Set<Long> inflightSet = input.inflightSet();

    List<Long> chunksToUnload = new ArrayList<>();
    for (Long chunkKey : sentChunks) {
      if (!isNeededChunk(chunkX, chunkZ, effectiveRadiusSq, safeSquareRadius, chunkKey)) {
        chunksToUnload.add(chunkKey);
      }
    }

    Deque<Long> rebuiltQueue = new ConcurrentLinkedDeque<>();
    Set<Long> rebuiltQueuedSet = ConcurrentHashMap.newKeySet();
    int kept = 0;
    for (Long existing : input.currentQueue()) {
      if (!isNeededChunk(chunkX, chunkZ, effectiveRadiusSq, safeSquareRadius, existing)) continue;
      if (sentContains != null && sentContains.test(existing)) continue;
      if (inflightSet.contains(existing)) continue;
      rebuiltQueue.addLast(existing);
      rebuiltQueuedSet.add(existing);
      kept++;
    }

    int maxAdds = Math.max(1, input.maxAdds());
    int cursor = Math.floorMod(input.cursorStart(), Math.max(1, offsetCount));
    List<Long> toAdd = new ArrayList<>(Math.min(offsetCount, maxAdds));
    List<Long> deferredUnavailable = new ArrayList<>(Math.min(offsetCount, maxAdds));
    int scanned = 0;
    while (scanned < offsetCount && toAdd.size() < maxAdds) {
      int index = Math.floorMod(cursor + scanned, offsetCount);
      long packedOffset = sortedOffsets[index];
      int dx = unpackX(packedOffset);
      int dz = unpackZ(packedOffset);
      long chunkKey = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
      scanned++;
      if (sentContains != null && sentContains.test(chunkKey)) continue;
      if (queuedSet.contains(chunkKey)) continue;
      if (inflightSet.contains(chunkKey)) continue;
      if (rebuiltQueuedSet.contains(chunkKey)) continue;
      boolean unavailable = temporarilyUnavailable != null && temporarilyUnavailable.test(chunkKey);
      if (unavailable) {
        if (deferredUnavailable.size() < maxAdds) {
          deferredUnavailable.add(chunkKey);
        }
        continue;
      }
      toAdd.add(chunkKey);
    }
    int deferredIndex = 0;
    while (toAdd.size() < maxAdds && deferredIndex < deferredUnavailable.size()) {
      toAdd.add(deferredUnavailable.get(deferredIndex++));
    }
    int nextCursor = offsetCount == 0 ? 0 : Math.floorMod(cursor + scanned, offsetCount);

    return new PlanResult(
        safeSquareRadius,
        offsetCount,
        chunksToUnload,
        rebuiltQueue,
        rebuiltQueuedSet,
        toAdd,
        kept,
        nextCursor);
  }

  private boolean isNeededChunk(
      int centerChunkX, int centerChunkZ, int effectiveRadiusSq, int safeSquareRadius, long chunkKey) {
    int dx = ChunkPos.getX(chunkKey) - centerChunkX;
    int dz = ChunkPos.getZ(chunkKey) - centerChunkZ;
    int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
    if (chebyshev <= safeSquareRadius) return false;
    return dx * dx + dz * dz <= effectiveRadiusSq;
  }

  private long[] getSortedOffsets(int effectiveRadius, int safeSquareRadius) {
    long cacheKey = (((long) effectiveRadius) << 32) | (safeSquareRadius & 0xFFFFFFFFL);
    long[] hot = lastOffsetsCacheValue;
    if (hot != null && lastOffsetsCacheKey == cacheKey) {
      return hot;
    }
    long[] cached = sortedOffsetsCache.get(cacheKey);
    if (cached != null) {
      lastOffsetsCacheKey = cacheKey;
      lastOffsetsCacheValue = cached;
      return cached;
    }
    long[] built = buildSortedOffsets(effectiveRadius, safeSquareRadius);
    long[] raced = sortedOffsetsCache.putIfAbsent(cacheKey, built);
    long[] result = raced == null ? built : raced;
    lastOffsetsCacheKey = cacheKey;
    lastOffsetsCacheValue = result;
    return result;
  }

  private long[] buildSortedOffsets(int effectiveRadius, int safeSquareRadius) {
    List<long[]> sortable = new ArrayList<>();
    for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
      for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
        int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
        if (chebyshev <= safeSquareRadius) continue;
        int distSq = dx * dx + dz * dz;
        if (distSq > effectiveRadius * effectiveRadius) continue;
        sortable.add(new long[] {pack(dx, dz), distSq});
      }
    }
    sortable.sort(Comparator.comparingLong(v -> v[1]));
    long[] offsets = new long[sortable.size()];
    for (int i = 0; i < sortable.size(); i++) {
      offsets[i] = sortable.get(i)[0];
    }
    return offsets;
  }

  private static long pack(int x, int z) {
    return (((long) x) << 32) | (z & 0xFFFFFFFFL);
  }

  private static int unpackX(long packed) {
    return (int) (packed >> 32);
  }

  private static int unpackZ(long packed) {
    return (int) packed;
  }
}
