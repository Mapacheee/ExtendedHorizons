package me.mapacheee.extendedhorizons.fakechunks.disk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

/**
 * Reads raw compressed chunk data directly from Minecraft region (.mca) files
 * using pure Java NIO. This bypasses Paper's chunk loading pipeline entirely,
 * avoiding expensive heightmap recalculation and chunk upgrade logic.
 *
 * Region file format (.mca)
 * Header (8192 bytes):
 *   Locations (4096 bytes): 1024 entries × 4 bytes each
 *     [3 bytes: sector offset] [1 byte: sector count]
 *   Timestamps (4096 bytes): ignored
 *
 * Chunk data sectors (4096-byte aligned):
 *   [4 bytes: data length] [1 byte: compression type] [N bytes: compressed data]
 *     Compression types: 1=GZip, 2=Zlib, 3=None, 4=LZ4
 */
public final class RegionFileReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionFileReader.class);

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SIZE = SECTOR_SIZE * 2;
    private static final int LOCATION_ENTRIES = 1024;
    private static final int LOCATION_ENTRY_SIZE = 4;

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;
    private static final int COMPRESSION_LZ4 = 4;

    private static final int DECOMPRESSION_BUFFER_SIZE = 8192;

    private RegionFileReader() {}

    /**
     * Reads the raw decompressed bytes of a chunk from the region file.
     *
     * @param worldFolder the root folder of the world (e.g. server/world/)
     * @param chunkX      the chunk X coordinate (chunk coords, not block coords)
     * @param chunkZ      the chunk Z coordinate (chunk coords, not block coords)
     * @return the decompressed NBT bytes, or null if the chunk does not exist in the file
     */
    public static byte[] readChunkBytes(File worldFolder, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;

        File regionFolder = new File(worldFolder, "region");
        File regionFile = new File(regionFolder, "r." + regionX + "." + regionZ + ".mca");

        if (!regionFile.exists()) {
            LOGGER.debug("Region file does not exist: {}", regionFile.getAbsolutePath());
            return null;
        }

        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        int locationIndex = (localX + localZ * 32) * LOCATION_ENTRY_SIZE;

        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            long fileLength = raf.length();
            if (fileLength < HEADER_SIZE) {
                LOGGER.warn("Region file is too small ({} bytes), expected at least {} bytes: {}",
                    fileLength, HEADER_SIZE, regionFile.getAbsolutePath());
                return null;
            }

            raf.seek(locationIndex);
            int locationValue = raf.readInt();
            if (locationValue == 0) {
                return null;
            }

            int sectorOffset = (locationValue >> 8) & 0xFFFFFF;
            int sectorCount = locationValue & 0xFF;

            if (sectorOffset < 2) {
                LOGGER.warn("Invalid sector offset {} for chunk [{}, {}] in {}",
                    sectorOffset, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            long dataStart = (long) sectorOffset * SECTOR_SIZE;
            long maxDataEnd = dataStart + (long) sectorCount * SECTOR_SIZE;
            if (dataStart >= fileLength) {
                LOGGER.warn("Sector offset {} points beyond file end ({} bytes) for chunk [{}, {}] in {}",
                    sectorOffset, fileLength, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            raf.seek(dataStart);
            int dataLength = raf.readInt();
            int compressionType = raf.readUnsignedByte();

            if (dataLength <= 0) {
                LOGGER.warn("Invalid data length {} for chunk [{}, {}] in {}",
                    dataLength, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            int compressedLength = dataLength - 1;
            if (compressedLength <= 0) {
                LOGGER.warn("Compressed payload length is {} for chunk [{}, {}] in {}",
                    compressedLength, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            long available = fileLength - raf.getFilePointer();
            if (compressedLength > available) {
                LOGGER.warn("Compressed length {} exceeds available bytes {} for chunk [{}, {}] in {}",
                    compressedLength, available, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            byte[] compressedData = new byte[compressedLength];
            raf.readFully(compressedData);

            return decompress(compressedData, compressionType, chunkX, chunkZ, regionFile.getName());
        } catch (IOException e) {
            LOGGER.error("Failed to read chunk [{}, {}] from {}: {}",
                chunkX, chunkZ, regionFile.getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Decompresses chunk data according to the compression type.
     */
    private static byte[] decompress(byte[] data, int compressionType, int chunkX, int chunkZ, String fileName) {
        try {
            return switch (compressionType) {
                case COMPRESSION_GZIP -> decompressGzip(data);
                case COMPRESSION_ZLIB -> decompressZlib(data);
                case COMPRESSION_NONE -> data;
                case COMPRESSION_LZ4 -> decompressLz4(data);
                default -> {
                    LOGGER.warn("Unknown compression type {} for chunk [{}, {}] in {}",
                        compressionType, chunkX, chunkZ, fileName);
                    yield null;
                }
            };
        } catch (IOException e) {
            LOGGER.error("Failed to decompress chunk [{}, {}] from {} (type={}): {}",
                chunkX, chunkZ, fileName, compressionType, e.getMessage(), e);
            return null;
        }
    }

    private static byte[] decompressGzip(byte[] data) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAllBytes(gis);
        }
    }

    private static byte[] decompressZlib(byte[] data) throws IOException {
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data), new Inflater())) {
            return readAllBytes(iis);
        }
    }

    private static byte[] decompressLz4(byte[] data) throws IOException {
        try {
            LZ4Factory factory = LZ4Factory.fastestInstance();
            LZ4SafeDecompressor decompressor = factory.safeDecompressor();
            int maxOutput = data.length * 8;
            byte[] output = decompressor.decompress(data, maxOutput);
            return output;
        } catch (Exception e) {
            throw new IOException("LZ4 decompression failed", e);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(DECOMPRESSION_BUFFER_SIZE);
        byte[] buffer = new byte[DECOMPRESSION_BUFFER_SIZE];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }
}
