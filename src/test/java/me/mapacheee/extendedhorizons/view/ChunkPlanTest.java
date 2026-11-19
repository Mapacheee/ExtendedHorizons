package me.mapacheee.extendedhorizons.view;

import me.mapacheee.extendedhorizons.compat.PacketAccess;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlanTest {

  @Test
  void schedulesEveryNewlyExposedChunkIncludingTheNativeBoundary() {
    for (int radius : new int[]{3, 8, 32, 128}) {
      ViewSnapshot start = new ViewSnapshot(null, -15, 4, radius / 2, radius, 0, true);
      for (int dx : new int[]{-2, -1, 0, 1, 2}) {
        for (int dz : new int[]{-1, 0, 1}) {
          ViewSnapshot next = new ViewSnapshot(null, start.x() + dx, start.z() + dz,
            start.nativeDistance(), radius, 1, true);
          ChunkPlan plan = new ChunkPlan();
          plan.move(start, next);
          Set<Long> candidates = new HashSet<>();
          while (!plan.isEmpty()) {
            candidates.add(plan.first());
            plan.advance();
          }
          for (int z = next.z() - radius; z <= next.z() + radius; z++) {
            for (int x = next.x() - radius; x <= next.x() + radius; x++) {
              if (extended(next, x, z) && !extended(start, x, z)) {
                assertTrue(candidates.contains(PacketAccess.chunkKey(x, z)));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void keepsServingTheOriginalSweepWhileCrossingChunkBoundaries() {
    ChunkPlan plan = new ChunkPlan();
    ViewSnapshot start = new ViewSnapshot(null, 0, 0, 4, 32, 0, true);
    ViewSnapshot view = start;
    plan.rebuild(view);
    Set<Long> delivered = new HashSet<>();
    for (int tick = 0; tick < 1000; tick++) {
      ViewSnapshot next = new ViewSnapshot(null, 1 - view.x(), 0, 4, 32, view.x() == 0 ? 1 : 5, true);
      plan.move(view, next);
      view = next;
      for (int slot = 0; slot < 8 && !plan.isEmpty(); slot++) {
        long key = plan.first();
        assertEquals(key, plan.first()); // A denied read must leave the candidate available.
        plan.advance();
        if (extended(view, ChunkPos.getX(key), ChunkPos.getZ(key))) {
          delivered.add(key);
        }
      }
    }
    for (int z = -32; z <= 32; z++) {
      for (int x = -32; x <= 32; x++) {
        if (extended(start, x, z) && extended(new ViewSnapshot(null, 1, 0, 4, 32, 1, true), x, z)) {
          assertTrue(delivered.contains(PacketAccess.chunkKey(x, z)));
        }
      }
    }
  }

  private static boolean extended(ViewSnapshot view, int x, int z) {
    int dx = x - view.x();
    int dz = z - view.z();
    return dx * dx + dz * dz > view.nativeDistance() * view.nativeDistance()
      && ChunkTrackingView.isWithinDistance(view.x(), view.z(), view.distance(), x, z, false);
  }
}
