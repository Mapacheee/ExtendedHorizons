package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class FastChunkDataWriter {

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

        ByteBuf sectionBuffer = PooledByteBufAllocator.DEFAULT.buffer(1024, 256 * 1024);
        try {
            FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionBuffer);
            for (LevelChunkSection section : chunk.getSections()) {
                section.write(sectionBuf, null, 0);
            }
            int sectionBytes = sectionBuffer.readableBytes();
            VarIntUtil.writeVarInt(out, sectionBytes);
            out.writeBytes(sectionBuffer, sectionBuffer.readerIndex(), sectionBytes);
        } finally {
            sectionBuffer.release();
        }

        VarIntUtil.writeVarInt(out, 0);
    }

    private static void writeHeightmaps(FriendlyByteBuf out, LevelChunk chunk) {
        HeightmapWriter.writeHeightmaps(out, chunk);
    }
}
