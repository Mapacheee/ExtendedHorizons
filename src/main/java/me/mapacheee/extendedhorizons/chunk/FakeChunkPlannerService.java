package me.mapacheee.extendedhorizons.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.minecraft.world.level.ChunkPos;

public class FakeChunkPlannerService {

  private final ConcurrentHashMap<Long, long[]> sortedOffsetsCache = new ConcurrentHashMap<>();

  public record PlanInput(
      int chunkX,
      int chunkZ,
      int viewDistance,
      int serverDistance,
      double safeSquareFactor,
      Set<Long> sentChunksSnapshot,
      Deque<Long> currentQueue,
      Set<Long> queuedSet,
      Set<Long> inflightSet) {}

  public record PlanResult(
      int safeSquareRadius,
      Set<Long> neededChunks,
      Set<Long> chunksToUnload,
      Deque<Long> rebuiltQueue,
      Set<Long> rebuiltQueuedSet,
      List<Long> toAdd,
      int kept) {}

  public PlanResult build(PlanInput input) {
    int chunkX = input.chunkX();
    int chunkZ = input.chunkZ();
    int effectiveRadius = input.viewDistance() + 1;
    int safeSquareRadius = (int) Math.floor(input.serverDistance() * input.safeSquareFactor());
    if (safeSquareRadius < 2) safeSquareRadius = 2;

    long[] sortedOffsets = getSortedOffsets(effectiveRadius, safeSquareRadius);
    int offsetCount = sortedOffsets.length;
    Set<Long> neededChunks = new HashSet<>(Math.max(16, offsetCount * 2));
    for (long packedOffset : sortedOffsets) {
      int dx = unpackX(packedOffset);
      int dz = unpackZ(packedOffset);
      neededChunks.add(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
    }

    Set<Long> sentChunks = input.sentChunksSnapshot();
    Set<Long> queuedSet = input.queuedSet();
    Set<Long> inflightSet = input.inflightSet();

    Set<Long> chunksToUnload = new HashSet<>();
    for (Long chunkKey : sentChunks) {
      if (!neededChunks.contains(chunkKey)) {
        chunksToUnload.add(chunkKey);
      }
    }

    Deque<Long> rebuiltQueue = new ConcurrentLinkedDeque<>();
    Set<Long> rebuiltQueuedSet = ConcurrentHashMap.newKeySet();
    int kept = 0;
    for (Long existing : input.currentQueue()) {
      if (!neededChunks.contains(existing)) continue;
      if (sentChunks.contains(existing)) continue;
      if (inflightSet.contains(existing)) continue;
      rebuiltQueue.addLast(existing);
      rebuiltQueuedSet.add(existing);
      kept++;
    }

    List<Long> toAdd = new ArrayList<>(offsetCount);
    for (long packedOffset : sortedOffsets) {
      int dx = unpackX(packedOffset);
      int dz = unpackZ(packedOffset);
      long chunkKey = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
      if (sentChunks.contains(chunkKey)) continue;
      if (queuedSet.contains(chunkKey)) continue;
      if (inflightSet.contains(chunkKey)) continue;
      if (rebuiltQueuedSet.contains(chunkKey)) continue;
      toAdd.add(chunkKey);
    }

    return new PlanResult(
        safeSquareRadius,
        neededChunks,
        chunksToUnload,
        rebuiltQueue,
        rebuiltQueuedSet,
        toAdd,
        kept);
  }

  private long[] getSortedOffsets(int effectiveRadius, int safeSquareRadius) {
    long cacheKey = (((long) effectiveRadius) << 32) | (safeSquareRadius & 0xFFFFFFFFL);
    return sortedOffsetsCache.computeIfAbsent(cacheKey, k -> buildSortedOffsets(effectiveRadius, safeSquareRadius));
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
