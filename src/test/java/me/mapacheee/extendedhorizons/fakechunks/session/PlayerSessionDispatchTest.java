package me.mapacheee.extendedhorizons.fakechunks.session;

import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkSendQueueEntry;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionDispatchTest {

    @Test
    void periodicRefreshRevisitsStationaryChunksWithoutRefreshingNewPayloads() {
        PlayerSession session = readySession();
        long key = nextChunk(session);
        session.onChunkSent(key, session.beginChunkSend(key));
        long now = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertEquals(null, session.pollChunkForRefresh(now));
        }
        Long selected = null;
        for (int i = 0; i < 100 && selected == null; i++) {
            selected = session.pollChunkForRefresh(now + 31_000_000_000L);
        }
        assertEquals(Long.valueOf(key), selected);
        assertTrue(session.requestChunkRefresh(selected));
        assertFalse(session.isEhLoaded(key));
        assertArrayEquals(new long[]{key}, session.loadedBvChunkKeys());
        assertTrue(session.hasPendingChunkWork());
    }

    @Test
    void refreshKeepsTerrainAndFarEntitiesVisibleThroughFailureAndRetry() {
        PlayerSession session = readySession();
        long key = ChunkKeyCodec.pack(3, 0);
        while (nextChunk(session) != key) { }
        session.onChunkSent(key, session.beginChunkSend(key));
        assertTrue(session.requestChunkRefresh(key));
        assertTrue(session.isChunkReadyForEntities(key));
        assertTrue(session.drainPendingUnloads().isEmpty());
        assertEquals(key, nextChunk(session));
        long attempt = session.beginChunkSend(key);
        session.onChunkSendFailed(key, attempt);
        assertArrayEquals(new long[]{key}, session.loadedBvChunkKeys());
        assertTrue(session.isChunkReadyForEntities(key));
        assertTrue(session.requestChunkRefresh(key));
        assertEquals(key, nextChunk(session));
        long retry = session.beginChunkSend(key);
        session.onChunkSent(key, attempt);
        assertFalse(session.isEhLoaded(key), "Old write completions cannot finish a new refresh");
        session.onChunkSent(key, retry);
        assertTrue(session.isEhLoaded(key));
        assertArrayEquals(new long[]{key}, session.loadedBvChunkKeys());
    }

    @Test
    void refreshPendingAtTeleportStillUnloadsTheOldTerrain() {
        PlayerSession session = readySession();
        long key = nextChunk(session);
        session.onChunkSent(key, session.beginChunkSend(key));
        session.requestChunkRefresh(key);
        session.updateDistance(8); // Resizing storage must retain visible terrain, including (0, 0).
        session.moveTo(1000, 1000);
        assertEquals(java.util.List.of(key), session.drainPendingUnloads());
        assertEquals(0, session.loadedBvChunkKeys().length);
    }

    @Test
    void vanillaTakeoverCancelsRefreshAndKeepsItsChunkOutOfFakeUnloads() {
        PlayerSession session = readySession();
        long key = nextChunk(session);
        session.onChunkSent(key, session.beginChunkSend(key));
        session.requestChunkRefresh(key);
        assertEquals(key, nextChunk(session));
        var build = new CompletableFuture<ByteBuf>();
        session.enqueueChunk(new ChunkSendQueueEntry(key, session.worldId(), session.epoch(), 1L, build),
            session.worldId(), session.epoch());
        session.serverChunkAdd(ChunkKeyCodec.x(key), ChunkKeyCodec.z(key));
        assertTrue(build.isCancelled());
        assertFalse(session.requestChunkRefresh(key));
        assertEquals(0, session.loadedBvChunkKeys().length);
        session.moveTo(1000, 1000);
        assertTrue(session.drainPendingUnloads().isEmpty());
    }

    @Test
    void suppressedVanillaUnloadRemainsTrackedDuringRefresh() {
        PlayerSession session = readySession();
        long key = ChunkKeyCodec.pack(3, 0);
        session.serverChunkAdd(3, 0);
        assertTrue(session.serverChunkRemove(3, 0));
        assertArrayEquals(new long[]{key}, session.loadedBvChunkKeys());
        assertTrue(session.isChunkReadyForEntities(key));
        session.requestChunkRefresh(key);
        session.handleDimensionReset();
        assertEquals(0, session.loadedBvChunkKeys().length);
        assertFalse(session.isChunkReadyForEntities(key));
    }

    @Test
    void vanillaUnloadWhileDisabledDoesNotRetainClientTerrain() {
        PlayerSession session = readySession();
        session.serverChunkAdd(3, 0);
        session.enabled(false);
        assertFalse(session.serverChunkRemove(3, 0));
        assertEquals(0, session.loadedBvChunkKeys().length);
        assertFalse(session.isChunkReadyForEntities(ChunkKeyCodec.pack(3, 0)));
    }

    @Test
    void periodicRefreshDiscardsOldDimensionState() {
        PlayerSession session = readySession();
        long key = nextChunk(session);
        session.onChunkSent(key, session.beginChunkSend(key));
        session.handleDimensionReset();
        for (int i = 0; i < 100; i++) {
            assertEquals(null, session.pollChunkForRefresh(System.nanoTime() + 31_000_000_000L));
        }
    }

    @Test
    void dimensionResetDiscardsFarEntitiesAndOldWorldUnloads() {
        PlayerSession session = readySession();
        session.trackedFarPlayers().put(UUID.randomUUID(), 1_000_000_001);
        long key = nextChunk(session);
        long attempt = session.beginChunkSend(key);
        session.onChunkSent(key, attempt);
        session.moveTo(1000, 1000);
        session.handleDimensionReset();
        assertTrue(session.trackedFarPlayers().isEmpty());
        assertTrue(session.drainPendingUnloads().isEmpty());
    }

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
    void serverChunkReplacementKeepsPlannerAwake() {
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

        session.serverChunkAdd(ChunkKeyCodec.x(chunkKey), ChunkKeyCodec.z(chunkKey));

        assertTrue(future.isCancelled());
        assertTrue(session.chunkQueue().isEmpty());
        assertTrue(session.hasPendingChunkWork());
        assertNotNull(session.pollNextChunkKey());
    }

    @Test
    void serverChunksReceivedBeforeInitializationAreNotPlannedAsFake() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
        long serverChunkKey = ChunkKeyCodec.pack(0, 0);
        session.serverChunkAdd(0, 0);
        session.setChunkPos(0, 0);
        session.updateDistance(3);
        session.enabled(true);

        assertNotEquals(serverChunkKey, nextChunk(session));
    }

    @Test
    void movementClearsServerStateOutsideStorageWindow() {
        PlayerSession session = readySession();
        session.serverChunkAdd(0, 0);

        session.moveTo(3, 0);
        session.moveTo(6, 0);
        session.moveTo(9, 0);
        session.moveTo(10, 0);

        boolean queuedCollidingCoordinate = false;
        Long chunkKey;
        while ((chunkKey = session.pollNextChunkKey()) != null) {
            if (ChunkKeyCodec.x(chunkKey) == 13 && ChunkKeyCodec.z(chunkKey) == 0) {
                queuedCollidingCoordinate = true;
            }
        }
        assertTrue(queuedCollidingCoordinate);
    }

    @Test
    void movementWhileDisabledClearsServerStateFromPreviousLocation() {
        PlayerSession session = readySession();
        session.serverChunkAdd(0, 0);
        session.enabled(false);

        session.moveTo(13, 0);
        session.enabled(true);

        assertEquals(ChunkKeyCodec.pack(13, 0), nextChunk(session));
    }

    @Test
    void movementPrunesNativeChunksFromPreviousClientWindow() {
        PlayerSession session = readySession();
        session.serverChunkAdd(0, 0);

        session.moveTo(10, 0);
        session.enabled(true);
        session.moveTo(0, 0);
        session.enabled(true);

        assertEquals(ChunkKeyCodec.pack(0, 0), nextChunk(session));
    }

    @Test
    void bandwidthLimiterAllowsPayloadLargerThanBurstCapacity() {
        PlayerSession session = readySession();
        session.configureBandwidthLimiter(true, 100L, 100L);

        assertTrue(session.tryConsumeBandwidth(101L));
        assertFalse(session.tryConsumeBandwidth(1L));
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
