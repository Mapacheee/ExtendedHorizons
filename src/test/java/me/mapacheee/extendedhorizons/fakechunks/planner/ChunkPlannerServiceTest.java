package me.mapacheee.extendedhorizons.fakechunks.planner;

import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlannerServiceTest {

    @Test
    void iterationListContainsEveryChunkAcceptedByRangeCheck() {
        int radius = 32;
        Set<Long> planned = Arrays.stream(ChunkPlannerService.radiusIterationList(radius))
            .boxed()
            .collect(Collectors.toSet());

        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                if (ChunkPlannerService.isWithinRange(x, z, radius)) {
                    assertTrue(planned.contains(ChunkKeyCodec.pack(x, z)), "Missing offset " + x + "," + z);
                }
            }
        }
    }
}
