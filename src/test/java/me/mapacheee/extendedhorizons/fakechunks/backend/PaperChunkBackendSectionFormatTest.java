package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperChunkBackendSectionFormatTest {

    @Test
    void antiXraySectionWritesBothCountFields() {
        ByteBuf antiXray = Unpooled.buffer();
        try {
            ChunkSectionCountWriter.write(new FriendlyByteBuf(antiXray), (short) 17, (short) 3);

            assertEquals(17, antiXray.readShort());
            assertEquals(3, antiXray.readShort());
        } finally {
            antiXray.release();
        }
    }
}
