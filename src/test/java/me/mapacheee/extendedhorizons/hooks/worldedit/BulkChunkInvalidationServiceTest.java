package me.mapacheee.extendedhorizons.hooks.worldedit;

import io.netty.channel.embedded.EmbeddedChannel;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BulkChunkInvalidationServiceTest {
    @Test
    void refreshDoesNotWriteAnUnloadAndIgnoresStaleDimensionRequests() {
        var channel = new EmbeddedChannel();
        try {
            var service = new BulkChunkInvalidationService(null, null, null, null, new ChannelInjectionService());
            var session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
            session.setChunkPos(0, 0);
            session.updateDistance(3);
            session.enabled(true);
            long key = session.pollNextChunkKey();
            session.onChunkSent(key, session.beginChunkSend(key));
            long epoch = session.epoch();
            service.refreshSession(channel, session, session.worldId(), epoch, new long[]{key});
            channel.runPendingTasks();
            channel.flushOutbound();
            assertNull(channel.readOutbound(), "Refresh must not erase client terrain while the replacement builds");
            assertArrayEquals(new long[]{key}, session.loadedBvChunkKeys());
            assertEquals(Long.valueOf(key), session.pollNextChunkKey());
            session.onChunkSent(key, session.beginChunkSend(key));
            session.bumpEpoch();
            service.refreshSession(channel, session, session.worldId(), epoch, new long[]{key});
            channel.runPendingTasks();
            assertTrue(session.isEhLoaded(key), "A stale task must not restart the current chunk's build");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
