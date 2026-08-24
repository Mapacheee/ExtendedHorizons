package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

final class EncodedPayloadCopy {

    private EncodedPayloadCopy() {}

    static ByteBuf copy(ByteBufAllocator allocator, ByteBuf source) {
        ByteBuf copy = allocator.buffer(source.readableBytes());
        try {
            copy.writeBytes(source, source.readerIndex(), source.readableBytes());
            return copy;
        } catch (RuntimeException | Error throwable) {
            ReferenceCountUtil.release(copy);
            throw throwable;
        }
    }
}
