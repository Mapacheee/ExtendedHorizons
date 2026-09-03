package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @ParameterizedTest
    @ValueSource(ints = {16, 24})
    void fastSectionEnvelopeUsesRuntimeSectionCount(int sectionCount) {
        ByteBuf actual = Unpooled.buffer();
        try {
            FastChunkDataWriter.writeSections(
                new FriendlyByteBuf(actual),
                sectionCount,
                (sectionOut, index) -> {
                    sectionOut.writeShort(index);
                    sectionOut.writeShort(index + 1);
                    sectionOut.writeByte(index);
                }
            );

            int sectionBytes = VarInt.read(actual);
            assertEquals(sectionCount * 5, sectionBytes);
            for (int i = 0; i < sectionCount; i++) {
                assertEquals(i, actual.readUnsignedShort());
                assertEquals(i + 1, actual.readUnsignedShort());
                assertEquals(i, actual.readUnsignedByte());
            }
            assertFalse(actual.isReadable());
        } finally {
            actual.release();
        }
    }
}
