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

    Set<Long> neededChunks = new HashSet<>();
    for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
      for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
        int cx = chunkX + dx;
        int cz = chunkZ + dz;
        int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
        if (chebyshev <= safeSquareRadius) continue;
        if (dx * dx + dz * dz > effectiveRadius * effectiveRadius) continue;
        neededChunks.add(ChunkPos.asLong(cx, cz));
      }
    }

    Set<Long> chunksToUnload = new HashSet<>();
    for (Long chunkKey : input.sentChunksSnapshot()) {
      if (!neededChunks.contains(chunkKey)) {
        chunksToUnload.add(chunkKey);
      }
    }

    Deque<Long> rebuiltQueue = new ConcurrentLinkedDeque<>();
    Set<Long> rebuiltQueuedSet = ConcurrentHashMap.newKeySet();
    int kept = 0;
    for (Long existing : input.currentQueue()) {
      if (!neededChunks.contains(existing)) continue;
      if (input.sentChunksSnapshot().contains(existing)) continue;
      if (input.inflightSet().contains(existing)) continue;
      rebuiltQueue.addLast(existing);
      rebuiltQueuedSet.add(existing);
      kept++;
    }

    List<Long> toAdd = new ArrayList<>();
    for (Long chunkKey : neededChunks) {
      if (input.sentChunksSnapshot().contains(chunkKey)) continue;
      if (input.queuedSet().contains(chunkKey)) continue;
      if (input.inflightSet().contains(chunkKey)) continue;
      if (rebuiltQueuedSet.contains(chunkKey)) continue;
      toAdd.add(chunkKey);
    }

    toAdd.sort(
        Comparator.comparingInt(
            key -> {
              int cx = ChunkPos.getX(key);
              int cz = ChunkPos.getZ(key);
              int dx = cx - chunkX;
              int dz = cz - chunkZ;
              return dx * dx + dz * dz;
            }));

    return new PlanResult(
        safeSquareRadius,
        neededChunks,
        chunksToUnload,
        rebuiltQueue,
        rebuiltQueuedSet,
        toAdd,
        kept);
  }
}
