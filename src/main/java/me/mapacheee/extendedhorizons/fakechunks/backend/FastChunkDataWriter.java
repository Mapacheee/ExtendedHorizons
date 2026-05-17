package me.mapacheee.extendedhorizons.fakechunks.backend;

import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import io.netty.buffer.Unpooled;

final class FastChunkDataWriter {

    private static final int PROBE_BUFFER_PADDING = 64;
    private static volatile Boolean NEEDS_SIZE_CORRECTION;

    private FastChunkDataWriter() {}

    private static boolean needsSizeCorrection(LevelChunkSection section) {
        Boolean cached = NEEDS_SIZE_CORRECTION;
        if (cached != null) {
            return cached;
        }
        synchronized (FastChunkDataWriter.class) {
            cached = NEEDS_SIZE_CORRECTION;
            if (cached != null) {
                return cached;
            }
            cached = probeWithSection(section);
            NEEDS_SIZE_CORRECTION = cached;
            return cached;
        }
    }

    private static boolean probeWithSection(LevelChunkSection section) {
        try {
            int reported = section.getSerializedSize();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(reported + PROBE_BUFFER_PADDING));
            section.write(buf, null, 0);
            int actual = buf.writerIndex();
            buf.release();
            return reported != actual;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean canUseFastPath(LevelChunk chunk) {
        return chunk != null;
    }

    static int estimateChunkDataSize(LevelChunk chunk) {
        if (chunk == null) {
            return 0;
        }
        int size = 0;
        size += HeightmapWriter.estimateHeightmapsSize(chunk);

        int sectionsSize = computeSectionsSize(chunk);
        size += varIntSize(sectionsSize) + sectionsSize;

        size += varIntSize(0);
        return size;
    }

    static void writeChunkData(FriendlyByteBuf out, LevelChunk chunk) {
        writeHeightmaps(out, chunk);

        int serializedSize = computeSectionsSize(chunk);
        VarIntUtil.writeVarInt(out, serializedSize);
        int expectedWriterIndex = out.writerIndex() + serializedSize;

        for (LevelChunkSection section : chunk.getSections()) {
            section.write(out, null, 0);
        }

        if (out.writerIndex() != expectedWriterIndex) {
            throw new IllegalStateException("Expected writer index to be at "
                + expectedWriterIndex + ", got " + out.writerIndex());
        }

        VarIntUtil.writeVarInt(out, 0);
    }

    private static int computeSectionsSize(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        if (sections.length == 0) {
            return 0;
        }

        boolean needsCorrection = needsSizeCorrection(sections[0]);
        int sectionsSize = 0;

        for (LevelChunkSection section : sections) {
            int baseSize = section.getSerializedSize();
            if (needsCorrection) {
                baseSize -= VarInt.getByteSize(section.getStates().data.storage().getRaw().length)
                    + VarInt.getByteSize(((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length);
            }
            sectionsSize += Math.max(0, baseSize);
        }
        return sectionsSize;
    }

    private static void writeHeightmaps(FriendlyByteBuf out, LevelChunk chunk) {
        HeightmapWriter.writeHeightmaps(out, chunk);
    }

    private static int varIntSize(int value) {
        return VarInt.getByteSize(value);
    }
}
