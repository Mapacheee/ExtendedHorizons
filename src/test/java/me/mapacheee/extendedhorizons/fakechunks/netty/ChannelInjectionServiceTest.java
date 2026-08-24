package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelInjectionServiceTest {

    @Test
    void thirdPartyMutationDoesNotTouchCanonicalPayload() {
        MutatingEncoder encoder = new MutatingEncoder();
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        ChannelInjectionService service = new ChannelInjectionService();
        ByteBuf canonical = Unpooled.buffer().writeBytes(new byte[] {1, 2, 3, 4});
        ByteBuf outbound = channel.alloc().buffer(canonical.readableBytes());
        outbound.writeBytes(canonical, canonical.readerIndex(), canonical.readableBytes());

        ChannelPromise promise = service.writeEncodedFuture(channel, outbound);
        assertNotNull(promise);
        channel.runPendingTasks();
        channel.flushOutbound();

        ByteBuf sent = channel.readOutbound();
        assertNotNull(sent);
        assertTrue(promise.isSuccess());
        assertEquals(1, encoder.writeCount);
        assertEquals(99, sent.getUnsignedByte(sent.readerIndex()));
        assertEquals(1, canonical.getUnsignedByte(canonical.readerIndex()));

        sent.release();
        canonical.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void inactiveChannelConsumesEncodedPayload() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close().syncUninterruptibly();
        ChannelInjectionService service = new ChannelInjectionService();
        ByteBuf payload = Unpooled.buffer().writeByte(1);

        ChannelPromise promise = service.writeEncodedFuture(channel, payload);

        assertNotNull(promise);
        assertTrue(promise.isDone());
        assertFalse(promise.isSuccess());
        assertEquals(0, payload.refCnt());
        assertFalse(channel.finishAndReleaseAll());
    }

    private static final class MutatingEncoder extends MessageToMessageEncoder<ByteBuf> {

        private int writeCount;

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            this.writeCount++;
            msg.setByte(msg.readerIndex(), 99);
            out.add(msg.retain());
        }
    }
}
