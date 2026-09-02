package me.mapacheee.extendedhorizons.fakechunks.disk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskChunkReaderCompatibilityTest {

    @AfterEach
    void clearCache() {
        DiskChunkReader.clearIncompatibleRegions();
    }

    @Test
    void incompatibleRegionDoesNotDisableAdjacentRegionsOrWorlds() {
        UUID worldId = UUID.randomUUID();
        UUID otherWorldId = UUID.randomUUID();

        assertTrue(DiskChunkReader.markRegionIncompatible(worldId, -1, -1));
        assertFalse(DiskChunkReader.markRegionIncompatible(worldId, -32, -32));

        assertFalse(DiskChunkReader.shouldAttemptDirectRead(worldId, -1, -1));
        assertFalse(DiskChunkReader.shouldAttemptDirectRead(worldId, -32, -32));
        assertTrue(DiskChunkReader.shouldAttemptDirectRead(worldId, -33, -33));
        assertTrue(DiskChunkReader.shouldAttemptDirectRead(otherWorldId, -1, -1));
    }
}
