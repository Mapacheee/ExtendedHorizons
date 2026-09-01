package me.mapacheee.extendedhorizons.fakechunks.session;

import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkSendQueueEntry;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionDispatchTest {

    @Test
    void chunkBecomesLoadedOnlyAfterWriteSuccess() {
        PlayerSession session = readySession();
        long chunkKey = nextChunk(session);

        long sendAttempt = session.beginChunkSend(chunkKey);
        assertTrue(sendAttempt > 0L);
        assertFalse(session.isEhLoaded(chunkKey));
        session.onChunkSent(chunkKey, sendAttempt);

        assertTrue(session.isEhLoaded(chunkKey));
    }

    @Test
    void failedOrInvalidatedWriteDoesNotRemainLoaded() {
        PlayerSession failedSession = readySession();
        long failedKey = nextChunk(failedSession);
        long failedAttempt = failedSession.beginChunkSend(failedKey);
        assertTrue(failedAttempt > 0L);
        failedSession.onChunkSendFailed(failedKey, failedAttempt);
        assertFalse(failedSession.isEhLoaded(failedKey));

        PlayerSession invalidatedSession = readySession();
        long invalidatedKey = nextChunk(invalidatedSession);
        long invalidatedAttempt = invalidatedSession.beginChunkSend(invalidatedKey);
        assertTrue(invalidatedAttempt > 0L);
        assertTrue(invalidatedSession.invalidateChunk(invalidatedKey));
        invalidatedSession.onChunkSent(invalidatedKey, invalidatedAttempt);
        assertFalse(invalidatedSession.isEhLoaded(invalidatedKey));
    }

    @Test
    void staleWriteCompletionCannotCommitNewSendAttempt() {
        PlayerSession session = readySession();
        long chunkKey = nextChunk(session);
        long firstAttempt = session.beginChunkSend(chunkKey);
        assertTrue(firstAttempt > 0L);

        assertTrue(session.invalidateChunk(chunkKey));
        assertEquals(chunkKey, nextChunk(session));
        long secondAttempt = session.beginChunkSend(chunkKey);
        assertTrue(secondAttempt > firstAttempt);

        session.onChunkSent(chunkKey, firstAttempt);
        session.onChunkSendFailed(chunkKey, firstAttempt);
        assertFalse(session.isEhLoaded(chunkKey));

        session.onChunkSent(chunkKey, secondAttempt);
        assertTrue(session.isEhLoaded(chunkKey));
    }

    @Test
    void closedSessionRejectsLateQueueEntry() {
        PlayerSession session = readySession();
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        ChunkSendQueueEntry first = new ChunkSendQueueEntry(
            1L,
            session.worldId(),
            session.epoch(),
            1L,
            future
        );
        assertTrue(session.enqueueChunk(first, session.worldId(), session.epoch()));

        session.close();

        assertTrue(future.isCancelled());
        ChunkSendQueueEntry late = new ChunkSendQueueEntry(
            2L,
            session.worldId(),
            session.epoch(),
            1L,
            CompletableFuture.completedFuture(null)
        );
        assertFalse(session.enqueueChunk(late, session.worldId(), session.epoch()));
        late.releaseFuture();
    }

    @Test
    void invalidationCancelsQueuedPayloadBeforeSend() {
        PlayerSession session = readySession();
        long chunkKey = nextChunk(session);
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        ChunkSendQueueEntry entry = new ChunkSendQueueEntry(
            chunkKey,
            session.worldId(),
            session.epoch(),
            1L,
            future
        );
        assertTrue(session.enqueueChunk(entry, session.worldId(), session.epoch()));

        session.invalidatePendingChunk(chunkKey);

        assertTrue(future.isCancelled());
        assertEquals(0L, session.beginChunkSend(chunkKey));
    }

    @Test
    void dimensionResetAllowsPreviouslyLoadedChunkToBeSentAgain() {
        PlayerSession session = readySession();
        long chunkKey = nextChunk(session);
        long firstAttempt = session.beginChunkSend(chunkKey);
        session.onChunkSent(chunkKey, firstAttempt);
        session.lastAdvertisedDistance(12);
        session.lastAdvertisedChunkKey(ChunkKeyCodec.pack(0, 0));
        long previousEpoch = session.epoch();

        session.handleDimensionReset();

        assertEquals(previousEpoch + 1L, session.epoch());
        assertFalse(session.enabled());
        assertFalse(session.isEhLoaded(chunkKey));
        assertEquals(-1, session.lastAdvertisedDistance());
        assertEquals(
            ChunkKeyCodec.pack(Integer.MIN_VALUE, Integer.MIN_VALUE),
            session.lastAdvertisedChunkKey()
        );
        session.onChunkSent(chunkKey, firstAttempt);
        assertFalse(session.isEhLoaded(chunkKey));
        assertEquals(chunkKey, nextChunk(session));

        long secondAttempt = session.beginChunkSend(chunkKey);
        assertTrue(secondAttempt > firstAttempt);
        session.onChunkSent(chunkKey, secondAttempt);
        assertTrue(session.isEhLoaded(chunkKey));
    }

    private static PlayerSession readySession() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
        session.setChunkPos(0, 0);
        session.updateDistance(3);
        session.enabled(true);
        return session;
    }

    private static long nextChunk(PlayerSession session) {
        Long chunkKey = session.pollNextChunkKey();
        assertNotNull(chunkKey);
        return chunkKey;
    }
}
