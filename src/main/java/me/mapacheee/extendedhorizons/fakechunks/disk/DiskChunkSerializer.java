package me.mapacheee.extendedhorizons.fakechunks.disk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.netty.PacketIdRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerLevel;
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

    @SuppressWarnings("unchecked")
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
                    PalettedContainer<net.minecraft.world.level.block.state.BlockState> blocks =
                            sectionTag.get("block_states") instanceof CompoundTag blockTag
                            ? factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, blockTag).getOrThrow()
                            : factory.createForBlockStates();

                    PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes =
                            sectionTag.get("biomes") instanceof CompoundTag biomeTag
                            ? factory.biomeContainerRWCodec().parse(NbtOps.INSTANCE, biomeTag).getOrThrow()
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

        if (onlyAir) {
            LOGGER.debug("Chunk [{}, {}] is all-air, skipping", chunkX, chunkZ);
            return null;
        }

        for (int i = 0; i < sectionsCount; i++) {
            if (sections[i] == null) {
                sections[i] = new LevelChunkSection(
                        factory.createForBlockStates(),
                        factory.createForBiomes()
                );
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

        List<byte[]> skyData = new ArrayList<>(count);
        BitSet notSkyEmpty = new BitSet();
        BitSet skyEmpty = new BitSet();

        List<byte[]> blockData = new ArrayList<>(count);
        BitSet notBlockEmpty = new BitSet();
        BitSet blockEmpty = new BitSet();

        for (int i = 0; i < count; i++) {
            byte[] sky   = (skyLight != null) ? skyLight[i] : null;
            byte[] block = blockLight[i];

            if (sky == null) { skyEmpty.set(i); }
            else { notSkyEmpty.set(i); skyData.add(sky); }

            if (block == null) { blockEmpty.set(i); }
            else { notBlockEmpty.set(i); blockData.add(block); }
        }

        if (skyLight != null) {
            writeBitSet(buf, notSkyEmpty.toLongArray());
            writeBitSet(buf, notBlockEmpty.toLongArray());
            writeBitSet(buf, skyEmpty.toLongArray());
            writeBitSet(buf, blockEmpty.toLongArray());
            writeByteArrayList(buf, skyData);
            writeByteArrayList(buf, blockData);
        } else {
            buf.writeByte(0);
            writeBitSet(buf, notBlockEmpty.toLongArray());
            buf.writeByte(0);
            writeBitSet(buf, blockEmpty.toLongArray());
            buf.writeByte(0);
            writeByteArrayList(buf, blockData);
        }
    }

    private static void writeBitSet(ByteBuf buf, long[] set) {
        VarInt.write(buf, set.length);
        for (long l : set) buf.writeLong(l);
    }

    private static void writeByteArrayList(ByteBuf buf, List<byte[]> list) {
        VarInt.write(buf, list.size());
        for (byte[] arr : list) FriendlyByteBuf.writeByteArray(buf, arr);
    }
}
