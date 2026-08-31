package me.mapacheee.extendedhorizons.fakechunks.disk;

import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import me.mapacheee.lib.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

/**
 * Reads raw compressed chunk data directly from Minecraft region (.mca) files
 * using cached, concurrent and thread-safe Java NIO FileChannels. This bypasses
 * Paper's chunk loading pipeline entirely, avoiding expensive heightmap
 * recalculation and chunk upgrade logic.
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
    private static final int HEADER_SECTORS = 2;
    private static final int HEADER_SIZE = SECTOR_SIZE * HEADER_SECTORS;
    private static final int LOCATION_ENTRY_SIZE = 4;

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;
    private static final int COMPRESSION_LZ4 = 4;

    private static final int DECOMPRESSION_BUFFER_SIZE = 8192;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int CHANNEL_CACHE_MAX_SIZE = 128;
    private static final int CHANNEL_CACHE_EXPIRY_MINUTES = 10;
    private static final int REGION_COORD_SHIFT = 5;
    private static final int REGION_LOCAL_MASK = 31;
    private static final int REGION_DIMENSION = 32;
    private static final int SECTOR_OFFSET_SHIFT = 8;
    private static final int SECTOR_COUNT_MASK = 0xFF;
    private static final int MIN_SECTOR_OFFSET = 2;
    private static final int EXTERNAL_STREAM_FLAG = 0x80;
    private static final int COMPRESSION_TYPE_MASK = 0x7F;
    private static final int MAX_EXTERNAL_CHUNK_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_CHUNK_BYTES = 64 * 1024 * 1024;

    /**
     * Reusable direct ByteBuffers for the fixed-size reads (location entry + chunk header).
     * Eliminates 2 heap allocations per chunk read.
     */
    private static final ThreadLocal<ByteBuffer> LOCATION_BUF =
        ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(LOCATION_ENTRY_SIZE));
    private static final ThreadLocal<ByteBuffer> CHUNK_HEADER_BUF =
        ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(CHUNK_HEADER_BYTES));
    private static final ThreadLocal<byte[]> DECOMPRESSION_BUF =
        ThreadLocal.withInitial(() -> new byte[DECOMPRESSION_BUFFER_SIZE]);

    /** Cached LZ4 decompressor — avoid calling LZ4Factory.fastestInstance() on every decompression. */
    private static final LZ4SafeDecompressor LZ4_DECOMPRESSOR =
        LZ4Factory.fastestInstance().safeDecompressor();

    /**
     * Cache of open FileChannels to avoid heavy OS handle churn.
     * Thread-safe and auto-evicts inactive channels after 10 minutes.
     */
    private static final Cache<String, FileChannel> CHANNEL_CACHE = Caffeine.newBuilder()
        .maximumSize(CHANNEL_CACHE_MAX_SIZE)
        .expireAfterAccess(Duration.ofMinutes(CHANNEL_CACHE_EXPIRY_MINUTES))
        .removalListener((String path, FileChannel channel, RemovalCause cause) -> {
          try {
            channel.close();
          } catch (IOException e) {
            LOGGER.debug("Failed to close cached FileChannel for {}: {}", path, e.getMessage());
          }
        })
        .build();

    private RegionFileReader() {}

    /**
     * Reads the raw decompressed bytes of a chunk from the region file using a cached FileChannel.
     * This method is fully thread-safe and non-blocking under concurrent reads on the same region file.
     *
     * @param worldFolder the root folder of the world (e.g. server/world/)
     * @param chunkX      the chunk X coordinate
     * @param chunkZ      the chunk Z coordinate
     * @return the decompressed NBT bytes, or null if the chunk does not exist or cannot be read.
     */
    public static byte[] readChunkBytes(File worldFolder, int chunkX, int chunkZ) {
        int regionX = chunkX >> REGION_COORD_SHIFT;
        int regionZ = chunkZ >> REGION_COORD_SHIFT;

        File regionFolder = new File(worldFolder, "region");
        File regionFile = new File(regionFolder, "r." + regionX + "." + regionZ + ".mca");

        if (!regionFile.exists()) {
            LOGGER.debug("Region file does not exist: {}", regionFile.getAbsolutePath());
            return null;
        }

        int localX = chunkX & REGION_LOCAL_MASK;
        int localZ = chunkZ & REGION_LOCAL_MASK;
        int locationIndex = (localX + localZ * REGION_DIMENSION) * LOCATION_ENTRY_SIZE;

        FileChannel channel;
        try {
            channel = CHANNEL_CACHE.get(regionFile.getAbsolutePath(), path -> {
                try {
                    return FileChannel.open(regionFile.toPath(), StandardOpenOption.READ);
                } catch (IOException e) {
                    LOGGER.error("Failed to open FileChannel for region file {}: {}", path, e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            channel = null;
        }

        if (channel == null) {
            return null;
        }

        try {
            long fileLength = channel.size();
            if (fileLength < HEADER_SIZE) {
                LOGGER.warn("Region file is too small ({} bytes), expected at least {} bytes: {}",
                    fileLength, HEADER_SIZE, regionFile.getAbsolutePath());
                return null;
            }

            ByteBuffer buf = LOCATION_BUF.get();
            buf.clear();
            if (!readFully(channel, buf, locationIndex)) {
                return null;
            }
            buf.flip();
            int locationValue = buf.getInt();
            if (locationValue == 0) {
                return null;
            }

            int sectorOffset = (locationValue >> SECTOR_OFFSET_SHIFT) & 0xFFFFFF;
            int sectorCount = locationValue & SECTOR_COUNT_MASK;

            if (sectorOffset < MIN_SECTOR_OFFSET || sectorCount <= 0) {
                LOGGER.warn("Invalid sector offset {} for chunk [{}, {}] in {}",
                    sectorOffset, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            long dataStart = (long) sectorOffset * SECTOR_SIZE;
            if (dataStart >= fileLength) {
                LOGGER.warn("Sector offset {} points beyond file end ({} bytes) for chunk [{}, {}] in {}",
                    sectorOffset, fileLength, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            ByteBuffer headerBuf = CHUNK_HEADER_BUF.get();
            headerBuf.clear();
            if (!readFully(channel, headerBuf, dataStart)) {
                return null;
            }
            headerBuf.flip();
            int dataLength = headerBuf.getInt();
            int compressionByte = headerBuf.get() & 0xFF;
            int compressionType = compressionByte & COMPRESSION_TYPE_MASK;
            boolean externalStream = (compressionByte & EXTERNAL_STREAM_FLAG) != 0;

            if (dataLength <= 0) {
                LOGGER.warn("Invalid data length {} for chunk [{}, {}] in {}",
                    dataLength, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            if (externalStream) {
                if (dataLength != 1) {
                    LOGGER.warn("Invalid external chunk stub length {} for chunk [{}, {}] in {}",
                        dataLength, chunkX, chunkZ, regionFile.getName());
                    return null;
                }
                File externalFile = new File(regionFolder, "c." + chunkX + "." + chunkZ + ".mcc");
                if (!externalFile.isFile()) {
                    LOGGER.warn("External chunk file does not exist: {}", externalFile.getAbsolutePath());
                    return null;
                }
                long externalSize = Files.size(externalFile.toPath());
                if (externalSize <= 0 || externalSize > MAX_EXTERNAL_CHUNK_BYTES) {
                    LOGGER.warn("Invalid external chunk size {} for chunk [{}, {}]", externalSize, chunkX, chunkZ);
                    return null;
                }
                byte[] externalData = Files.readAllBytes(externalFile.toPath());
                return decompress(externalData, compressionType, sectorCount, chunkX, chunkZ, externalFile.getName());
            }

            int allocatedBytes = sectorCount * SECTOR_SIZE - Integer.BYTES;
            if (dataLength > allocatedBytes) {
                LOGGER.warn("Chunk data length {} exceeds allocated space {} for chunk [{}, {}] in {}",
                    dataLength, allocatedBytes, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            int compressedLength = dataLength - 1;
            if (compressedLength <= 0) {
                LOGGER.warn("Compressed payload length is {} for chunk [{}, {}] in {}",
                    compressedLength, chunkX, chunkZ, regionFile.getName());
                return null;
            }

            ByteBuffer dataBuf = ByteBuffer.allocate(compressedLength);
            if (!readFully(channel, dataBuf, dataStart + CHUNK_HEADER_BYTES)) {
                LOGGER.warn("Compressed payload read truncated for chunk [{}, {}] in {}",
                    chunkX, chunkZ, regionFile.getName());
                return null;
            }

            byte[] compressedData = dataBuf.array();
            return decompress(compressedData, compressionType, sectorCount, chunkX, chunkZ, regionFile.getName());
        } catch (java.nio.channels.ClosedChannelException e) {
            CHANNEL_CACHE.invalidate(regionFile.getAbsolutePath());
            LOGGER.debug("FileChannel closed for {}, invalidating cache", regionFile.getName());
            return null;
        } catch (IOException | RuntimeException e) {
            CHANNEL_CACHE.invalidate(regionFile.getAbsolutePath());
            LOGGER.error("Failed to read chunk [{}, {}] from FileChannel: {}", chunkX, chunkZ, e.getMessage());
            return null;
        }
    }

    /**
     * Clears all cached FileChannels and closes them gracefully.
     */
    public static void clearCache() {
        CHANNEL_CACHE.invalidateAll();
    }

    /**
     * Decompresses chunk data according to the compression type.
     */
    private static byte[] decompress(byte[] data, int compressionType, int sectorCount, int chunkX, int chunkZ, String fileName) {
        try {
            return switch (compressionType) {
                case COMPRESSION_GZIP -> decompressGzip(data, sectorCount);
                case COMPRESSION_ZLIB -> decompressZlib(data, sectorCount);
                case COMPRESSION_NONE -> data;
                case COMPRESSION_LZ4 -> decompressLz4(data, sectorCount);
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

    private static byte[] decompressGzip(byte[] data, int sectorCount) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAllBytes(gis, sectorCount);
        }
    }

    private static byte[] decompressZlib(byte[] data, int sectorCount) throws IOException {
        Inflater inflater = new Inflater();
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data), inflater)) {
            return readAllBytes(iis, sectorCount);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decompressLz4(byte[] data, int sectorCount) throws IOException {
        try (LZ4BlockInputStream lzis = LZ4BlockInputStream.newBuilder().build(new ByteArrayInputStream(data))) {
            return readAllBytes(lzis, sectorCount);
        } catch (Exception e) {
            throw new IOException("LZ4 decompression failed", e);
        }
    }

    private static byte[] readAllBytes(InputStream is, int sectorCount) throws IOException {
        int estimatedSize = Math.max(DECOMPRESSION_BUFFER_SIZE, sectorCount * SECTOR_SIZE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(estimatedSize);
        byte[] buffer = DECOMPRESSION_BUF.get();
        int len;
        while ((len = is.read(buffer)) != -1) {
            if (bos.size() > MAX_DECOMPRESSED_CHUNK_BYTES - len) {
                throw new IOException("Decompressed chunk exceeds " + MAX_DECOMPRESSED_CHUNK_BYTES + " bytes");
            }
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long currentPosition = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, currentPosition);
            if (read <= 0) {
                return false;
            }
            currentPosition += read;
        }
        return true;
    }
}
