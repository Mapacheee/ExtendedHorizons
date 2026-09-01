package me.mapacheee.extendedhorizons.fakechunks.disk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RegionFileReaderTest {

    private static final int SECTOR_SIZE = 4096;
    private static final int CHUNK_SECTOR = 2;
    private static final int COMPRESSION_NONE = 3;

    @TempDir
    Path worldFolder;

    @AfterEach
    void closeRegionFiles() {
        RegionFileReader.clearCache();
    }

    @Test
    void readsInternalUncompressedChunk() throws IOException {
        byte[] payload = {10, 0, 0};
        writeRegionStub(payload.length + 1, COMPRESSION_NONE, payload);

        assertArrayEquals(payload, RegionFileReader.readChunkBytes(worldFolder.toFile(), 0, 0));
    }

    @Test
    void readsExternalUncompressedChunk() throws IOException {
        byte[] payload = {10, 0, 0};
        writeRegionStub(1, 0x80 | COMPRESSION_NONE, new byte[0]);
        Files.write(worldFolder.resolve("region").resolve("c.0.0.mcc"), payload);

        assertArrayEquals(payload, RegionFileReader.readChunkBytes(worldFolder.toFile(), 0, 0));
    }

    private void writeRegionStub(int dataLength, int compression, byte[] payload) throws IOException {
        Path regionFolder = Files.createDirectories(worldFolder.resolve("region"));
        ByteBuffer file = ByteBuffer.allocate(CHUNK_SECTOR * SECTOR_SIZE + Integer.BYTES + 1 + payload.length);
        file.putInt((CHUNK_SECTOR << 8) | 1);
        file.position(CHUNK_SECTOR * SECTOR_SIZE);
        file.putInt(dataLength);
        file.put((byte) compression);
        file.put(payload);
        Files.write(regionFolder.resolve("r.0.0.mca"), file.array());
    }
}
