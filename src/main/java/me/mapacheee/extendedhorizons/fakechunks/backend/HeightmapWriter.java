package me.mapacheee.extendedhorizons.fakechunks.backend;

import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

final class HeightmapWriter {

    private static final Heightmap.Types[] SENDABLE_HEIGHTMAP_TYPES = Arrays.stream(Heightmap.Types.values())
        .filter(Heightmap.Types::sendToClient)
        .toArray(Heightmap.Types[]::new);
    private static final int[] SENDABLE_HEIGHTMAP_TYPE_IDS = Arrays.stream(SENDABLE_HEIGHTMAP_TYPES)
        .mapToInt(Enum::ordinal)
        .toArray();

    private HeightmapWriter() {
    }

    static void writeHeightmaps(FriendlyByteBuf out, LevelChunk chunk) {
        long[][] heightmapsData = extractHeightmapsData(chunk);
        int heightmapsCount = 0;
        for (long[] data : heightmapsData) {
            if (data != null) {
                heightmapsCount++;
            }
        }

        VarInt.write(out, heightmapsCount);
        for (int i = 0; i < heightmapsData.length; i++) {
            long[] data = heightmapsData[i];
            if (data != null) {
                VarInt.write(out, SENDABLE_HEIGHTMAP_TYPE_IDS[i]);
                FriendlyByteBuf.writeLongArray(out, data);
            }
        }
    }

    static int estimateHeightmapsSize(LevelChunk chunk) {
        long[][] heightmapsData = extractHeightmapsData(chunk);
        int heightmapsCount = 0;
        for (long[] data : heightmapsData) {
            if (data != null) {
                heightmapsCount++;
            }
        }

        int size = VarInt.getByteSize(heightmapsCount);
        for (int i = 0; i < heightmapsData.length; i++) {
            long[] data = heightmapsData[i];
            if (data != null) {
                size += VarInt.getByteSize(SENDABLE_HEIGHTMAP_TYPE_IDS[i]);
                size += VarInt.getByteSize(data.length) + (data.length * Long.BYTES);
            }
        }
        return size;
    }

    private static long[][] extractHeightmapsData(LevelChunk chunk) {
        long[][] heightmapsData = new long[SENDABLE_HEIGHTMAP_TYPES.length][];
        for (int i = 0; i < SENDABLE_HEIGHTMAP_TYPES.length; i++) {
            Heightmap.Types type = SENDABLE_HEIGHTMAP_TYPES[i];
            if (chunk.hasPrimedHeightmap(type)) {
                heightmapsData[i] = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
            }
        }
        return heightmapsData;
    }
}

