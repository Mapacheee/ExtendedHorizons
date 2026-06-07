package me.mapacheee.extendedhorizons.fakechunks.disk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.netty.PacketIdRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;


public final class DiskChunkSerializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkSerializer.class);

    private static final int PROTOCOL_MC296121 = 770;
    private static final int MIN_PACKET_SIZE = 4096;

    private static final boolean REMAP_CHAIN_TO_IRON_CHAIN =
            BuiltInRegistries.BLOCK.keySet().stream()
                    .noneMatch(key -> "minecraft:chain".equals(key.toString()));

    private DiskChunkSerializer() {}

    public static ByteBuf serialize(
            byte[] nbtBytes, ServerLevel level,
            int chunkX, int chunkZ, boolean hasSkyLight
    ) {
        if (nbtBytes == null || nbtBytes.length < 2) {
            return null;
        }
        if (!PacketIdRegistry.hasLevelChunkWithLightId()) {
            LOGGER.warn("Packet ID not resolved for chunk [{}, {}]", chunkX, chunkZ);
            return null;
        }

        CompoundTag rootTag;
        try {
            rootTag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtBytes)));
        } catch (Exception e) {
            LOGGER.warn("Failed to parse NBT for chunk [{}, {}]: {}", chunkX, chunkZ, e.getMessage());
            return null;
        }

      return serializeFromTag(rootTag, level, chunkX, chunkZ, hasSkyLight);
    }

    private static ByteBuf serializeFromTag(
            CompoundTag rootTag, ServerLevel level,
            int chunkX, int chunkZ, boolean hasSkyLight
    ) {
        int sectionsCount  = level.getSectionsCount();
        int minSectionY    = level.getMinSectionY();
        int lightSections  = sectionsCount + 2;
        int minLightSection = minSectionY - 1;

        PalettedContainerFactory factory = level.palettedContainerFactory();
        LevelChunkSection[] sections = new LevelChunkSection[sectionsCount];
        byte[][] blockLight = new byte[lightSections][];
        byte[][] skyLight   = hasSkyLight ? new byte[lightSections][] : null;

        var sectionTagsOpt = rootTag.getList(SerializableChunkData.SECTIONS_TAG);
        if (sectionTagsOpt.isEmpty()) {
            LOGGER.debug("No sections tag for chunk [{}, {}]", chunkX, chunkZ);
            return null;
        }
        ListTag sectionTags = sectionTagsOpt.get();

        boolean onlyAir = true;
        for (int i = 0; i < sectionTags.size(); i++) {
            var sectionTagOpt = sectionTags.getCompound(i);
            if (sectionTagOpt.isEmpty()) continue;
            CompoundTag sectionTag = sectionTagOpt.get();

            var yOpt = sectionTag.getByte("Y");
            if (yOpt.isEmpty()) continue;
            int y = yOpt.get().intValue();

            int sectionIndex = y - minSectionY;
            if (sectionIndex >= 0 && sectionIndex < sectionsCount) {
                try {
                    PalettedContainer<BlockState> blocks;
                    if (sectionTag.get("block_states") instanceof CompoundTag blockTag) {
                        if (REMAP_CHAIN_TO_IRON_CHAIN) remapChainInPalette(blockTag);
                        blocks = factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, blockTag)
                                .resultOrPartial(err -> LOGGER.debug("Partial block parse in chunk [{}, {}]: {}", chunkX, chunkZ, err))
                                .orElseGet(factory::createForBlockStates);
                    } else {
                        blocks = factory.createForBlockStates();
                    }

                    PalettedContainer<Holder<Biome>> biomes =
                            sectionTag.get("biomes") instanceof CompoundTag biomeTag
                            ? factory.biomeContainerRWCodec().parse(NbtOps.INSTANCE, biomeTag)
                                    .resultOrPartial(err -> LOGGER.debug("Partial biome parse in chunk [{}, {}]: {}", chunkX, chunkZ, err))
                                    .orElseGet(factory::createForBiomes)
                            : factory.createForBiomes();

                    LevelChunkSection section = new LevelChunkSection(blocks, biomes);
                    sections[sectionIndex] = section;
                    if (!section.hasOnlyAir()) {
                        onlyAir = false;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse section y={} for chunk [{}, {}]: {}",
                            y, chunkX, chunkZ, e.getMessage());
                }
            }

            int lightIdx = y - minLightSection;
            if (lightIdx >= 0 && lightIdx < lightSections) {
                if (sectionTag.get(SerializableChunkData.BLOCK_LIGHT_TAG) instanceof ByteArrayTag blt) {
                    blockLight[lightIdx] = blt.getAsByteArray();
                }
                if (hasSkyLight && sectionTag.get(SerializableChunkData.SKY_LIGHT_TAG) instanceof ByteArrayTag slt) {
                    skyLight[lightIdx] = slt.getAsByteArray();
                }
            }
        }

        for (int i = 0; i < sectionsCount; i++) {
            if (sections[i] == null) {
                sections[i] = new LevelChunkSection(
                        factory.createForBlockStates(),
                        factory.createForBiomes()
                );
            } else {
                sections[i].recalcBlockCounts();
            }
        }

        return writePacket(chunkX, chunkZ, sections, blockLight, skyLight, level);
    }

    private static ByteBuf writePacket(
            int chunkX, int chunkZ,
            LevelChunkSection[] sections,
            byte[][] blockLight, byte[][] skyLight,
            ServerLevel level
    ) {
        ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer(MIN_PACKET_SIZE, Integer.MAX_VALUE);
        try {
            VarInt.write(raw, PacketIdRegistry.getLevelChunkWithLightId());
            raw.writeInt(chunkX);
            raw.writeInt(chunkZ);
            VarInt.write(raw, 0);
            writeSections(raw, sections);
            VarInt.write(raw, 0);
            writeLightData(raw, blockLight, skyLight);

            return raw;
        } catch (Throwable t) {
            LOGGER.error("Failed to write packet for chunk [{}, {}]: {}", chunkX, chunkZ, t.getMessage(), t);
            raw.release();
            return null;
        }
    }

    private static void writeSections(ByteBuf raw, LevelChunkSection[] sections) {
        boolean applyFix = SharedConstants.getProtocolVersion() == PROTOCOL_MC296121;

        int serializedSize = 0;
        for (LevelChunkSection section : sections) {
            serializedSize += section.getSerializedSize();
            if (applyFix) {
                serializedSize -= VarInt.getByteSize(
                        section.getStates().data.storage().getRaw().length)
                        + VarInt.getByteSize(
                        ((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length);
            }
        }

        VarInt.write(raw, serializedSize);
        int expectedEnd = raw.writerIndex() + serializedSize;

        FriendlyByteBuf friendly = new FriendlyByteBuf(raw);
        for (LevelChunkSection section : sections) {
            section.write(friendly, null, 0);
        }

        if (raw.writerIndex() != expectedEnd) {
            throw new IllegalStateException(
                    "Section size mismatch: expected writerIndex=" + expectedEnd
                    + " but got " + raw.writerIndex()
                    + " (diff=" + (raw.writerIndex() - expectedEnd) + ")"
            );
        }
    }

    private static void writeLightData(ByteBuf buf, byte[][] blockLight, byte[][] skyLight) {
        int count = blockLight.length;
        int words = (count + 63) >> 6;

        if (words == 1) {
            long notSkyEmptyMask = 0;
            long skyEmptyMask = 0;
            long notBlockEmptyMask = 0;
            long blockEmptyMask = 0;

            int skyDataCount = 0;
            int blockDataCount = 0;

            for (int i = 0; i < count; i++) {
                byte[] sky = (skyLight != null) ? skyLight[i] : null;
                byte[] block = blockLight[i];
                long bit = 1L << i;

                if (sky == null) {
                    skyEmptyMask |= bit;
                } else {
                    notSkyEmptyMask |= bit;
                    skyDataCount++;
                }

                if (block == null) {
                    blockEmptyMask |= bit;
                } else {
                    notBlockEmptyMask |= bit;
                    blockDataCount++;
                }
            }

            if (skyLight != null) {
                writeBitSetDirect(buf, notSkyEmptyMask);
                writeBitSetDirect(buf, notBlockEmptyMask);
                writeBitSetDirect(buf, skyEmptyMask);
                writeBitSetDirect(buf, blockEmptyMask);

                VarInt.write(buf, skyDataCount);
                for (int i = 0; i < count; i++) {
                    byte[] sky = skyLight[i];
                    if (sky != null) {
                        FriendlyByteBuf.writeByteArray(buf, sky);
                    }
                }

                // Two-Pass: write blockData directly to buf without ArrayList
                VarInt.write(buf, blockDataCount);
                for (int i = 0; i < count; i++) {
                    byte[] block = blockLight[i];
                    if (block != null) {
                        FriendlyByteBuf.writeByteArray(buf, block);
                    }
                }
            } else {
                buf.writeByte(0);
                writeBitSetDirect(buf, notBlockEmptyMask);
                buf.writeByte(0);
                writeBitSetDirect(buf, blockEmptyMask);
                buf.writeByte(0);

                VarInt.write(buf, blockDataCount);
                for (int i = 0; i < count; i++) {
                    byte[] block = blockLight[i];
                    if (block != null) {
                        FriendlyByteBuf.writeByteArray(buf, block);
                    }
                }
            }
        } else {
            long[] notSkyEmpty = new long[words];
            long[] skyEmpty = new long[words];
            long[] notBlockEmpty = new long[words];
            long[] blockEmpty = new long[words];

            int skyDataCount = 0;
            int blockDataCount = 0;

            for (int i = 0; i < count; i++) {
                byte[] sky = (skyLight != null) ? skyLight[i] : null;
                byte[] block = blockLight[i];

                int wordIdx = i >> 6;
                long bit = 1L << (i & 0x3F);

                if (sky == null) {
                    skyEmpty[wordIdx] |= bit;
                } else {
                    notSkyEmpty[wordIdx] |= bit;
                    skyDataCount++;
                }

                if (block == null) {
                    blockEmpty[wordIdx] |= bit;
                } else {
                    notBlockEmpty[wordIdx] |= bit;
                    blockDataCount++;
                }
            }

            if (skyLight != null) {
                writeBitSet(buf, notSkyEmpty);
                writeBitSet(buf, notBlockEmpty);
                writeBitSet(buf, skyEmpty);
                writeBitSet(buf, blockEmpty);

                VarInt.write(buf, skyDataCount);
                for (int i = 0; i < count; i++) {
                    byte[] sky = skyLight[i];
                    if (sky != null) {
                        FriendlyByteBuf.writeByteArray(buf, sky);
                    }
                }

                VarInt.write(buf, blockDataCount);
                for (int i = 0; i < count; i++) {
                    byte[] block = blockLight[i];
                    if (block != null) {
                        FriendlyByteBuf.writeByteArray(buf, block);
                    }
                }
            } else {
                buf.writeByte(0);
                writeBitSet(buf, notBlockEmpty);
                buf.writeByte(0);
                writeBitSet(buf, blockEmpty);
                buf.writeByte(0);

                VarInt.write(buf, blockDataCount);
                for (int i = 0; i < count; i++) {
                    byte[] block = blockLight[i];
                    if (block != null) {
                        FriendlyByteBuf.writeByteArray(buf, block);
                    }
                }
            }
        }
    }

    private static void writeBitSetDirect(ByteBuf buf, long mask) {
        VarInt.write(buf, 1);
        buf.writeLong(mask);
    }

    private static void writeBitSet(ByteBuf buf, long[] set) {
        VarInt.write(buf, set.length);
        for (long l : set) buf.writeLong(l);
    }

    /**
     * Walks the palette inside a block_states CompoundTag and replaces any entry
     * whose "Name" is "minecraft:chain" with "minecraft:iron_chain".
     *
     * This handles world data that was saved on a newer server version (where the
     * chain block was renamed from iron_chain to chain) and is now being read on
     * an older server that only knows the iron_chain name.
     */
    private static void remapChainInPalette(CompoundTag blockStatesTag) {
        var paletteOpt = blockStatesTag.getList("palette");
        if (paletteOpt.isEmpty()) return;
        ListTag palette = paletteOpt.get();
        for (int i = 0; i < palette.size(); i++) {
            var entryOpt = palette.getCompound(i);
            if (entryOpt.isEmpty()) continue;
            CompoundTag entry = entryOpt.get();
            var nameOpt = entry.getString("Name");
            if (nameOpt.isPresent() && "minecraft:chain".equals(nameOpt.get())) {
                entry.putString("Name", "minecraft:iron_chain");
            }
        }
    }
}
