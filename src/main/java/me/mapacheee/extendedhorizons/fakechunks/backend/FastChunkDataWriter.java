package me.mapacheee.extendedhorizons.fakechunks.backend;

import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

final class FastChunkDataWriter {

    private static final Heightmap.Types[] SENDABLE_HEIGHTMAP_TYPES = Arrays.stream(Heightmap.Types.values())
        .filter(Heightmap.Types::sendToClient)
        .toArray(Heightmap.Types[]::new);
    private static final int[] SENDABLE_HEIGHTMAP_TYPE_IDS = Arrays.stream(SENDABLE_HEIGHTMAP_TYPES)
        .mapToInt(Enum::ordinal)
        .toArray();

    private FastChunkDataWriter() {}

    static boolean canUseFastPath(LevelChunk chunk) {
        return chunk != null && chunk.getBlockEntities().isEmpty();
    }

    static int estimateChunkDataSize(LevelChunk chunk) {
        if (chunk == null) {
            return 0;
        }
        int size = 0;
        int heightmapsCount = 0;
        for (Heightmap.Types type : SENDABLE_HEIGHTMAP_TYPES) {
            if (chunk.hasPrimedHeightmap(type)) {
                heightmapsCount++;
            }
        }
        size += varIntSize(heightmapsCount);
        for (int i = 0; i < SENDABLE_HEIGHTMAP_TYPES.length; i++) {
            Heightmap.Types type = SENDABLE_HEIGHTMAP_TYPES[i];
            if (!chunk.hasPrimedHeightmap(type)) {
                continue;
            }
            long[] data = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
            size += varIntSize(SENDABLE_HEIGHTMAP_TYPE_IDS[i]);
            size += varIntSize(data.length) + (data.length * Long.BYTES);
        }

        int sectionsSize = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            sectionsSize += section.getSerializedSize();
            if (SharedConstants.getProtocolVersion() == 770) {
                sectionsSize -= VarInt.getByteSize(section.getStates().data.storage().getRaw().length)
                    + VarInt.getByteSize(((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length);
            }
        }
        size += varIntSize(sectionsSize) + sectionsSize;

        size += 1;
        return size;
    }

    static void writeChunkData(FriendlyByteBuf out, LevelChunk chunk) {
        writeHeightmaps(out, chunk);

        LevelChunkSection[] sections = chunk.getSections();
        int serializedSize = 0;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            serializedSize += section.getSerializedSize();
            if (SharedConstants.getProtocolVersion() == 770) {
                serializedSize -= VarInt.getByteSize(section.getStates().data.storage().getRaw().length)
                    + VarInt.getByteSize(((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length);
            }
        }

        VarIntUtil.writeVarInt(out, serializedSize);
        int expectedWriterIndex = out.writerIndex() + serializedSize;
      for (LevelChunkSection section : sections) {
        section.write(out, null, 0);
      }
        if (out.writerIndex() != expectedWriterIndex) {
            throw new IllegalStateException("Fast serializer writer index mismatch");
        }

        VarIntUtil.writeVarInt(out, 0);
    }

    private static void writeHeightmaps(FriendlyByteBuf out, LevelChunk chunk) {
        int presentCount = 0;
        for (Heightmap.Types type : SENDABLE_HEIGHTMAP_TYPES) {
            if (!chunk.hasPrimedHeightmap(type)) {
                continue;
            }
            presentCount++;
        }

        VarIntUtil.writeVarInt(out, presentCount);
        for (int i = 0; i < SENDABLE_HEIGHTMAP_TYPES.length; i++) {
            Heightmap.Types type = SENDABLE_HEIGHTMAP_TYPES[i];
            if (!chunk.hasPrimedHeightmap(type)) {
                continue;
            }
            VarIntUtil.writeVarInt(out, SENDABLE_HEIGHTMAP_TYPE_IDS[i]);
            FriendlyByteBuf.writeLongArray(out, chunk.getOrCreateHeightmapUnprimed(type).getRawData());
        }
    }

    private static int varIntSize(int value) {
        return VarInt.getByteSize(value);
    }
}

