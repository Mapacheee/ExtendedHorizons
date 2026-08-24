package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodedPayloadCopyTest {

    @Test
    void copyHasIndependentStorageAndIndices() {
        ByteBuf canonical = Unpooled.buffer().writeBytes(new byte[] {1, 2, 3, 4});
        canonical.readByte();
        ByteBuf copy = EncodedPayloadCopy.copy(canonical.alloc(), canonical);
        try {
            copy.setByte(copy.readerIndex(), 99);
            copy.readByte();

            assertEquals(2, canonical.getUnsignedByte(canonical.readerIndex()));
            assertEquals(1, canonical.readerIndex());
            assertEquals(1, copy.readerIndex());
        } finally {
            copy.release();
            canonical.release();
        }
    }
}
