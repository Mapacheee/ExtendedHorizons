package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDispatchServiceTest {

    @Test
    void writableChannelDoesNotDeferPayloadLargerThanHighWaterMark() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 8));

        assertTrue(channel.isWritable());
        assertTrue(channel.bytesBeforeUnwritable() < 1_024L);
        assertFalse(ChunkDispatchService.shouldDeferWrite(channel));
        assertFalse(channel.finishAndReleaseAll());
    }
}
