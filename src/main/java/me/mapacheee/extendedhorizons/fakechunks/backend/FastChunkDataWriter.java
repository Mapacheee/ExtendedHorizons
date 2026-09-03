package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class FastChunkDataWriter {

    private static final int SECTION_BUFFER_INITIAL = 1024;
    private static final int SECTION_BUFFER_MAX = 256 * 1024;

    private FastChunkDataWriter() {}

    static boolean canUseFastPath(LevelChunk chunk) {
        return chunk != null;
    }

    static int estimateChunkDataSize(LevelChunk chunk) {
        if (chunk == null) {
            return 0;
        }
        int size = 0;
        size += HeightmapWriter.estimateHeightmapsSize(chunk);

        int sectionsSize = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            sectionsSize += Math.max(0, section.getSerializedSize());
        }
        size += VarInt.getByteSize(sectionsSize) + sectionsSize;

        size += VarInt.getByteSize(0);
        return size;
    }

    static void writeChunkData(FriendlyByteBuf out, LevelChunk chunk) {
        writeHeightmaps(out, chunk);

        writeSections(out, chunk.getSections());

        VarIntUtil.writeVarInt(out, 0);
    }

    static void writeSections(FriendlyByteBuf out, LevelChunkSection[] sections) {
        writeSections(out, sections.length, (sectionOut, index) ->
            sections[index].write(sectionOut, null, 0));
    }

    static void writeSections(FriendlyByteBuf out, int sectionCount, SectionWriter sectionWriter) {
        ByteBuf sectionBuffer = PooledByteBufAllocator.DEFAULT.buffer(SECTION_BUFFER_INITIAL, SECTION_BUFFER_MAX);
        try {
            FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionBuffer);
            for (int index = 0; index < sectionCount; index++) {
                sectionWriter.write(sectionBuf, index);
            }
            int sectionBytes = sectionBuffer.readableBytes();
            VarIntUtil.writeVarInt(out, sectionBytes);
            out.writeBytes(sectionBuffer, sectionBuffer.readerIndex(), sectionBytes);
        } finally {
            sectionBuffer.release();
        }
    }

    private static void writeHeightmaps(FriendlyByteBuf out, LevelChunk chunk) {
        HeightmapWriter.writeHeightmaps(out, chunk);
    }

    @FunctionalInterface
    interface SectionWriter {
        void write(FriendlyByteBuf out, int index);
    }
}
