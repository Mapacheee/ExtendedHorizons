package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FastLightDataWriterTest {

    private static final int LIGHT_LAYER_BYTES = 2048;

    @ParameterizedTest
    @ValueSource(ints = {16, 24})
    void syntheticSkylightUsesRuntimeSectionCount(int chunkSectionCount) {
        int lightLayerCount = chunkSectionCount + 2;
        ByteBuf raw = Unpooled.buffer();
        try {
            FastLightDataWriter.writeSyntheticFullBrightLight(
                new FriendlyByteBuf(raw),
                chunkSectionCount,
                true
            );

            assertEquals(lightLayerCount, readBitSet(raw).cardinality());
            assertEquals(0, readBitSet(raw).cardinality());
            assertEquals(0, readBitSet(raw).cardinality());
            assertEquals(lightLayerCount, readBitSet(raw).cardinality());
            assertFullBrightLayers(raw, lightLayerCount);
            assertEquals(0, VarInt.read(raw));
            assertFalse(raw.isReadable());
        } finally {
            raw.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24})
    void syntheticNoSkylightUsesExplicitDimensionFlag(int chunkSectionCount) {
        int lightLayerCount = chunkSectionCount + 2;
        ByteBuf raw = Unpooled.buffer();
        try {
            FastLightDataWriter.writeSyntheticFullBrightLight(
                new FriendlyByteBuf(raw),
                chunkSectionCount,
                false
            );

            assertEquals(0, readBitSet(raw).cardinality());
            assertEquals(lightLayerCount, readBitSet(raw).cardinality());
            assertEquals(lightLayerCount, readBitSet(raw).cardinality());
            assertEquals(0, readBitSet(raw).cardinality());
            assertEquals(0, VarInt.read(raw));
            assertFullBrightLayers(raw, lightLayerCount);
            assertFalse(raw.isReadable());
        } finally {
            raw.release();
        }
    }

    private static BitSet readBitSet(ByteBuf in) {
        int length = VarInt.read(in);
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
            values[i] = in.readLong();
        }
        return BitSet.valueOf(values);
    }

    private static void assertFullBrightLayers(ByteBuf in, int expectedCount) {
        assertEquals(expectedCount, VarInt.read(in));
        for (int i = 0; i < expectedCount; i++) {
            assertEquals(LIGHT_LAYER_BYTES, VarInt.read(in));
            for (int j = 0; j < LIGHT_LAYER_BYTES; j++) {
                assertEquals(0xFF, in.readUnsignedByte());
            }
        }
    }
}
