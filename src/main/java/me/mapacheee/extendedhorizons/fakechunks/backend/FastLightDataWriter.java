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

        int size = 3;
        size += estimateBitSet(masks.notBlockEmpty().toLongArray());
        size += estimateBitSet(masks.blockEmpty().toLongArray());
        size += varIntSize(masks.blockDataCount()) + masks.blockDataBytes();
        return size;
    }

    private static NoSkyMasks buildNoSkyMasks(byte[][] blockLight, List<byte[]> blockDataOut) {
        BitSet notBlockEmpty = new BitSet();
        BitSet blockEmpty = new BitSet();
        int blockDataCount = 0;
        int blockDataBytes = 0;

        for (int indexY = 0; indexY < blockLight.length; indexY++) {
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
        List<byte[]> skyData = new ArrayList<>(skyLight.length);
        BitSet notSkyEmpty = new BitSet();
        BitSet skyEmpty = new BitSet();

        List<byte[]> blockData = new ArrayList<>(blockLight.length);
        BitSet notBlockEmpty = new BitSet();
        BitSet blockEmpty = new BitSet();

        for (int indexY = 0; indexY < blockLight.length; indexY++) {
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


