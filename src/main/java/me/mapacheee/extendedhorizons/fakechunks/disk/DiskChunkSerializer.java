package me.mapacheee.extendedhorizons.fakechunks.disk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayProcessor;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkSectionCountWriter;
import me.mapacheee.extendedhorizons.fakechunks.netty.PacketIdRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.BitSet;


public final class DiskChunkSerializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkSerializer.class);

    private static final int PROTOCOL_MC296121 = 770;
    private static final int MIN_PACKET_SIZE = 4096;
    private static final int MAX_PACKET_BUFFER = 4 * 1024 * 1024;
    private static final int EXTRA_LIGHT_SECTIONS = 2;
    private static final int LIGHT_SECTION_OFFSET = 1;

    private DiskChunkSerializer() {}

    public static ByteBuf serialize(
            byte[] nbtBytes, ServerLevel level,
            int chunkX, int chunkZ, boolean hasSkyLight
    ) {
        return serialize(nbtBytes, level, chunkX, chunkZ, hasSkyLight, null);
    }

    public static ByteBuf serialize(byte[] nbtBytes, ServerLevel level,
            int chunkX, int chunkZ, boolean hasSkyLight, AntiXrayProcessor antiXray) {
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

        return serializeFromTag(rootTag, level, chunkX, chunkZ, hasSkyLight, antiXray);
    }

    private static ByteBuf serializeFromTag(
            CompoundTag rootTag, ServerLevel level,
            int chunkX, int chunkZ, boolean hasSkyLight, AntiXrayProcessor antiXray
    ) {
        int currentDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (!isCompatibleChunkTag(rootTag, chunkX, chunkZ, currentDataVersion)) {
            return null;
        }

        int sectionsCount  = level.getSectionsCount();
        int minSectionY    = level.getMinSectionY();
        int lightSections  = sectionsCount + EXTRA_LIGHT_SECTIONS;
        int minLightSection = minSectionY - LIGHT_SECTION_OFFSET;

        PalettedContainerFactory factory = level.palettedContainerFactory();
        LevelChunkSection[] sections = new LevelChunkSection[sectionsCount];
        byte[][] blockLight = new byte[lightSections][];
        byte[][] skyLight   = hasSkyLight ? new byte[lightSections][] : null;

        if (shouldRemapChain()) {
            remapLegacyPalettes(rootTag);
        }

        SerializableChunkData chunkData;
        try {
            chunkData = SerializableChunkData.parse(level, factory, rootTag);
        } catch (Throwable throwable) {
            LOGGER.debug("Direct chunk parse failed for [{}, {}], using Paper fallback: {}",
                chunkX, chunkZ, throwable.getMessage());
            return null;
        }

        if (chunkData == null || !chunkData.lightCorrect()) {
            LOGGER.debug("Chunk [{}, {}] has stale or incomplete light data, using Paper fallback", chunkX, chunkZ);
            return null;
        }

        for (SerializableChunkData.SectionData sectionData : chunkData.sectionData()) {
            int y = sectionData.y();
            int sectionIndex = y - minSectionY;
            LevelChunkSection section = sectionData.chunkSection();
            if (section != null && sectionIndex >= 0 && sectionIndex < sectionsCount) {
                sections[sectionIndex] = section;
            }

            int lightIndex = y - minLightSection;
            if (lightIndex < 0 || lightIndex >= lightSections) {
                continue;
            }
            DataLayer blockLayer = sectionData.blockLight();
            if (blockLayer != null) {
                blockLight[lightIndex] = blockLayer.getData();
            }
            DataLayer skyLayer = sectionData.skyLight();
            if (hasSkyLight && skyLayer != null) {
                skyLight[lightIndex] = skyLayer.getData();
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

        return writePacket(chunkX, chunkZ, sections, blockLight, skyLight, level, antiXray);
    }

    static boolean isCompatibleChunkTag(
            CompoundTag rootTag,
            int chunkX,
            int chunkZ,
            int currentDataVersion
    ) {
        int storedDataVersion = rootTag.getIntOr(SharedConstants.DATA_VERSION_TAG, -1);
        if (storedDataVersion != currentDataVersion) {
            LOGGER.debug("Chunk [{}, {}] DataVersion {} differs from server {}, using Paper fallback",
                chunkX, chunkZ, storedDataVersion, currentDataVersion);
            return false;
        }
        if (rootTag.getIntOr(SerializableChunkData.X_POS_TAG, Integer.MIN_VALUE) != chunkX
            || rootTag.getIntOr(SerializableChunkData.Z_POS_TAG, Integer.MIN_VALUE) != chunkZ) {
            LOGGER.debug("Chunk coordinates do not match requested [{}, {}], using Paper fallback", chunkX, chunkZ);
            return false;
        }
        String status = rootTag.getStringOr("Status", "");
        int namespaceSeparator = status.lastIndexOf(':');
        String statusPath = namespaceSeparator >= 0 ? status.substring(namespaceSeparator + 1) : status;
        return "full".equals(statusPath);
    }

    private static ByteBuf writePacket(
            int chunkX, int chunkZ,
            LevelChunkSection[] sections,
            byte[][] blockLight, byte[][] skyLight,
            ServerLevel level, AntiXrayProcessor antiXray
    ) {
        ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer(MIN_PACKET_SIZE, MAX_PACKET_BUFFER);
        try {
            VarInt.write(raw, PacketIdRegistry.getLevelChunkWithLightId());
            raw.writeInt(chunkX);
            raw.writeInt(chunkZ);
            VarInt.write(raw, 0);
            if (antiXray == null) {
                writeSections(raw, sections);
            } else {
                writeProtectedSections(raw, sections, level.getMinSectionY(), antiXray);
            }
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

        BitSet notSkyEmpty = new BitSet(count);
        BitSet skyEmpty = new BitSet(count);
        BitSet notBlockEmpty = new BitSet(count);
        BitSet blockEmpty = new BitSet(count);

        int skyDataCount = 0;
        int blockDataCount = 0;

        for (int i = 0; i < count; i++) {
            byte[] sky = (skyLight != null) ? skyLight[i] : null;
            byte[] block = blockLight[i];

            if (sky == null) {
                skyEmpty.set(i);
            } else {
                notSkyEmpty.set(i);
                skyDataCount++;
            }

            if (block == null) {
                blockEmpty.set(i);
            } else {
                notBlockEmpty.set(i);
                blockDataCount++;
            }
        }

        if (skyLight != null) {
            writeBitSet(buf, notSkyEmpty.toLongArray());
            writeBitSet(buf, notBlockEmpty.toLongArray());
            writeBitSet(buf, skyEmpty.toLongArray());
            writeBitSet(buf, blockEmpty.toLongArray());

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
            writeBitSet(buf, notBlockEmpty.toLongArray());
            buf.writeByte(0);
            writeBitSet(buf, blockEmpty.toLongArray());
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

    static void writeProtectedSections(ByteBuf raw, LevelChunkSection[] sections, int minSectionY,
                                       AntiXrayProcessor antiXray) {
        ByteBuf data = PooledByteBufAllocator.DEFAULT.buffer(4096, MAX_PACKET_BUFFER);
        try {
            FriendlyByteBuf out = new FriendlyByteBuf(data);
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                ChunkSectionCountWriter.write(out, section);
                int statesStart = out.writerIndex();
                section.getStates().write(out, null, 0);
                out.readerIndex(statesStart);
                antiXray.process(out, minSectionY + i, false);
                out.readerIndex(0);
                section.getBiomes().write(out, null, 0);
            }
            VarInt.write(raw, data.readableBytes());
            raw.writeBytes(data, data.readerIndex(), data.readableBytes());
        } finally {
            data.release();
        }
    }

    private static void remapLegacyPalettes(CompoundTag rootTag) {
        var sectionsOpt = rootTag.getList(SerializableChunkData.SECTIONS_TAG);
        if (sectionsOpt.isEmpty()) {
            return;
        }
        ListTag sections = sectionsOpt.get();
        for (int i = 0; i < sections.size(); i++) {
            var sectionOpt = sections.getCompound(i);
            if (sectionOpt.isEmpty()) {
                continue;
            }
            if (sectionOpt.get().get("block_states") instanceof CompoundTag blockStates) {
                remapChainInPalette(blockStates);
            }
        }
    }

    private static boolean shouldRemapChain() {
        return BuiltInRegistries.BLOCK.keySet().stream()
            .noneMatch(key -> "minecraft:chain".equals(key.toString()));
    }
}
