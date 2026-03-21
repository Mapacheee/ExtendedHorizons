package me.mapacheee.extendedhorizons.chunk.pipeline.impl;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.pipeline.ChunkPackets;
import me.mapacheee.extendedhorizons.chunk.pipeline.ChunkPipelineService;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.network.protocol.Packet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;

@Service
public class ChunkPipelineServiceImpl implements ChunkPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(ChunkPipelineServiceImpl.class);
    private final ExecutorService pipelineExecutor;

    @Inject
    public ChunkPipelineServiceImpl() {
        this.pipelineExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "EH-ChunkPipeline");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        );
    }

    @OnDisable
    public void onDisable() {
        pipelineExecutor.shutdown();
        try {
            if (!pipelineExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                pipelineExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pipelineExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            pipelineExecutor.shutdownNow();
        }
    }

    @Override
    public CompletableFuture<ChunkPackets> createPacket(World world, int chunkX, int chunkZ, CompoundTag chunkData) {
        if (pipelineExecutor.isShutdown()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> parseSections(chunkData), pipelineExecutor)
                .thenCompose(parsed -> {
                    if (parsed == null || parsed.isEmpty()) return CompletableFuture.completedFuture(null);
                    CompletableFuture<ChunkPackets> future = new CompletableFuture<>();
                    runAtChunk(world, chunkX, chunkZ, () -> {
                        try {
                            ServerLevel serverLevel = ((CraftWorld) world).getHandle();
                            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                            LevelChunk levelChunk = new LevelChunk(serverLevel, chunkPos);

                            LevelChunkSection[] sections = levelChunk.getSections();
                            Registry<Biome> biomeRegistry = serverLevel.registryAccess().lookupOrThrow(Registries.BIOME);
                            Holder<Biome> biomeHolder = biomeRegistry.get(Biomes.PLAINS).orElseThrow();

                            for (SectionData sb : parsed) {
                                int index = levelChunk.getSectionIndexFromSectionY(sb.sectionY);
                                if (index < 0 || index >= sections.length) continue;

                                if (sb.blocks != null) {
                                    LevelChunkSection section = sections[index];
                                    if (section == null) {
                                        PalettedContainer<BlockState> blockStates = createBlockStatesContainer();
                                        PalettedContainer<Holder<Biome>> biomes = createBiomesContainer(biomeRegistry, biomeHolder);
                                        if (blockStates == null || biomes == null) continue;
                                        section = new LevelChunkSection(blockStates, biomes);
                                        sections[index] = section;
                                    }
                                    applyBlocks(section, sb.blocks);
                                }
                            }

                            LevelLightEngine lightEngine = serverLevel.getLightEngine();
                            BitSet[] lightMasks = getLightMasks(levelChunk);
                            ClientboundLevelChunkWithLightPacket chunkPacket = new ClientboundLevelChunkWithLightPacket(
                                    levelChunk,
                                    lightEngine,
                                    lightMasks[0],
                                    lightMasks[1],
                                    false);

                            Packet<?> lightPacket = buildLightPacketFromNbt(chunkPos, levelChunk, parsed);
                            future.complete(new ChunkPackets(chunkPacket, lightPacket));
                        } catch (Throwable t) {
                            logger.error("Failed to create packet for chunk {},{}", chunkX, chunkZ, t);
                            future.complete(null);
                        }
                    });
                    return future;
                });
    }

    private void runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (runnable == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        if (world == null) return;

        try {
            Location loc = new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
            Bukkit.getServer().getRegionScheduler().execute(plugin, loc, runnable);
        } catch (Throwable t) {
            try {
                Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
            } catch (Throwable ignored) {
            }
        }
    }

    private record SectionData(int sectionY, BlockState[] blocks, byte[] skyLight, byte[] blockLight) {}

    private List<SectionData> parseSections(CompoundTag chunkData) {
        try {
            if (chunkData == null) return null;
            if (!chunkData.contains("sections")) return null;

            ListTag sectionsTag = chunkData.getList("sections").orElse(new ListTag());
            if (sectionsTag.isEmpty()) return null;

            List<SectionData> result = new ArrayList<>();
            for (int i = 0; i < sectionsTag.size(); i++) {
                CompoundTag sectionTag = (CompoundTag) sectionsTag.get(i);
                int y = sectionTag.getByte("Y").orElse((byte) 0);
                byte[] skyLight = getLightArray(sectionTag, "SkyLight");
                byte[] blockLight = getLightArray(sectionTag, "BlockLight");
                BlockState[] blocks = decodeSectionBlocks(sectionTag);
                result.add(new SectionData(y, blocks, skyLight, blockLight));
            }

            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    private BlockState[] decodeSectionBlocks(CompoundTag sectionTag) {
        try {
            if (sectionTag == null) return null;
            if (!sectionTag.contains("block_states")) return null;
            CompoundTag blockStatesTag = sectionTag.getCompound("block_states").orElse(null);
            if (blockStatesTag == null) return null;

            ListTag paletteTag = blockStatesTag.getList("palette").orElse(null);
            if (paletteTag == null || paletteTag.isEmpty()) return null;

            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                Tag paletteEntry = paletteTag.get(i);
                palette[i] = BlockState.CODEC.parse(NbtOps.INSTANCE, paletteEntry)
                        .result()
                        .orElse(Blocks.AIR.defaultBlockState());
            }

            BlockState[] out = new BlockState[4096];
            if (palette.length == 1) {
                BlockState state = palette[0];
                for (int i = 0; i < 4096; i++) out[i] = state;
                return out;
            }

            long[] data = blockStatesTag.getLongArray("data").orElse(new long[0]);
            if (data.length == 0) return null;

            int bits = 32 - Integer.numberOfLeadingZeros(palette.length - 1);
            if (bits < 4) bits = 4;
            int mask = (1 << bits) - 1;

            for (int idx = 0; idx < 4096; idx++) {
                int bitIndex = idx * bits;
                int startLong = bitIndex >> 6;
                int startOffset = bitIndex & 63;
                long value = data[startLong] >>> startOffset;
                int paletteIndex;
                if (startOffset + bits <= 64) {
                    paletteIndex = (int) (value & mask);
                } else {
                    long next = data[startLong + 1];
                    paletteIndex = (int) ((value | (next << (64 - startOffset))) & mask);
                }
                out[idx] = paletteIndex >= 0 && paletteIndex < palette.length
                        ? palette[paletteIndex]
                        : Blocks.AIR.defaultBlockState();
            }

            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyBlocks(LevelChunkSection section, BlockState[] blocks) {
        if (section == null || blocks == null || blocks.length != 4096) return;
        for (int idx = 0; idx < 4096; idx++) {
            BlockState state = blocks[idx];
            if (state == null) state = Blocks.AIR.defaultBlockState();
            int x = idx & 15;
            int z = (idx >> 4) & 15;
            int y = (idx >> 8) & 15;
            section.setBlockState(x, y, z, state);
        }
    }

    @SuppressWarnings("unchecked")
    private PalettedContainer<BlockState> createBlockStatesContainer() {
        try {
            Object strategy = getPalettedStrategy("SECTION_STATES");
            for (Constructor<?> c : PalettedContainer.class.getDeclaredConstructors()) {
                c.setAccessible(true);
                if (c.getParameterCount() == 3) {
                    try {
                        if (strategy != null) {
                            return (PalettedContainer<BlockState>) c.newInstance(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), strategy);
                        }
                        return (PalettedContainer<BlockState>) c.newInstance(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), null);
                    } catch (Exception ignored) {}
                    try {
                        return (PalettedContainer<BlockState>) c.newInstance(Blocks.AIR.defaultBlockState(), null, Block.BLOCK_STATE_REGISTRY);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private PalettedContainer<Holder<Biome>> createBiomesContainer(Registry<Biome> biomeRegistry, Holder<Biome> biomeHolder) {
        if (biomeRegistry == null || biomeHolder == null) return null;
        try {
            Object strategy = getPalettedStrategy("SECTION_BIOMES");
            for (Constructor<?> c : PalettedContainer.class.getDeclaredConstructors()) {
                c.setAccessible(true);
                if (c.getParameterCount() == 3) {
                    try {
                        if (strategy != null) {
                            return (PalettedContainer<Holder<Biome>>) c.newInstance(biomeRegistry.asHolderIdMap(), biomeHolder, strategy);
                        }
                        return (PalettedContainer<Holder<Biome>>) c.newInstance(biomeRegistry.asHolderIdMap(), biomeHolder, null);
                    } catch (Exception ignored) {}
                    try {
                        return (PalettedContainer<Holder<Biome>>) c.newInstance(biomeHolder, null, biomeRegistry.asHolderIdMap());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object getPalettedStrategy(String fieldName) {
        try {
            Class<?> strategyClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Strategy");
            Field f = strategyClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BitSet[] getLightMasks(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int sectionCount = sections.length;
        BitSet skyLight = new BitSet(sectionCount + 2);
        BitSet blockLight = new BitSet(sectionCount + 2);

        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) {
                skyLight.set(i + 1);
                blockLight.set(i + 1);
            }
        }
        return new BitSet[] { skyLight, blockLight };
    }

    private Packet<?> buildLightPacketFromNbt(ChunkPos chunkPos, LevelChunk levelChunk, List<SectionData> parsed) {
        try {
            if (levelChunk == null || parsed == null || parsed.isEmpty()) return null;

            int sectionCount = levelChunk.getSections().length;
            BitSet skyMask = new BitSet(sectionCount + 2);
            BitSet blockMask = new BitSet(sectionCount + 2);
            BitSet emptySkyMask = new BitSet(sectionCount + 2);
            BitSet emptyBlockMask = new BitSet(sectionCount + 2);

            List<byte[]> skyUpdates = new ArrayList<>();
            List<byte[]> blockUpdates = new ArrayList<>();

            java.util.Map<Integer, byte[]> skyByBit = new java.util.HashMap<>();
            java.util.Map<Integer, byte[]> blockByBit = new java.util.HashMap<>();

            for (SectionData sd : parsed) {
                int index = levelChunk.getSectionIndexFromSectionY(sd.sectionY);
                if (index < 0 || index >= sectionCount) continue;
                int bit = index + 1;

                if (sd.skyLight != null && sd.skyLight.length == 2048) {
                    skyMask.set(bit);
                    skyByBit.put(bit, Arrays.copyOf(sd.skyLight, sd.skyLight.length));
                }

                if (sd.blockLight != null && sd.blockLight.length == 2048) {
                    blockMask.set(bit);
                    blockByBit.put(bit, Arrays.copyOf(sd.blockLight, sd.blockLight.length));
                }
            }

            if (skyMask.isEmpty() && blockMask.isEmpty()) return null;

            for (int bit = skyMask.nextSetBit(0); bit >= 0; bit = skyMask.nextSetBit(bit + 1)) {
                byte[] arr = skyByBit.get(bit);
                if (arr != null) skyUpdates.add(arr);
            }
            for (int bit = blockMask.nextSetBit(0); bit >= 0; bit = blockMask.nextSetBit(bit + 1)) {
                byte[] arr = blockByBit.get(bit);
                if (arr != null) blockUpdates.add(arr);
            }

            return createLightPacket(chunkPos, skyMask, blockMask, emptySkyMask, emptyBlockMask, skyUpdates, blockUpdates);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Packet<?> createLightPacket(ChunkPos chunkPos, BitSet skyMask, BitSet blockMask, BitSet emptySkyMask, BitSet emptyBlockMask, List<byte[]> skyUpdates, List<byte[]> blockUpdates) {
        try {
            Class<?> dataClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLightUpdatePacket");

            Object data = null;
            for (var ctor : dataClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 6
                        && params[0] == BitSet.class
                        && params[1] == BitSet.class
                        && params[2] == BitSet.class
                        && params[3] == BitSet.class
                        && List.class.isAssignableFrom(params[4])
                        && List.class.isAssignableFrom(params[5])) {
                    data = ctor.newInstance(skyMask, blockMask, emptySkyMask, emptyBlockMask, skyUpdates, blockUpdates);
                    break;
                }
                if (params.length == 7
                        && params[0] == BitSet.class
                        && params[1] == BitSet.class
                        && params[2] == BitSet.class
                        && params[3] == BitSet.class
                        && List.class.isAssignableFrom(params[4])
                        && List.class.isAssignableFrom(params[5])
                        && params[6] == boolean.class) {
                    data = ctor.newInstance(skyMask, blockMask, emptySkyMask, emptyBlockMask, skyUpdates, blockUpdates, true);
                    break;
                }
            }
            if (data == null) return null;

            Object packet = null;
            for (var ctor : packetClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 2 && params[0] == ChunkPos.class && params[1].isAssignableFrom(dataClass)) {
                    packet = ctor.newInstance(chunkPos, data);
                    break;
                }
                if (params.length == 3 && params[0] == int.class && params[1] == int.class && params[2].isAssignableFrom(dataClass)) {
                    packet = ctor.newInstance(chunkPos.x, chunkPos.z, data);
                    break;
                }
                if (params.length == 3 && params[0] == ChunkPos.class && params[1].isAssignableFrom(dataClass) && params[2] == boolean.class) {
                    packet = ctor.newInstance(chunkPos, data, true);
                    break;
                }
                if (params.length == 4 && params[0] == int.class && params[1] == int.class && params[2].isAssignableFrom(dataClass) && params[3] == boolean.class) {
                    packet = ctor.newInstance(chunkPos.x, chunkPos.z, data, true);
                    break;
                }
            }
            if (packet instanceof Packet<?> p) return p;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] getLightArray(CompoundTag sectionTag, String key) {
        try {
            if (sectionTag == null || key == null) return null;
            byte[] arr = sectionTag.getByteArray(key).orElse(null);
            if (arr == null || arr.length != 2048) return null;
            return arr;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
