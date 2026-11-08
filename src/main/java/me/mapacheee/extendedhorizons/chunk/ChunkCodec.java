package me.mapacheee.extendedhorizons.chunk;

import me.mapacheee.extendedhorizons.compat.LightPacketAccess;
import me.mapacheee.extendedhorizons.compat.PacketAccess;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

final class ChunkCodec {

  private static final int MAX_PACKET_BYTES = 8 * 1024 * 1024;

  private ChunkCodec() {
  }

  static ChunkData loaded(ServerLevel level, LevelChunk chunk, ChunkMask mask) {
    if (!chunk.isLightCorrect()) {
      return null;
    }
    var lightEngine = level.getChunkSource().getLightEngine();
    if (mask != null) {
      var sections = chunk.getSections().clone();
      mask.apply(level, sections);
      var entities = chunk.getBlockEntities().values().stream().filter(entity -> {
        var state = chunk.getBlockState(entity.getBlockPos());
        return !mask.test(state) && entity.isValidBlockState(state);
      }).toList();
      Map<Heightmap.Types, long[]> heights = new EnumMap<>(Heightmap.Types.class);
      for (var entry : chunk.getHeightmaps()) {
        heights.put(entry.getKey(), entry.getValue().getRawData());
      }
      var pos = chunk.getPos();
      var light = new ClientboundLightUpdatePacketData(pos, lightEngine, null, null);
      return encode(level, PacketAccess.x(pos), PacketAccess.z(pos), sections, heights, entities,
        output -> LightPacketAccess.write(output, light));
    }
    var packet = new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null, false);
    ByteBuf buffer = Unpooled.buffer(4096, MAX_PACKET_BYTES);
    try {
      ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(buffer, level.registryAccess()), packet);
      return new ChunkData(packet, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  static ChunkData disk(ServerLevel level, int x, int z, CompoundTag tag, ChunkMask mask) {
    int version = tag.getIntOr("DataVersion", -1);
    int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    if (version < 0 || version > currentVersion
      || tag.getIntOr("xPos", Integer.MIN_VALUE) != x || tag.getIntOr("zPos", Integer.MIN_VALUE) != z) {
      return null;
    }
    if (version != currentVersion) {
      tag = level.getChunkSource().chunkMap.upgradeChunkTag(tag.copy());
    }
    SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
    boolean vanillaLight = !tag.contains("starlight.light_version") && tag.getBooleanOr("isLightOn", false);
    if (data == null || data.chunkStatus() != ChunkStatus.FULL || !data.lightCorrect() && !vanillaLight) {
      return null;
    }
    int count = level.getSectionsCount();
    int minSection = level.getMinSectionY();
    LevelChunkSection[] sections = new LevelChunkSection[count];
    DataLayer[] blockLight = new DataLayer[count + 2];
    DataLayer[] skyLight = new DataLayer[count + 2];
    for (var section : data.sectionData()) {
      int index = section.y() - minSection;
      if (index >= 0 && index < count) {
        sections[index] = section.chunkSection();
      }
      int lightIndex = index + 1;
      if (lightIndex >= 0 && lightIndex < blockLight.length) {
        StarlightSectionData state = (StarlightSectionData) (Object) section;
        blockLight[lightIndex] = vanillaLight ? section.blockLight() : light(section.blockLight(), state.starlight$getBlockLightState());
        if (level.dimensionType().hasSkyLight()) {
          skyLight[lightIndex] = vanillaLight ? section.skyLight() : light(section.skyLight(), state.starlight$getSkyLightState());
        }
      }
    }
    LevelChunkSection empty = new LevelChunkSection(level.palettedContainerFactory());
    for (int i = 0; i < count; i++) {
      if (sections[i] == null) {
        sections[i] = empty;
      }
    }
    if (mask != null) {
      mask.apply(level, sections);
    }
    return encode(level, x, z, sections, data.heightmaps(), diskBlockEntities(level, data, sections),
      output -> writeLight(output, skyLight, blockLight));
  }

  private static ChunkData encode(ServerLevel level, int x, int z, LevelChunkSection[] sections,
    Map<Heightmap.Types, long[]> heights, List<BlockEntity> entities, Consumer<RegistryFriendlyByteBuf> writeLight) {
    int sectionBytes = 0;
    for (LevelChunkSection section : sections) {
      sectionBytes += section.getSerializedSize();
    }
    if (sectionBytes > 2 * 1024 * 1024) {
      return null;
    }
    ByteBuf buffer = Unpooled.buffer(sectionBytes + 1024, MAX_PACKET_BYTES);
    try {
      RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(buffer, level.registryAccess());
      output.writeInt(x);
      output.writeInt(z);
      writeHeightmaps(output, level, sections, heights);
      output.writeVarInt(sectionBytes);
      for (int i = 0; i < sections.length; i++) {
        sections[i].write(output, null, i);
      }
      writeBlockEntities(output, level, entities);
      writeLight.accept(output);
      int bytes = buffer.readableBytes();
      var packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(output);
      if (output.isReadable()) {
        throw new IllegalStateException("Chunk packet has " + output.readableBytes() + " trailing bytes of " + bytes);
      }
      packet.setReady(true);
      return new ChunkData(packet, bytes);
    } finally {
      buffer.release();
    }
  }

  private static DataLayer light(DataLayer data, int state) {
    if (state < 0) {
      return null;
    }
    return new SWMRNibbleArray(data == null ? null : data.getData(), state).toVanillaNibble();
  }

  private static void writeHeightmaps(RegistryFriendlyByteBuf output, ServerLevel level,
    LevelChunkSection[] sections, Map<Heightmap.Types, long[]> stored) {
    Map<Heightmap.Types, long[]> maps = new EnumMap<>(Heightmap.Types.class);
    int bits = Mth.ceillog2(level.getHeight() + 1);
    int expectedLength = (256 + (64 / bits) - 1) / (64 / bits);
    for (Heightmap.Types type : Heightmap.Types.values()) {
      if (!type.sendToClient()) {
        continue;
      }
      long[] heights = stored.get(type);
      if (heights == null || heights.length != expectedLength) {
        SimpleBitStorage rebuilt = new SimpleBitStorage(bits, 256);
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            for (int y = level.getHeight() - 1; y >= 0; y--) {
              if (type.isOpaque().test(sections[y >> 4].getBlockState(x, y & 15, z))) {
                rebuilt.set((z << 4) | x, y + 1);
                break;
              }
            }
          }
        }
        heights = rebuilt.getRaw();
      }
      maps.put(type, heights);
    }
    output.writeVarInt(maps.size());
    maps.forEach((type, heights) -> {
      Heightmap.Types.STREAM_CODEC.encode(output, type);
      ByteBufCodecs.LONG_ARRAY.encode(output, heights);
    });
  }

  private static List<BlockEntity> diskBlockEntities(ServerLevel level, SerializableChunkData data, LevelChunkSection[] sections) {
    List<BlockEntity> entities = new ArrayList<>();
    for (CompoundTag tag : data.blockEntities()) {
      BlockPos pos = BlockEntity.getPosFromTag(data.chunkPos(), tag);
      int sectionIndex = (pos.getY() >> 4) - level.getMinSectionY();
      if (sectionIndex < 0 || sectionIndex >= sections.length
        || (pos.getX() >> 4) != PacketAccess.x(data.chunkPos()) || (pos.getZ() >> 4) != PacketAccess.z(data.chunkPos())) {
        continue;
      }
      var state = sections[sectionIndex].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
      if (!state.hasBlockEntity()) {
        continue;
      }
      BlockEntity entity = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
      if (entity != null && entity.isValidBlockState(state)) {
        entities.add(entity);
      }
    }
    return entities;
  }

  private static void writeBlockEntities(RegistryFriendlyByteBuf output, ServerLevel level, List<BlockEntity> entities) {
    output.writeVarInt(entities.size());
    for (BlockEntity entity : entities) {
      BlockPos pos = entity.getBlockPos();
      output.writeByte(((pos.getX() & 15) << 4) | (pos.getZ() & 15));
      output.writeShort(pos.getY());
      ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE).encode(output, entity.getType());
      CompoundTag update = entity.sanitizeSentNbt(entity.getUpdateTag(level.registryAccess()));
      ByteBufCodecs.OPTIONAL_COMPOUND_TAG.encode(output, update.isEmpty() ? Optional.empty() : Optional.of(update));
    }
  }

  private static void writeLight(RegistryFriendlyByteBuf output, DataLayer[] sky, DataLayer[] block) {
    BitSet skyMask = new BitSet();
    BitSet blockMask = new BitSet();
    BitSet emptySky = new BitSet();
    BitSet emptyBlock = new BitSet();
    List<byte[]> skyUpdates = lightUpdates(sky, skyMask, emptySky);
    List<byte[]> blockUpdates = lightUpdates(block, blockMask, emptyBlock);
    LightPacketAccess.write(output, skyMask, blockMask, emptySky, emptyBlock, skyUpdates, blockUpdates);
  }

  private static List<byte[]> lightUpdates(DataLayer[] layers, BitSet mask, BitSet empty) {
    List<byte[]> updates = new ArrayList<>();
    for (int i = 0; i < layers.length; i++) {
      DataLayer layer = layers[i];
      if (layer != null) {
        if (layer.isEmpty()) {
          empty.set(i);
        } else {
          mask.set(i);
          updates.add(layer.getData());
        }
      }
    }
    return updates;
  }
}
