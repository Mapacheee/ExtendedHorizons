package me.mapacheee.extendedhorizons.fakechunks.disk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayProcessor;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkSectionCountWriter;
import me.mapacheee.extendedhorizons.fakechunks.netty.PacketIdRegistry;
import me.mapacheee.extendedhorizons.util.ChunkSerializationCompat;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.BitSet;


public final class DiskChunkSerializer {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkSerializer.class);

  private static final int MIN_PACKET_SIZE = 4096;
  private static final int MAX_PACKET_BUFFER = 4 * 1024 * 1024;
  private static final int EXTRA_LIGHT_SECTIONS = 2;
  private static final int LIGHT_SECTION_OFFSET = 1;
  private static final boolean REMAP_CHAIN = BuiltInRegistries.BLOCK.keySet().stream()
    .noneMatch(key -> "minecraft:chain".equals(key.toString()));
  private static volatile boolean directSectionsCompatible = true;

  private DiskChunkSerializer() {
  }

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

    if (REMAP_CHAIN) {
      remapLegacyPalettes(rootTag);
    }
    if (antiXray == null && directSectionsCompatible) {
      try {
        return writeDirectPacket(rootTag, level, chunkX, chunkZ, hasSkyLight);
      } catch (LinkageError error) {
        directSectionsCompatible = false;
        LOGGER.debug("Direct section counting unavailable, using native chunk parser", error);
      }
    }

    int sectionsCount = level.getSectionsCount();
    int minSectionY = level.getMinSectionY();
    int lightSections = sectionsCount + EXTRA_LIGHT_SECTIONS;
    int minLightSection = minSectionY - LIGHT_SECTION_OFFSET;

    PalettedContainerFactory factory = level.palettedContainerFactory();
    LevelChunkSection[] sections = new LevelChunkSection[sectionsCount];
    byte[][] blockLight = new byte[lightSections][];
    byte[][] skyLight = hasSkyLight ? new byte[lightSections][] : null;

    SerializableChunkData chunkData;
    try {
      chunkData = SerializableChunkData.parse(level, factory, rootTag);
    } catch (Throwable throwable) {
      LOGGER.debug("Direct chunk parse failed for [{}, {}], using Paper fallback: {}",
        chunkX, chunkZ, throwable.getMessage());
      return null;
    }

    if (!chunkData.lightCorrect()) {
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
      }
    }

    return writePacket(chunkX, chunkZ, sections, blockLight, skyLight, level, antiXray);
  }

  private static ByteBuf writeDirectPacket(CompoundTag rootTag, ServerLevel level,
    int chunkX, int chunkZ, boolean hasSkyLight) {
    Tag originalSections = rootTag.get(SerializableChunkData.SECTIONS_TAG);
    ListTag tags = rootTag.getList(SerializableChunkData.SECTIONS_TAG).orElseGet(ListTag::new);
    PalettedContainerFactory factory = level.palettedContainerFactory();
    rootTag.put(SerializableChunkData.SECTIONS_TAG, new ListTag());
    try {
      if (!SerializableChunkData.parse(level, factory, rootTag).lightCorrect()) {
        return null;
      }
    } catch (RuntimeException exception) {
      return null;
    } finally {
      if (originalSections == null) {
        rootTag.remove(SerializableChunkData.SECTIONS_TAG);
      } else {
        rootTag.put(SerializableChunkData.SECTIONS_TAG, originalSections);
      }
    }

    int count = level.getSectionsCount();
    int minY = level.getMinSectionY();
    DiskSection[] sections = new DiskSection[count];
    byte[][] blockLight = new byte[count + EXTRA_LIGHT_SECTIONS][];
    byte[][] skyLight = hasSkyLight ? new byte[blockLight.length][] : null;
    try {
      for (int i = 0; i < tags.size(); i++) {
        CompoundTag tag = tags.getCompound(i).orElseThrow();
        int sectionIndex = tag.getByteOr("Y", (byte) 0) - minY;
        if (sectionIndex >= 0 && sectionIndex < count) {
          PalettedContainer<BlockState> states = tag.getCompound("block_states")
            .map(data -> factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, data).getOrThrow())
            .orElseGet(factory::createForBlockStates);
          PalettedContainerRO<Holder<Biome>> biomes = tag.getCompound("biomes")
            .map(data -> factory.biomeContainerCodec().parse(NbtOps.INSTANCE, data).getOrThrow())
            .orElseGet(factory::createForBiomes);
          sections[sectionIndex] = new DiskSection(states, biomes);
        }
        int lightIndex = sectionIndex + LIGHT_SECTION_OFFSET;
        if (lightIndex >= 0 && lightIndex < blockLight.length) {
          blockLight[lightIndex] = tag.getByteArray("BlockLight").orElse(null);
          if (skyLight != null) {
            skyLight[lightIndex] = tag.getByteArray("SkyLight").orElse(null);
          }
          if ((blockLight[lightIndex] != null && blockLight[lightIndex].length != 2048)
            || (skyLight != null && skyLight[lightIndex] != null && skyLight[lightIndex].length != 2048)) {
            return null;
          }
        }
      }
      DiskSection empty = new DiskSection(factory.createForBlockStates(), factory.createForBiomes());
      ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer(MIN_PACKET_SIZE, MAX_PACKET_BUFFER);
      ByteBuf data = PooledByteBufAllocator.DEFAULT.buffer(MIN_PACKET_SIZE, MAX_PACKET_BUFFER);
      try {
        FriendlyByteBuf out = new FriendlyByteBuf(data);
        for (DiskSection section : sections) {
          (section == null ? empty : section).write(out);
        }
        VarInt.write(raw, PacketIdRegistry.getLevelChunkWithLightId());
        raw.writeInt(chunkX);
        raw.writeInt(chunkZ);
        VarInt.write(raw, 0);
        VarInt.write(raw, data.readableBytes());
        raw.writeBytes(data, data.readerIndex(), data.readableBytes());
        VarInt.write(raw, 0);
        writeLightData(raw, blockLight, skyLight);
        return raw;
      } catch (RuntimeException | Error exception) {
        raw.release();
        throw exception;
      } finally {
        data.release();
      }
    } catch (RuntimeException exception) {
      LOGGER.debug("Direct palette serialization failed for [{}, {}], using Paper fallback", chunkX, chunkZ, exception);
      return null;
    }
  }

  private record DiskSection(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
    private void write(FriendlyByteBuf out) {
      var data = this.states.data;
      int paletteSize = data.palette().getSize();
      int[] totals = new int[2];
      if (paletteSize == 1) {
        addCounts(data.palette().valueFor(0), data.storage().getSize(), totals);
      } else if (paletteSize <= 256) {
        int[] counts = new int[paletteSize];
        int bits = data.storage().getBits();
        int perLong = Long.SIZE / bits;
        long mask = (1L << bits) - 1L;
        int remaining = data.storage().getSize();
        for (long packed : data.storage().getRaw()) {
          int entries = Math.min(perLong, remaining);
          for (int i = 0; i < entries; i++) {
            counts[(int) (packed & mask)]++;
            packed >>>= bits;
          }
          remaining -= entries;
        }
        for (int i = 0; i < paletteSize; i++) {
          if (counts[i] != 0) {
            addCounts(data.palette().valueFor(i), counts[i], totals);
          }
        }
      } else {
        this.states.count((state, count) -> addCounts(state, count, totals));
      }
      ChunkSectionCountWriter.write(out, (short) totals[0], (short) totals[1]);
      ChunkSerializationCompat.writePalette(this.states, out);
      ChunkSerializationCompat.writePalette(this.biomes, out);
    }

    private static void addCounts(BlockState state, int count, int[] totals) {
      if (!state.isAir()) {
        totals[0] += count;
      }
      if (!state.getFluidState().isEmpty()) {
        totals[1] += count;
      }
    }
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
    ByteBuf data = PooledByteBufAllocator.DEFAULT.buffer(MIN_PACKET_SIZE, MAX_PACKET_BUFFER);
    try {
      FriendlyByteBuf friendly = new FriendlyByteBuf(data);
      for (LevelChunkSection section : sections) {
        ChunkSerializationCompat.writeSection(section, friendly);
      }
      VarInt.write(raw, data.readableBytes());
      raw.writeBytes(data, data.readerIndex(), data.readableBytes());
    } finally {
      data.release();
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
      ChunkSerializationCompat.writeLightMask(buf, notSkyEmpty);
      ChunkSerializationCompat.writeLightMask(buf, notBlockEmpty);
      ChunkSerializationCompat.writeLightMask(buf, skyEmpty);
      ChunkSerializationCompat.writeLightMask(buf, blockEmpty);

      VarInt.write(buf, skyDataCount);
      for (int i = 0; i < count; i++) {
        byte[] sky = skyLight[i];
        if (sky != null) {
          FriendlyByteBuf.writeByteArray(buf, sky);
        }
      }

      VarInt.write(buf, blockDataCount);
      for (byte[] block : blockLight) {
        if (block != null) {
          FriendlyByteBuf.writeByteArray(buf, block);
        }
      }
    } else {
      buf.writeByte(0);
      ChunkSerializationCompat.writeLightMask(buf, notBlockEmpty);
      buf.writeByte(0);
      ChunkSerializationCompat.writeLightMask(buf, blockEmpty);
      buf.writeByte(0);

      VarInt.write(buf, blockDataCount);
      for (byte[] block : blockLight) {
        if (block != null) {
          FriendlyByteBuf.writeByteArray(buf, block);
        }
      }
    }
  }

  /**
   * Walks the palette inside a block_states CompoundTag and replaces any entry
   * whose "Name" is "minecraft:chain" with "minecraft:iron_chain".
   * <p>
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
        ChunkSerializationCompat.writePalette(section.getStates(), out);
        out.readerIndex(statesStart);
        antiXray.process(out, minSectionY + i, false);
        out.readerIndex(0);
        ChunkSerializationCompat.writePalette(section.getBiomes(), out);
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
}
