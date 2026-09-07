package me.mapacheee.extendedhorizons.fakechunks.session;

import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSessionDirectionTest {
    @Test
    void changingDirectionPreservesAllChunksAndStablePriorityOrderAtLargeRadii() throws Exception {
        var update = PlayerSession.class.getDeclaredMethod("updateLookDirection", double.class, double.class);
        update.setAccessible(true);
        var offsets = PlayerSession.class.getDeclaredField("chunksInDistance");
        offsets.setAccessible(true);
        for (int radius : new int[]{3, 128}) {
            PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
            session.setChunkPos(0, 0);
            session.updateDistance(radius);
            long[] base = ChunkPlannerService.radiusIterationList(radius).clone();
            Map<Long, Integer> originalOrder = new HashMap<>();
            for (int i = 0; i < base.length; i++) originalOrder.put(base[i], i);
            for (double direction : new double[]{1.0, -1.0}) {
                update.invoke(session, direction, 0.0);
                long[] actual = (long[]) offsets.get(session);
                assertEquals(base.length, actual.length);
                Set<Long> remaining = new HashSet<>(originalOrder.keySet());
                double previous = Double.NEGATIVE_INFINITY;
                int previousIndex = -1;
                for (long key : actual) {
                    assertTrue(remaining.remove(key), "Every planned chunk must appear exactly once");
                    int x = ChunkKeyCodec.x(key);
                    int z = ChunkKeyCodec.z(key);
                    double distance = Math.sqrt(x * x + z * z);
                    double priority = distance == 0 ? -1 : distance * (1 - 0.3 * x * direction / distance);
                    assertTrue(priority >= previous - 1e-12, "Direction changes must retain ascending priority");
                    if (Double.compare(previous, priority) == 0) {
                        assertTrue(originalOrder.get(key) > previousIndex, "Equal priorities retain planner order");
                    }
                    previous = priority;
                    previousIndex = originalOrder.get(key);
                }
                assertTrue(remaining.isEmpty());
                assertArrayEquals(base, ChunkPlannerService.radiusIterationList(radius), "Shared planner order is immutable");
            }
        }
    }
}
