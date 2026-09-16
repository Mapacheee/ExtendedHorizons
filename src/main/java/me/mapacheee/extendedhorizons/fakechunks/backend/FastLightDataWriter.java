package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import me.mapacheee.extendedhorizons.util.ChunkSerializationCompat;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

final class FastLightDataWriter {

  private static final int NO_SKY_HEADER_BYTES = 3;
  private static final int EXTRA_LIGHT_SECTIONS = 2;
  private static final int FULL_BRIGHT_ARRAY_BYTES = 2048;
  private static final byte[] FULL_BRIGHT;

  static {
    FULL_BRIGHT = new byte[FULL_BRIGHT_ARRAY_BYTES];
    Arrays.fill(FULL_BRIGHT, (byte) 0xFF);
  }

  private FastLightDataWriter() {
  }

  static int estimateLightDataSize(LevelChunk chunk) {
    byte[][] blockLight =
      java.util.Objects.requireNonNull(readLightLayers(chunk, LightLayer.BLOCK, false));
    byte[][] skyLight = readLightLayers(chunk, LightLayer.SKY, true);
    if (skyLight == null) {
      return estimateNoSkyLightSize(blockLight);
    }

    LightMasks masks = buildMasks(blockLight, skyLight);
    int size = 0;
    size += ChunkSerializationCompat.lightMaskSize(masks.notSkyEmpty);
    size += ChunkSerializationCompat.lightMaskSize(masks.notBlockEmpty);
    size += ChunkSerializationCompat.lightMaskSize(masks.skyEmpty);
    size += ChunkSerializationCompat.lightMaskSize(masks.blockEmpty);
    size += estimateByteArrayList(masks.skyData);
    size += estimateByteArrayList(masks.blockData);
    return size;
  }

  static boolean hasInitialisedLight(LevelChunk chunk) {
    LevelLightEngine engine = chunk.getLevel().getLightEngine();
    for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
      SectionPos section = SectionPos.of(chunk.getPos(), y);
      if (engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(section) != null
        || engine.getLayerListener(LightLayer.SKY).getDataLayerData(section) != null) {
        return true;
      }
    }
    return false;
  }

  static void writeSyntheticFullBrightLight(
    FriendlyByteBuf out,
    int chunkSectionCount,
    boolean hasSkyLight
  ) {
    int sectionCount = chunkSectionCount + EXTRA_LIGHT_SECTIONS;

    if (hasSkyLight) {
      BitSet notSkyEmpty = new BitSet(sectionCount);
      notSkyEmpty.set(0, sectionCount);
      BitSet notBlockEmpty = new BitSet(sectionCount);
      BitSet skyEmpty = new BitSet(sectionCount);
      BitSet blockEmpty = new BitSet(sectionCount);
      blockEmpty.set(0, sectionCount);

      ChunkSerializationCompat.writeLightMask(out, notSkyEmpty);
      ChunkSerializationCompat.writeLightMask(out, notBlockEmpty);
      ChunkSerializationCompat.writeLightMask(out, skyEmpty);
      ChunkSerializationCompat.writeLightMask(out, blockEmpty);

      VarIntUtil.writeVarInt(out, sectionCount);
      for (int i = 0; i < sectionCount; i++) {
        FriendlyByteBuf.writeByteArray(out, FULL_BRIGHT);
      }
      out.writeByte(0);
    } else {
      BitSet notSkyEmpty = new BitSet(sectionCount);
      BitSet notBlockEmpty = new BitSet(sectionCount);
      notBlockEmpty.set(0, sectionCount);
      BitSet skyEmpty = new BitSet(sectionCount);
      skyEmpty.set(0, sectionCount);
      BitSet blockEmpty = new BitSet(sectionCount);

      ChunkSerializationCompat.writeLightMask(out, notSkyEmpty);
      ChunkSerializationCompat.writeLightMask(out, notBlockEmpty);
      ChunkSerializationCompat.writeLightMask(out, skyEmpty);
      ChunkSerializationCompat.writeLightMask(out, blockEmpty);

      out.writeByte(0);
      VarIntUtil.writeVarInt(out, sectionCount);
      for (int i = 0; i < sectionCount; i++) {
        FriendlyByteBuf.writeByteArray(out, FULL_BRIGHT);
      }
    }
  }

  static void writeLightData(FriendlyByteBuf out, LevelChunk chunk) {
    byte[][] blockLight =
      java.util.Objects.requireNonNull(readLightLayers(chunk, LightLayer.BLOCK, false));
    byte[][] skyLight = readLightLayers(chunk, LightLayer.SKY, true);

    if (skyLight == null) {
      writeNoSkyLightData(out, blockLight);
      return;
    }

    LightMasks masks = buildMasks(blockLight, skyLight);

    ChunkSerializationCompat.writeLightMask(out, masks.notSkyEmpty);
    ChunkSerializationCompat.writeLightMask(out, masks.notBlockEmpty);
    ChunkSerializationCompat.writeLightMask(out, masks.skyEmpty);
    ChunkSerializationCompat.writeLightMask(out, masks.blockEmpty);
    writeByteArrayList(out, masks.skyData);
    writeByteArrayList(out, masks.blockData);
  }

  private static byte[][] readLightLayers(LevelChunk chunk, LightLayer layer, boolean allowEmpty) {
    LevelLightEngine engine = chunk.getLevel().getLightEngine();
    LayerLightEventListener listener = engine.getLayerListener(layer);
    byte[][] layers = new byte[engine.getLightSectionCount()][];
    boolean present = false;
    for (int i = 0; i < layers.length; i++) {
      DataLayer data = listener.getDataLayerData(SectionPos.of(chunk.getPos(), engine.getMinLightSection() + i));
      if (data != null) {
        present = true;
        if (!data.isEmpty()) {
          layers[i] = data.getData();
        }
      }
    }
    return present || !allowEmpty ? layers : null;
  }

  private static void writeNoSkyLightData(ByteBuf out, byte[][] blockLight) {
    List<byte[]> blockData = new ArrayList<>(blockLight.length);
    NoSkyMasks masks = buildNoSkyMasks(blockLight, blockData);

    out.writeByte(0);
    ChunkSerializationCompat.writeLightMask(out, masks.notBlockEmpty());
    out.writeByte(0);
    ChunkSerializationCompat.writeLightMask(out, masks.blockEmpty());
    out.writeByte(0);
    writeByteArrayList(out, blockData);
  }

  private static int estimateNoSkyLightSize(byte[][] blockLight) {
    NoSkyMasks masks = buildNoSkyMasks(blockLight, null);

    int size = NO_SKY_HEADER_BYTES;
    size += ChunkSerializationCompat.lightMaskSize(masks.notBlockEmpty());
    size += ChunkSerializationCompat.lightMaskSize(masks.blockEmpty());
    size += varIntSize(masks.blockDataCount()) + masks.blockDataBytes();
    return size;
  }

  private static NoSkyMasks buildNoSkyMasks(byte[][] blockLight, List<byte[]> blockDataOut) {
    int sectionCount = blockLight.length;
    BitSet notBlockEmpty = new BitSet(sectionCount);
    BitSet blockEmpty = new BitSet(sectionCount);
    int blockDataCount = 0;
    int blockDataBytes = 0;

    for (int indexY = 0; indexY < sectionCount; indexY++) {
      byte[] block = blockLight[indexY];
      if (block == null) {
        blockEmpty.set(indexY);
        continue;
      }
      notBlockEmpty.set(indexY);
      blockDataCount++;
      blockDataBytes += varIntSize(block.length) + block.length;
      if (blockDataOut != null) {
        blockDataOut.add(block);
      }
    }

    return new NoSkyMasks(notBlockEmpty, blockEmpty, blockDataCount, blockDataBytes);
  }

  private static LightMasks buildMasks(byte[][] blockLight, byte[][] skyLight) {
    int sectionCount = blockLight.length;
    List<byte[]> skyData = new ArrayList<>(sectionCount);
    BitSet notSkyEmpty = new BitSet(sectionCount);
    BitSet skyEmpty = new BitSet(sectionCount);

    List<byte[]> blockData = new ArrayList<>(sectionCount);
    BitSet notBlockEmpty = new BitSet(sectionCount);
    BitSet blockEmpty = new BitSet(sectionCount);

    for (int indexY = 0; indexY < sectionCount; indexY++) {
      byte[] sky = skyLight[indexY];
      if (sky == null) {
        skyEmpty.set(indexY);
      } else {
        notSkyEmpty.set(indexY);
        skyData.add(sky);
      }
      byte[] block = blockLight[indexY];
      if (block == null) {
        blockEmpty.set(indexY);
      } else {
        notBlockEmpty.set(indexY);
        blockData.add(block);
      }
    }

    return new LightMasks(skyData, notSkyEmpty, skyEmpty, blockData, notBlockEmpty, blockEmpty);
  }

  private static void writeByteArrayList(ByteBuf out, List<byte[]> list) {
    int len = list.size();
    if (len == 0) {
      out.writeByte(0);
      return;
    }
    VarIntUtil.writeVarInt(out, len);
    for (byte[] bytes : list) {
      FriendlyByteBuf.writeByteArray(out, bytes);
    }
  }

  private static int estimateByteArrayList(List<byte[]> list) {
    int len = list.size();
    int size = varIntSize(len);
    for (byte[] bytes : list) {
      size += varIntSize(bytes.length) + bytes.length;
    }
    return size;
  }

  private static int varIntSize(int value) {
    if ((value & (0xFFFFFFFF << 7)) == 0) {
      return 1;
    }
    if ((value & (0xFFFFFFFF << 14)) == 0) {
      return 2;
    }
    if ((value & (0xFFFFFFFF << 21)) == 0) {
      return 3;
    }
    if ((value & (0xFFFFFFFF << 28)) == 0) {
      return 4;
    }
    return 5;
  }

  private record LightMasks(
    List<byte[]> skyData,
    BitSet notSkyEmpty,
    BitSet skyEmpty,
    List<byte[]> blockData,
    BitSet notBlockEmpty,
    BitSet blockEmpty
  ) {
  }

  private record NoSkyMasks(
    BitSet notBlockEmpty,
    BitSet blockEmpty,
    int blockDataCount,
    int blockDataBytes
  ) {
  }
}


