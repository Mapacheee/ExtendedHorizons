package me.mapacheee.extendedhorizons.fakechunks.antixray;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AntiXrayProcessorTest {

    private static final int BLOCK_COUNT = 16 * 16 * 16;

    @Test
    void remapsFiveBitStorageWithoutAssumingPowerOfTwoValuesPerWord() {
        ByteBuf data = createPaletteData(5, 17);
        try {
            AntiXrayProcessor processor = new AntiXrayProcessor(
                ReplacementStrategy.STATIC_ZERO,
                ReplacementPresets.createStatic(0),
                new int[]{16},
                256
            );

            processor.process(data, 0, false);

            assertEquals(5, data.readUnsignedByte());
            assertPalette(data, 17, index -> index);
            assertStorage(data, 5, index -> index % 17 == 16 ? 0 : index % 17);
        } finally {
            data.release();
        }
    }

    @Test
    void remapsStorageWhenPaletteExpansionChangesFromFourToFiveBits() {
        ByteBuf data = createPaletteData(4, 16);
        try {
            AntiXrayProcessor processor = new AntiXrayProcessor(
                ReplacementStrategy.STATIC_ZERO,
                ReplacementPresets.createStatic(100),
                new int[]{15},
                256
            );

            processor.process(data, 0, false);

            assertEquals(5, data.readUnsignedByte());
            assertPalette(data, 17, index -> index == 16 ? 100 : index);
            assertStorage(data, 5, index -> index % 16 == 15 ? 16 : index % 16);
        } finally {
            data.release();
        }
    }

    private static ByteBuf createPaletteData(int bits, int paletteSize) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(bits);
        VarIntUtil.writeVarInt(data, paletteSize);
        for (int value = 0; value < paletteSize; value++) {
            VarIntUtil.writeVarInt(data, value);
        }

        int valuesPerWord = Long.SIZE / bits;
        int wordCount = (BLOCK_COUNT + valuesPerWord - 1) / valuesPerWord;
        long[] storage = new long[wordCount];
        for (int index = 0; index < BLOCK_COUNT; index++) {
            int wordIndex = index / valuesPerWord;
            int bitIndex = (index % valuesPerWord) * bits;
            storage[wordIndex] |= (long) (index % paletteSize) << bitIndex;
        }
        for (long word : storage) {
            data.writeLong(word);
        }
        return data;
    }

    private static void assertPalette(ByteBuf data, int expectedSize, ExpectedValue expectedValue) {
        assertEquals(expectedSize, VarIntUtil.readVarInt(data));
        for (int index = 0; index < expectedSize; index++) {
            assertEquals(expectedValue.at(index), VarIntUtil.readVarInt(data), "palette index " + index);
        }
    }

    private static void assertStorage(ByteBuf data, int bits, ExpectedValue expectedValue) {
        int valuesPerWord = Long.SIZE / bits;
        int storageStart = data.readerIndex();
        long mask = (1L << bits) - 1L;
        for (int index = 0; index < BLOCK_COUNT; index++) {
            int wordIndex = index / valuesPerWord;
            int bitIndex = (index % valuesPerWord) * bits;
            long word = data.getLong(storageStart + wordIndex * Long.BYTES);
            assertEquals(expectedValue.at(index), (word >>> bitIndex) & mask, "block index " + index);
        }
    }

    @FunctionalInterface
    private interface ExpectedValue {
        int at(int index);
    }
}
