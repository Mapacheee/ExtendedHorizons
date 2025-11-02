package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.thewinterframework.service.annotation.Service;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/*
 *   Reads chunk NBT directly from region without loading server chunks
 *   And builds a PacketEvents Column approximation without server loads
*/
@Service
public class RegionColumnProviderService {

    private static final Logger logger = LoggerFactory.getLogger(RegionColumnProviderService.class);
    private static final boolean DEBUG = false;

    public CompletableFuture<Optional<CompoundTag>> fetchChunkNbt(World world, int x, int z) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        return level.getChunkSource().chunkMap.read(new ChunkPos(x, z))
                .exceptionally(ex -> {
                    if (DEBUG) {
                        logger.warn("Failed to read NBT for chunk {},{}: {}", x, z, ex.getMessage());
                    }
                    return Optional.empty();
                });
    }

    public Optional<Chunk> getMemoryChunkIfPresent(World world, int x, int z) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(((long) z << 32) | ((long) x & 0xFFFFFFFFL));
            if (holder == null) return Optional.empty();
            net.minecraft.world.level.chunk.LevelChunk nms = holder.getFullChunkNow();
            if (nms == null) return Optional.empty();
            return Optional.of(new CraftChunk(nms));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /*
     *   Build a Column from NBT using heightmap-driven approximation
     *   No server chunk loads; creates block palettes per section
    */
    public Optional<Column> buildColumnFromNbt(World world, int chunkX, int chunkZ, CompoundTag nbt) {
        try {
            if (nbt == null || nbt.isEmpty()) {
                if (DEBUG) {
                    logger.warn("Empty NBT for chunk {},{}", chunkX, chunkZ);
                }
                return Optional.empty();
            }

            Optional<String> statusOpt = nbt.getString("Status");
            if (statusOpt.isPresent()) {
                String status = statusOpt.get();
                if (!status.equals("minecraft:full") && !status.equals("full")) {
                    if (DEBUG) {
                        logger.info("Chunk " + chunkX + "," + chunkZ + " not fully generated (Status: " + status + "), skipping");
                    }
                    return Optional.empty();
                }
            }

            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int sectionsCount = (maxY - minY) / 16;
            BaseChunk[] sections = new BaseChunk[sectionsCount];

            int[] heights = extractHeights(nbt);
            if (heights == null || heights.length != 256) {
                heights = new int[256];
                int seaLevel = Math.max(minY, Math.min(63, maxY - 1));
                Arrays.fill(heights, seaLevel);
                if (DEBUG) {
                    logger.info("Using default heightmap for chunk " + chunkX + "," + chunkZ);
                }
            }

            final int plainsId = 1;
            final int airId = 0;
            final int stoneId = 1;
            final int dirtId = 10;
            final int grassId = 9;

            for (int sec = 0; sec < sectionsCount; sec++) {
                int sectionY = (minY / 16) + sec;

                @SuppressWarnings("deprecation")
                DataPalette blockPalette = DataPalette.createForChunk();
                @SuppressWarnings("deprecation")
                DataPalette biomePalette = DataPalette.createForBiome();

                for (int by = 0; by < 4; by++) {
                    for (int bx = 0; bx < 4; bx++) {
                        for (int bz = 0; bz < 4; bz++) {
                            biomePalette.set(bx, by, bz, plainsId);
                        }
                    }
                }

                for (int ly = 0; ly < 16; ly++) {
                    int globalY = (sectionY * 16) + ly;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int heightIndex = (z << 4) | x;
                            int terrainHeight = heights[heightIndex];

                            int blockId;
                            if (globalY > terrainHeight) {
                                blockId = airId;
                            } else if (globalY == terrainHeight) {
                                blockId = grassId;
                            } else if (globalY >= terrainHeight - 3) {
                                blockId = dirtId;
                            } else {
                                blockId = stoneId;
                            }

                            blockPalette.set(x, ly, z, blockId);
                        }
                    }
                }

                sections[sec] = new Chunk_v1_18(sectionY, blockPalette, biomePalette);
            }

            NBTCompound heightmaps = new NBTCompound();
            heightmaps.setTag("MOTION_BLOCKING", new NBTIntArray(heights));
            heightmaps.setTag("WORLD_SURFACE", new NBTIntArray(heights));

            TileEntity[] tileEntities = new TileEntity[0];
            int[] biomeDataInts = new int[0];

            Column column = new Column(
                    chunkX,
                    chunkZ,
                    true,
                    sections,
                    tileEntities,
                    heightmaps,
                    biomeDataInts
            );

            if (DEBUG) {
                logger.info("Successfully built fake chunk at " + chunkX + "," + chunkZ);
            }

            return Optional.of(column);
        } catch (Throwable t) {
            logger.warn("Error building column from NBT at {},{}: {}", chunkX, chunkZ, t.getMessage());
            if (DEBUG) {
                t.printStackTrace();
            }
            return Optional.empty();
        }
    }

    private static int[] extractHeights(CompoundTag nbt) {
        try {
            if (nbt == null) return null;

            Optional<CompoundTag> heightmapsOpt = nbt.getCompound("Heightmaps");
            if (heightmapsOpt.isEmpty()) {
                return null;
            }

            CompoundTag heightmapsTag = heightmapsOpt.get();

            Optional<long[]> dataOpt = heightmapsTag.getLongArray("MOTION_BLOCKING");
            if (dataOpt.isEmpty()) {
                dataOpt = heightmapsTag.getLongArray("WORLD_SURFACE");
                if (dataOpt.isEmpty()) {
                    return null;
                }
            }

            long[] data = dataOpt.get();
            return unpackHeightmap(data, 9, 256);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] unpackHeightmap(long[] data, int bitsPerEntry, int entries) {
        int[] out = new int[entries];
        if (data == null || data.length == 0) return out;

        int index = 0;
        int longIndex = 0;
        int bitIndex = 0;
        long current = data[0];
        long mask = (1L << bitsPerEntry) - 1L;

        while (index < entries && longIndex < data.length) {
            int remaining = 64 - bitIndex;
            if (remaining >= bitsPerEntry) {
                out[index++] = (int) ((current >>> bitIndex) & mask);
                bitIndex += bitsPerEntry;
                if (bitIndex == 64) {
                    bitIndex = 0;
                    longIndex++;
                    current = longIndex < data.length ? data[longIndex] : 0L;
                }
            } else {
                int bitsFromNext = bitsPerEntry - remaining;
                long part1 = (current >>> bitIndex) & ((1L << remaining) - 1L);
                longIndex++;
                long next = longIndex < data.length ? data[longIndex] : 0L;
                long part2 = next & ((1L << bitsFromNext) - 1L);
                out[index++] = (int) ((part2 << remaining) | part1);
                current = next;
                bitIndex = bitsFromNext;
            }
        }
        return out;
    }
}
