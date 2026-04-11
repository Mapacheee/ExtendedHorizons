package me.mapacheee.extendedhorizons.fakechunks.antixray;

import io.netty.buffer.ByteBuf;

import java.util.Arrays;

public final class AntiXrayProcessor {

    private static final long[] EMPTY_LONG_ARRAY = new long[0];
    private static final int STORAGE_BITS = 4;
    private static final int STORAGE_SIZE = 1 << STORAGE_BITS;
    private static final int STORAGE_SIZE_2D = 1 << (STORAGE_BITS * 2);
    private static final int STORAGE_SIZE_3D = 1 << (STORAGE_BITS * 3);

    private final ReplacementStrategy strategy;
    private final ReplacementPresets presets;
    private final boolean[] obfuscatedStates;
    private final int stateRegistrySize;

    public AntiXrayProcessor(
        ReplacementStrategy strategy,
        ReplacementPresets presets,
        int[] obfuscatedStates,
        int stateRegistrySize
    ) {
        this.strategy = strategy;
        this.presets = presets;
        this.stateRegistrySize = stateRegistrySize;

        this.obfuscatedStates = new boolean[stateRegistrySize + 1];
        for (int state : obfuscatedStates) {
            if (state >= 0 && state < this.obfuscatedStates.length) {
                this.obfuscatedStates[state] = true;
            }
        }
    }

    private static int storageIndex(int blockXZ, int blockY) {
        return (blockY << (STORAGE_BITS * 2)) | blockXZ;
    }

    public void process(ByteBuf buf, int sectionY, boolean storageLength) {
        int[] presets = this.presets.getPresets(sectionY);
        int presetCount = presets.length;
        if (presetCount < 1) {
            return;
        }

        int readerIndex = buf.readerIndex();
        int paletteBits = buf.readByte();
        int paletteStorageBits = paletteBits;
        int newPaletteBits = paletteBits;

        int[] newPalette = null;
        boolean[] obfuscatedPalette = null;
        boolean obfuscatedPaletteIsGlobal = false;
        int[] presetPalette = null;

        switch (paletteBits) {
            case 0: {
                int value = VarIntUtil.readVarInt(buf);
                if (!this.isObfuscatedState(value)) {
                    return;
                } else if (presetCount == 1 && presets[0] == value) {
                    return;
                }
                obfuscatedPalette = new boolean[] { true };
                presetPalette = new int[presetCount];
                int presetIndex = Arrays.binarySearch(presets, value);
                if (presetIndex < 0) {
                    newPalette = new int[1 + presetCount];
                    System.arraycopy(presets, 0, newPalette, 1, presetCount);
                    for (int i = 0; i < presetCount; i++) {
                        presetPalette[i] = i + 1;
                    }
                } else {
                    newPalette = new int[presetCount];
                    System.arraycopy(presets, 0, newPalette, 1, presetIndex);
                    System.arraycopy(presets, presetIndex + 1,
                        newPalette, 1 + presetIndex, presetCount - (presetIndex + 1));
                    for (int i = 0; i < presetIndex; i++) {
                        presetPalette[i] = i + 1;
                    }
                    for (int i = presetIndex + 1; i < presetCount; i++) {
                        presetPalette[i] = i;
                    }
                    presetPalette[presetIndex] = 0;
                }
                newPalette[0] = value;
                newPaletteBits = java.lang.Math.max(4, MathUtil.ceilLog2(newPalette.length));
                break;
            }
            case 1, 2, 3, 4:
                paletteStorageBits = 4;
                newPaletteBits = 4;
            case 5, 6, 7, 8: {
                int paletteSize = VarIntUtil.readVarInt(buf);
                int[] palette = new int[paletteSize];
                obfuscatedPalette = new boolean[paletteSize];
                int extraPaletteSize = presetCount;
                boolean hasObfuscated = false;
                for (int i = 0; i < paletteSize; i++) {
                    int value = VarIntUtil.readVarInt(buf);
                    palette[i] = value;
                    if (this.isObfuscatedState(value)
                        && (presetCount != 1 || presets[0] != value)) {
                        obfuscatedPalette[i] = true;
                        hasObfuscated = true;
                    }
                    int presetIndex = Arrays.binarySearch(presets, value);
                    if (presetIndex >= 0) {
                        extraPaletteSize--;
                        if (presetPalette == null) {
                            presetPalette = new int[presetCount];
                            if (paletteSize != 1) {
                                Arrays.fill(presetPalette, -1);
                            }
                        }
                        presetPalette[presetIndex] = i;
                    }
                }
                if (!hasObfuscated) {
                    return;
                }
                if (extraPaletteSize > 0) {
                    newPalette = new int[paletteSize + extraPaletteSize];
                    System.arraycopy(palette, 0, newPalette, 0, paletteSize);
                    if (presetPalette != null) {
                        for (int i = 0, j = paletteSize; i < presetCount; i++) {
                            if (presetPalette[i] == -1) {
                                newPalette[j] = presets[i];
                                presetPalette[i] = j++;
                            }
                        }
                    } else {
                        System.arraycopy(presets, 0, newPalette, paletteSize, presetCount);
                        presetPalette = new int[presetCount];
                        for (int i = 0; i < presetCount; i++) {
                            presetPalette[i] = i + paletteSize;
                        }
                    }
                    int predictedBits = MathUtil.ceilLog2(paletteSize + extraPaletteSize);
                    newPaletteBits = java.lang.Math.max(predictedBits, newPaletteBits);
                }
                break;
            }
            default: {
                paletteStorageBits = MathUtil.ceilLog2(this.stateRegistrySize);
                newPaletteBits = paletteStorageBits;
                obfuscatedPaletteIsGlobal = true;
                presetPalette = presets;
            }
        }

        int storageIndex = buf.readerIndex();
        long entryMask;
        int valuesPerWord;
        long[] storage;
        if (paletteStorageBits == 0) {
            entryMask = 0;
            valuesPerWord = 0;
            if (storageLength) {
                int bufWordCount = VarIntUtil.readVarInt(buf);
                if (bufWordCount != 0) {
                    throw new IllegalStateException("Invalid zero-sized storage length");
                }
            }
            storage = EMPTY_LONG_ARRAY;
        } else {
            entryMask = (1L << paletteStorageBits) - 1L;
            valuesPerWord = (char) (Long.SIZE / paletteStorageBits);
            int wordCount = (STORAGE_SIZE_3D + valuesPerWord - 1) / valuesPerWord;
            if (storageLength) {
                int bufWordCount = VarIntUtil.readVarInt(buf);
                if (bufWordCount != wordCount) {
                    throw new IllegalStateException("Invalid storage length");
                }
            }
            storage = new long[wordCount];
            for (int i = 0; i < wordCount; i++) {
                storage[i] = buf.readLong();
            }
        }

        boolean resize = paletteStorageBits != newPaletteBits;
        int newValuesPerWord;
        long[] newStorage;
        if (!resize) {
            newValuesPerWord = valuesPerWord;
            newStorage = storage;
        } else {
            newValuesPerWord = (char) (Long.SIZE / newPaletteBits);
            int newWordCount = (STORAGE_SIZE_3D + newValuesPerWord - 1) / newValuesPerWord;
            newStorage = new long[newWordCount];
        }

        for (int y = 0; y < STORAGE_SIZE; y++) {
            for (int xz = 0; xz < STORAGE_SIZE_2D; xz++) {
                int blockIndex = storageIndex(xz, y);
                int wordIndex;
                int bitIndex;
                long word;
                int value;
                if (paletteStorageBits != 0) {
                    wordIndex = blockIndex / valuesPerWord;
                    bitIndex = (blockIndex - wordIndex * valuesPerWord) * paletteStorageBits;
                    word = storage[wordIndex];
                    value = (int) ((word >> bitIndex) & entryMask);
                } else {
                    wordIndex = 0;
                    bitIndex = 0;
                    word = 0L;
                    value = 0;
                }

                int newValue;
                boolean obfuscateCurrent = obfuscatedPaletteIsGlobal
                    ? this.isObfuscatedState(value)
                    : (value >= 0 && value < obfuscatedPalette.length && obfuscatedPalette[value]);
                if (obfuscateCurrent) {
                    newValue = presetPalette[this.strategy.get()];
                    if (!resize && newValue != value) {
                        storage[wordIndex] = word & ~(entryMask << bitIndex) | (long) newValue << bitIndex;
                        continue;
                    }
                } else {
                    newValue = value;
                }

                if (resize) {
                    int newWordIndex = blockIndex / newValuesPerWord;
                    int newBitIndex = (blockIndex - newWordIndex * newValuesPerWord) * newPaletteBits;
                    newStorage[newWordIndex] |= (long) newValue << newBitIndex;
                }
            }
        }

        buf.readerIndex(readerIndex);

        if (newPalette != null) {
            buf.writerIndex(readerIndex);
            buf.writeByte(newPaletteBits);
            switch (newPaletteBits) {
                case 0 -> VarIntUtil.writeVarInt(buf, newPalette[0]);
                case 1, 2, 3, 4, 5, 6, 7, 8 -> {
                    int newPaletteSize = newPalette.length;
                    VarIntUtil.writeVarInt(buf, newPaletteSize);
                    for (int i = 0; i < newPaletteSize; i++) {
                        VarIntUtil.writeVarInt(buf, newPalette[i]);
                    }
                }
                default -> {
                    // global palette
                }
            }
        } else {
            buf.writerIndex(storageIndex);
        }

        int newStorageSize = newStorage.length;
        if (storageLength) {
            VarIntUtil.writeVarInt(buf, newStorageSize);
        }
        for (int i = 0; i < newStorageSize; i++) {
            buf.writeLong(newStorage[i]);
        }
    }

    private boolean isObfuscatedState(int state) {
        return state >= 0 && state < this.obfuscatedStates.length && this.obfuscatedStates[state];
    }
}


