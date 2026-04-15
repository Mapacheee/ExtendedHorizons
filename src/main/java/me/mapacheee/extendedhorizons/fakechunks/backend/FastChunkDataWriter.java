package me.mapacheee.extendedhorizons.fakechunks.backend;

import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

final class FastChunkDataWriter {

    private FastChunkDataWriter() {}

    static boolean canUseFastPath(LevelChunk chunk) {
        return chunk != null && chunk.getBlockEntities().isEmpty();
    }

    static int estimateChunkDataSize(LevelChunk chunk) {
        if (chunk == null) {
            return 0;
        }
        int size = 0;
        size += varIntSize(0);

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
        VarIntUtil.writeVarInt(out, 0);
    }

    private static int varIntSize(int value) {
        return VarInt.getByteSize(value);
    }
}

