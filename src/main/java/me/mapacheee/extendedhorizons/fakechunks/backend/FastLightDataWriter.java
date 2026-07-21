package me.mapacheee.extendedhorizons.fakechunks.backend;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

final class FastLightDataWriter {

    private static final int NO_SKY_HEADER_BYTES = 3;
    private static final int DEFAULT_SECTION_COUNT = 16;
    private static final int EXTRA_LIGHT_SECTIONS = 2;
    private static final int FULL_BRIGHT_ARRAY_BYTES = 2048;

    private static final MethodHandle GET_STORAGE_VISIBLE = createStorageVisibleHandle();

    private static MethodHandle createStorageVisibleHandle() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(SWMRNibbleArray.class, MethodHandles.lookup());
            return lookup.findGetter(SWMRNibbleArray.class, "storageVisible", byte[].class);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Unable to access SWMRNibbleArray.storageVisible", exception);
        }
    }

    private FastLightDataWriter() {
    }

    static int estimateLightDataSize(LevelChunk chunk) {
        byte[][] blockLight = java.util.Objects.requireNonNull(convertStarlightToBytes(chunk.starlight$getBlockNibbles(), false));
        byte[][] skyLight = convertStarlightToBytes(chunk.starlight$getSkyNibbles(), true);
        if (skyLight == null) {
            return estimateNoSkyLightSize(blockLight);
        }

        LightMasks masks = buildMasks(blockLight, skyLight);
        int size = 0;
        size += estimateBitSet(masks.notSkyEmpty.toLongArray());
        size += estimateBitSet(masks.notBlockEmpty.toLongArray());
        size += estimateBitSet(masks.skyEmpty.toLongArray());
        size += estimateBitSet(masks.blockEmpty.toLongArray());
        size += estimateByteArrayList(masks.skyData);
        size += estimateByteArrayList(masks.blockData);
        return size;
    }

    static boolean hasInitialisedLight(LevelChunk chunk) {
        SWMRNibbleArray[] blockNibbles = chunk.starlight$getBlockNibbles();
        if (blockNibbles != null) {
            for (SWMRNibbleArray layer : blockNibbles) {
                if (layer != null && layer.isInitialisedVisible()) {
                    return true;
                }
            }
        }
        SWMRNibbleArray[] skyNibbles = chunk.starlight$getSkyNibbles();
        if (skyNibbles != null) {
            for (SWMRNibbleArray layer : skyNibbles) {
                if (layer != null && layer.isInitialisedVisible()) {
                    return true;
                }
            }
        }
        return false;
    }

    static void writeSyntheticFullBrightLight(FriendlyByteBuf out, LevelChunk chunk) {
        SWMRNibbleArray[] blockNibbles = chunk.starlight$getBlockNibbles();
        boolean hasSky = chunk.starlight$getSkyNibbles() != null;
        int sectionCount = blockNibbles != null ? blockNibbles.length : chunk.getSectionsCount() + EXTRA_LIGHT_SECTIONS;

        byte[] fullBright = new byte[FULL_BRIGHT_ARRAY_BYTES];
        java.util.Arrays.fill(fullBright, (byte) 0xFF);

        if (hasSky) {
            BitSet notSkyEmpty = new BitSet(sectionCount);
            notSkyEmpty.set(0, sectionCount);
            BitSet notBlockEmpty = new BitSet(sectionCount);
            BitSet skyEmpty = new BitSet(sectionCount);
            BitSet blockEmpty = new BitSet(sectionCount);
            blockEmpty.set(0, sectionCount);

            writeBitSet(out, notSkyEmpty.toLongArray());
            writeBitSet(out, notBlockEmpty.toLongArray());
            writeBitSet(out, skyEmpty.toLongArray());
            writeBitSet(out, blockEmpty.toLongArray());

            VarIntUtil.writeVarInt(out, sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                FriendlyByteBuf.writeByteArray(out, fullBright);
            }
            out.writeByte(0);
        } else {
            BitSet notSkyEmpty = new BitSet(sectionCount);
            BitSet notBlockEmpty = new BitSet(sectionCount);
            notBlockEmpty.set(0, sectionCount);
            BitSet skyEmpty = new BitSet(sectionCount);
            skyEmpty.set(0, sectionCount);
            BitSet blockEmpty = new BitSet(sectionCount);

            writeBitSet(out, notSkyEmpty.toLongArray());
            writeBitSet(out, notBlockEmpty.toLongArray());
            writeBitSet(out, skyEmpty.toLongArray());
            writeBitSet(out, blockEmpty.toLongArray());

            out.writeByte(0);
            VarIntUtil.writeVarInt(out, sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                FriendlyByteBuf.writeByteArray(out, fullBright);
            }
        }
    }

    static void writeLightData(FriendlyByteBuf out, LevelChunk chunk) {
        byte[][] blockLight = java.util.Objects.requireNonNull(convertStarlightToBytes(chunk.starlight$getBlockNibbles(), false));
        byte[][] skyLight = convertStarlightToBytes(chunk.starlight$getSkyNibbles(), true);

        if (skyLight == null) {
            writeNoSkyLightData(out, blockLight);
            return;
        }

        LightMasks masks = buildMasks(blockLight, skyLight);

        writeBitSet(out, masks.notSkyEmpty.toLongArray());
        writeBitSet(out, masks.notBlockEmpty.toLongArray());
        writeBitSet(out, masks.skyEmpty.toLongArray());
        writeBitSet(out, masks.blockEmpty.toLongArray());
        writeByteArrayList(out, masks.skyData);
        writeByteArrayList(out, masks.blockData);
    }

    private static byte[][] convertStarlightToBytes(SWMRNibbleArray[] layers, boolean allowEmpty) {
        try {
            int layerCount = layers.length;
            byte[][] byteLayers = new byte[layerCount][];
            boolean converted = false;
            for (int i = 0; i < layerCount; i++) {
                SWMRNibbleArray layer = layers[i];
                if (layer != null && layer.isInitialisedVisible()) {
                    byteLayers[i] = (byte[]) GET_STORAGE_VISIBLE.invoke(layer);
                    converted = true;
                }
            }
            return converted || !allowEmpty ? byteLayers : null;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to convert starlight nibble arrays", throwable);
        }
    }

    private static void writeNoSkyLightData(ByteBuf out, byte[][] blockLight) {
        List<byte[]> blockData = new ArrayList<>(blockLight.length);
        NoSkyMasks masks = buildNoSkyMasks(blockLight, blockData);

        out.writeByte(0);
        writeBitSet(out, masks.notBlockEmpty().toLongArray());
        out.writeByte(0);
        writeBitSet(out, masks.blockEmpty().toLongArray());
        out.writeByte(0);
        writeByteArrayList(out, blockData);
    }

    private static int estimateNoSkyLightSize(byte[][] blockLight) {
        NoSkyMasks masks = buildNoSkyMasks(blockLight, null);

        int size = NO_SKY_HEADER_BYTES;
        size += estimateBitSet(masks.notBlockEmpty().toLongArray());
        size += estimateBitSet(masks.blockEmpty().toLongArray());
        size += varIntSize(masks.blockDataCount()) + masks.blockDataBytes();
        return size;
    }

    private static NoSkyMasks buildNoSkyMasks(byte[][] blockLight, List<byte[]> blockDataOut) {
        int sectionCount = blockLight.length;
        BitSet notBlockEmpty = new BitSet(sectionCount);
        BitSet blockEmpty = new BitSet(sectionCount);
        int blockDataCount = 0;
        int blockDataBytes = 0;

        for (int indexY = 0; indexY < sectionCount; indexY++) {
            byte[] block = blockLight[indexY];
            if (block == null) {
                blockEmpty.set(indexY);
                continue;
            }
            notBlockEmpty.set(indexY);
            blockDataCount++;
            blockDataBytes += varIntSize(block.length) + block.length;
            if (blockDataOut != null) {
                blockDataOut.add(block);
            }
        }

        return new NoSkyMasks(notBlockEmpty, blockEmpty, blockDataCount, blockDataBytes);
    }

    private static LightMasks buildMasks(byte[][] blockLight, byte[][] skyLight) {
        int sectionCount = blockLight.length;
        List<byte[]> skyData = new ArrayList<>(Math.min(sectionCount, DEFAULT_SECTION_COUNT));
        BitSet notSkyEmpty = new BitSet(sectionCount);
        BitSet skyEmpty = new BitSet(sectionCount);

        List<byte[]> blockData = new ArrayList<>(Math.min(sectionCount, DEFAULT_SECTION_COUNT));
        BitSet notBlockEmpty = new BitSet(sectionCount);
        BitSet blockEmpty = new BitSet(sectionCount);

        for (int indexY = 0; indexY < sectionCount; indexY++) {
            byte[] sky = skyLight[indexY];
            if (sky == null) {
                skyEmpty.set(indexY);
            } else {
                notSkyEmpty.set(indexY);
                skyData.add(sky);
            }
            byte[] block = blockLight[indexY];
            if (block == null) {
                blockEmpty.set(indexY);
            } else {
                notBlockEmpty.set(indexY);
                blockData.add(block);
            }
        }

        return new LightMasks(skyData, notSkyEmpty, skyEmpty, blockData, notBlockEmpty, blockEmpty);
    }

    private static void writeBitSet(ByteBuf out, long[] set) {
        VarIntUtil.writeVarInt(out, set.length);
        for (long value : set) {
            out.writeLong(value);
        }
    }

    private static int estimateBitSet(long[] set) {
        return varIntSize(set.length) + (set.length * Long.BYTES);
    }

    private static void writeByteArrayList(ByteBuf out, List<byte[]> list) {
        int len = list.size();
        if (len == 0) {
            out.writeByte(0);
            return;
        }
        VarIntUtil.writeVarInt(out, len);
        for (byte[] bytes : list) {
            FriendlyByteBuf.writeByteArray(out, bytes);
        }
    }

    private static int estimateByteArrayList(List<byte[]> list) {
        int len = list.size();
        int size = varIntSize(len);
        for (byte[] bytes : list) {
            size += varIntSize(bytes.length) + bytes.length;
        }
        return size;
    }

    private static int varIntSize(int value) {
        if ((value & (0xFFFFFFFF << 7)) == 0) {
            return 1;
        }
        if ((value & (0xFFFFFFFF << 14)) == 0) {
            return 2;
        }
        if ((value & (0xFFFFFFFF << 21)) == 0) {
            return 3;
        }
        if ((value & (0xFFFFFFFF << 28)) == 0) {
            return 4;
        }
        return 5;
    }

    private record LightMasks(
        List<byte[]> skyData,
        BitSet notSkyEmpty,
        BitSet skyEmpty,
        List<byte[]> blockData,
        BitSet notBlockEmpty,
        BitSet blockEmpty
    ) {}

    private record NoSkyMasks(
        BitSet notBlockEmpty,
        BitSet blockEmpty,
        int blockDataCount,
        int blockDataBytes
    ) {}
}


