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
        PaletteState paletteState = this.decodePaletteState(buf, presets, presetCount, paletteBits);
        if (paletteState == null) {
            return;
        }

        StorageState storageState = this.readStorageState(buf, paletteState.paletteStorageBits(), storageLength);
        RemappedStorage remapped = this.remapStorage(paletteState, storageState);
        this.writeProcessedStorage(buf, readerIndex, paletteState, storageState.storageReaderIndex(), remapped, storageLength);
    }

    private PaletteState decodePaletteState(ByteBuf buf, int[] presets, int presetCount, int paletteBits) {
        int paletteStorageBits = paletteBits;
        int newPaletteBits = paletteBits;
        int[] newPalette = null;
        boolean[] obfuscatedPalette = null;
        boolean obfuscatedPaletteIsGlobal = false;
        int[] presetPalette = null;

        switch (paletteBits) {
            case 0 -> {
                int value = VarIntUtil.readVarInt(buf);
                if (!this.isObfuscatedState(value) || (presetCount == 1 && presets[0] == value)) {
                    return null;
                }
                obfuscatedPalette = new boolean[]{true};
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
                    System.arraycopy(
                        presets,
                        presetIndex + 1,
                        newPalette,
                        1 + presetIndex,
                        presetCount - (presetIndex + 1)
                    );
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
            }
            case 1, 2, 3, 4, 5, 6, 7, 8 -> {
                if (paletteBits <= 4) {
                    paletteStorageBits = 4;
                    newPaletteBits = 4;
                }
                PaletteExpansion expansion = this.expandPalette(buf, presets, presetCount, newPaletteBits);
                if (expansion == null) {
                    return null;
                }
                newPaletteBits = expansion.newPaletteBits();
                newPalette = expansion.newPalette();
                obfuscatedPalette = expansion.obfuscatedPalette();
                presetPalette = expansion.presetPalette();
            }
            default -> {
                paletteStorageBits = MathUtil.ceilLog2(this.stateRegistrySize);
                newPaletteBits = paletteStorageBits;
                obfuscatedPaletteIsGlobal = true;
                presetPalette = presets;
            }
        }

        return new PaletteState(
            paletteStorageBits,
            newPaletteBits,
            newPalette,
            obfuscatedPalette,
            obfuscatedPaletteIsGlobal,
            presetPalette
        );
    }

    private PaletteExpansion expandPalette(ByteBuf buf, int[] presets, int presetCount, int initialNewPaletteBits) {
        int paletteSize = VarIntUtil.readVarInt(buf);
        int[] palette = new int[paletteSize];
        boolean[] obfuscatedPalette = new boolean[paletteSize];
        int[] presetPalette = null;
        int extraPaletteSize = presetCount;
        int newPaletteBits = initialNewPaletteBits;
        boolean hasObfuscated = false;

        for (int i = 0; i < paletteSize; i++) {
            int value = VarIntUtil.readVarInt(buf);
            palette[i] = value;
            if (this.isObfuscatedState(value) && (presetCount != 1 || presets[0] != value)) {
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
            return null;
        }

        int[] newPalette = null;
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

        return new PaletteExpansion(newPaletteBits, newPalette, obfuscatedPalette, presetPalette);
    }

    private StorageState readStorageState(ByteBuf buf, int paletteStorageBits, boolean storageLength) {
        int storageReaderIndex = buf.readerIndex();
        if (paletteStorageBits == 0) {
            if (storageLength) {
                int bufWordCount = VarIntUtil.readVarInt(buf);
                if (bufWordCount != 0) {
                    throw new IllegalStateException("Invalid zero-sized storage length");
                }
            }
            return new StorageState(storageReaderIndex, 0L, 0, EMPTY_LONG_ARRAY);
        }

        long entryMask = (1L << paletteStorageBits) - 1L;
        int valuesPerWord = Long.SIZE / paletteStorageBits;
        int wordCount = (STORAGE_SIZE_3D + valuesPerWord - 1) / valuesPerWord;
        if (storageLength) {
            int bufWordCount = VarIntUtil.readVarInt(buf);
            if (bufWordCount != wordCount) {
                throw new IllegalStateException("Invalid storage length");
            }
        }

        long[] storage = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            storage[i] = buf.readLong();
        }
        return new StorageState(storageReaderIndex, entryMask, valuesPerWord, storage);
    }

    private RemappedStorage remapStorage(PaletteState paletteState, StorageState storageState) {
        boolean resize = paletteState.paletteStorageBits() != paletteState.newPaletteBits();
        int newValuesPerWord;
        long[] newStorage;

        if (!resize) {
            newValuesPerWord = storageState.valuesPerWord();
            newStorage = storageState.storage();
        } else {
            newValuesPerWord = Long.SIZE / paletteState.newPaletteBits();
            int newWordCount = (STORAGE_SIZE_3D + newValuesPerWord - 1) / newValuesPerWord;
            newStorage = new long[newWordCount];
        }

        int srcValuesPerWord = storageState.valuesPerWord();
        int paletteBits = paletteState.paletteStorageBits();
        long entryMask = storageState.entryMask();

        for (int y = 0; y < STORAGE_SIZE; y++) {
            for (int xz = 0; xz < STORAGE_SIZE_2D; xz++) {
                int blockIndex = storageIndex(xz, y);
                int wordIndex;
                int bitIndex;
                long word;
                int value;
                if (paletteBits != 0) {
                    wordIndex = blockIndex / srcValuesPerWord;
                    bitIndex = (blockIndex % srcValuesPerWord) * paletteBits;
                    word = storageState.storage()[wordIndex];
                    value = (int) ((word >> bitIndex) & entryMask);
                } else {
                    wordIndex = 0;
                    bitIndex = 0;
                    word = 0L;
                    value = 0;
                }

                int newValue;
                boolean obfuscateCurrent = paletteState.obfuscatedPaletteIsGlobal()
                    ? this.isObfuscatedState(value)
                    : (value >= 0
                        && value < paletteState.obfuscatedPalette().length
                        && paletteState.obfuscatedPalette()[value]);
                if (obfuscateCurrent) {
                    newValue = paletteState.presetPalette()[this.strategy.get()];
                    if (!resize && newValue != value) {
                        storageState.storage()[wordIndex] = word & ~(entryMask << bitIndex) | (long) newValue << bitIndex;
                        continue;
                    }
                } else {
                    newValue = value;
                }

                if (resize) {
                    int newWordIndex = blockIndex / newValuesPerWord;
                    int newBitIndex = (blockIndex % newValuesPerWord) * paletteState.newPaletteBits();
                    newStorage[newWordIndex] |= (long) newValue << newBitIndex;
                }
            }
        }

        return new RemappedStorage(newStorage, resize);
    }

    private void writeProcessedStorage(
        ByteBuf buf,
        int readerIndex,
        PaletteState paletteState,
        int storageReaderIndex,
        RemappedStorage remapped,
        boolean storageLength
    ) {
        buf.readerIndex(readerIndex);

        if (paletteState.newPalette() != null) {
            buf.writerIndex(readerIndex);
            buf.writeByte(paletteState.newPaletteBits());
            switch (paletteState.newPaletteBits()) {
                case 0 -> VarIntUtil.writeVarInt(buf, paletteState.newPalette()[0]);
                case 1, 2, 3, 4, 5, 6, 7, 8 -> {
                    VarIntUtil.writeVarInt(buf, paletteState.newPalette().length);
                    for (int value : paletteState.newPalette()) {
                        VarIntUtil.writeVarInt(buf, value);
                    }
                }
                default -> {
                    // global palette
                }
            }
        } else {
            buf.writerIndex(storageReaderIndex);
        }

        if (storageLength) {
            VarIntUtil.writeVarInt(buf, remapped.newStorage().length);
        }
        for (long word : remapped.newStorage()) {
            buf.writeLong(word);
        }
    }

    private record PaletteState(
        int paletteStorageBits,
        int newPaletteBits,
        int[] newPalette,
        boolean[] obfuscatedPalette,
        boolean obfuscatedPaletteIsGlobal,
        int[] presetPalette
    ) {}

    private record PaletteExpansion(
        int newPaletteBits,
        int[] newPalette,
        boolean[] obfuscatedPalette,
        int[] presetPalette
    ) {}

    private record StorageState(
        int storageReaderIndex,
        long entryMask,
        int valuesPerWord,
        long[] storage
    ) {}

    private record RemappedStorage(long[] newStorage, boolean resize) {}

    private boolean isObfuscatedState(int state) {
        return state >= 0 && state < this.obfuscatedStates.length && this.obfuscatedStates[state];
    }
}


