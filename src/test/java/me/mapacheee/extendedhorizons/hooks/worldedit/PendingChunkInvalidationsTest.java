package me.mapacheee.extendedhorizons.hooks.worldedit;

import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingChunkInvalidationsTest {
    @Test void retainsTheFinalEditAfterAnEarlierBatchWasDrained() {
        var queue = new PendingChunkInvalidations();
        UUID world = UUID.randomUUID();
        queue.offer(world, 1, 0);
        assertEquals(List.of(1L), queue.drain(100_000_000L, 256).get(world));
        queue.offer(world, 1, 110_000_000L);
        assertTrue(queue.drain(200_000_000L, 256).isEmpty());
        assertEquals(List.of(1L), queue.drain(210_000_000L, 256).get(world));
    }

    @Test void boundsBatchesAndCoalescesWithoutLosingOtherWorlds() {
        var queue = new PendingChunkInvalidations();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        queue.offer(a, 1, 0); queue.offer(b, 1, 1); queue.offer(a, 1, 2);
        assertEquals(List.of(1L), queue.drain(200_000_000L, 1).get(b));
        assertEquals(List.of(1L), queue.drain(200_000_000L, 1).get(a));
        assertTrue(queue.drain(200_000_000L, 256).isEmpty());
    }
}
