package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSendQueueEntryTest {

    @Test
    void acquiredPayloadSurvivesEntryRelease() {
        ByteBuf payload = Unpooled.buffer().writeLong(42L);
        ChunkSendQueueEntry entry = new ChunkSendQueueEntry(
            1L,
            UUID.randomUUID(),
            1L,
            1L,
            CompletableFuture.completedFuture(payload)
        );

        ByteBuf acquired = entry.acquirePayload();
        assertNotNull(acquired);
        entry.releaseFuture();
        entry.releaseFuture();

        assertEquals(42L, acquired.readLong());
        acquired.release();
        assertEquals(0, payload.refCnt());
    }

    @Test
    void releaseBeforeCompletionCancelsFuture() {
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        ChunkSendQueueEntry entry = new ChunkSendQueueEntry(
            1L,
            UUID.randomUUID(),
            1L,
            1L,
            future
        );
        entry.releaseFuture();

        ByteBuf payload = Unpooled.buffer().writeByte(1);
        assertTrue(future.isCancelled());
        assertFalse(future.complete(payload));

        assertEquals(1, payload.refCnt());
        payload.release();
    }
}
