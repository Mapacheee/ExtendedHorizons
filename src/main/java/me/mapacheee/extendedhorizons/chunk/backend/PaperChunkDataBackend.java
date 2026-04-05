package me.mapacheee.extendedhorizons.chunk.backend;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.mojang.serialization.Codec;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;

public class PaperChunkDataBackend implements ChunkDataBackend {

  private static final byte LEVEL_CHUNK_WITH_LIGHT_PACKET_ID = 0x2C;
  private static final int DISK_TAG_FAST_PATH_MAX_SECTIONS = 6;
  private static final long SECTION_CACHE_TTL_MS = 1500L;
  private static final int SECTION_CACHE_MAX_ENTRIES = 4096;
  private static final Heightmap.Types[] SENDABLE_HEIGHTMAP_TYPES =
      Arrays.stream(Heightmap.Types.values()).filter(Heightmap.Types::sendToClient).toArray(Heightmap.Types[]::new);
  private static final int[] SENDABLE_HEIGHTMAP_TYPE_IDS =
      Arrays.stream(SENDABLE_HEIGHTMAP_TYPES).mapToInt(Enum::ordinal).toArray();
  private static final MethodHandle GET_STORAGE_VISIBLE = resolveStorageVisibleGetter();

  private final PaperChunkStorageProbe storageProbe;
  private final Cache<ChunkKey, SectionBlob> sectionBlobCache =
      Caffeine.newBuilder()
          .maximumSize(SECTION_CACHE_MAX_ENTRIES)
          .expireAfterWrite(Duration.ofMillis(SECTION_CACHE_TTL_MS))
          .build();

  public PaperChunkDataBackend() {
    this(new PaperChunkStorageProbe());
  }

  public PaperChunkDataBackend(PaperChunkStorageProbe storageProbe) {
    this.storageProbe = storageProbe;
  }

  private static MethodHandle resolveStorageVisibleGetter() {
    try {
      MethodHandles.Lookup lookup =
          MethodHandles.privateLookupIn(SWMRNibbleArray.class, MethodHandles.lookup());
      return lookup.findGetter(SWMRNibbleArray.class, "storageVisible", byte[].class);
    } catch (Throwable ignored) {
      return null;
    }
  }

  @Override
  public CompletableFuture<ChunkBuildResult> loadOrBuildPacket(
      World world, int chunkX, int chunkZ, boolean generateMissingChunks, ChunkScheduler scheduler) {
    if (world == null || scheduler == null) {
      return CompletableFuture.completedFuture(null);
    }
    boolean loadedBefore = false;
    try {
      loadedBefore = world.isChunkLoaded(chunkX, chunkZ);
    } catch (Throwable ignored) {
    }
    if (loadedBefore) {
      return loadPacket(world, chunkX, chunkZ, false, ChunkDataSource.LOADED, scheduler);
    }
    return storageProbe
        .hasChunkOnDisk(world, chunkX, chunkZ)
        .thenCompose(
            hasOnDisk -> {
              CompletableFuture<ChunkBuildResult> diskFirst;
              if (hasOnDisk) {
                diskFirst =
                    loadPacketFromDiskTag(world, chunkX, chunkZ, scheduler)
                      .thenCompose(
                        result ->
                          result != null
                            ? CompletableFuture.completedFuture(result)
                              : loadPacket(
                                world, chunkX, chunkZ, false, ChunkDataSource.DISK, scheduler));
              } else {
                diskFirst = loadPacket(world, chunkX, chunkZ, false, ChunkDataSource.DISK, scheduler);
              }
              return diskFirst.thenCompose(
                result -> {
                  if (result != null) {
                    return CompletableFuture.completedFuture(result);
                  }
                  if (!generateMissingChunks) {
                    return CompletableFuture.completedFuture(null);
                  }
                return loadPacket(
                    world, chunkX, chunkZ, true, ChunkDataSource.GENERATED, scheduler);
              });
            })
        .exceptionally(e -> null);
  }

  private CompletableFuture<ChunkBuildResult> loadPacketFromDiskTag(
      World world, int chunkX, int chunkZ, ChunkScheduler scheduler) {
    if (world == null || scheduler == null) {
      return CompletableFuture.completedFuture(null);
    }
    return storageProbe
        .readChunkTag(world, chunkX, chunkZ)
        .thenCompose(
            tagOpt -> {
              if (tagOpt == null || tagOpt.isEmpty()) {
                return CompletableFuture.completedFuture(null);
              }
              CompoundTag diskTag = tagOpt.get();
              if (!shouldUseDiskTagFastPath(diskTag)) {
                return loadPacket(world, chunkX, chunkZ, false, ChunkDataSource.DISK, scheduler);
              }
              CompletableFuture<ChunkBuildResult> promise = new CompletableFuture<>();
              boolean scheduled =
                  scheduler.runAtChunk(
                      world,
                      chunkX,
                      chunkZ,
                      () -> {
                        try {
                          ServerLevel level = ((CraftWorld) world).getHandle();
                          ByteBuf payload = serializeChunkFromTag(level, diskTag, chunkX, chunkZ);
                          if (payload == null || !payload.isReadable()) {
                            promise.complete(null);
                            return;
                          }
                          promise.complete(new ChunkBuildResult(payload, ChunkDataSource.DISK));
                        } catch (Throwable ignored) {
                          promise.complete(null);
                        }
                      });
              if (!scheduled) {
                promise.complete(null);
              }
              return promise;
            })
        .exceptionally(e -> null);
  }

  private boolean shouldUseDiskTagFastPath(CompoundTag chunkTag) {
    return false;
  }

  private CompletableFuture<ChunkBuildResult> loadPacket(
      World world,
      int chunkX,
      int chunkZ,
      boolean generateMissingChunks,
      ChunkDataSource source,
      ChunkScheduler scheduler) {
    CompletableFuture<ChunkBuildResult> promise = new CompletableFuture<>();
    world
        .getChunkAtAsync(chunkX, chunkZ, generateMissingChunks)
        .thenAccept(
            chunk -> {
              if (chunk == null) {
                promise.complete(null);
                return;
              }
              boolean scheduled =
                  scheduler.runAtChunk(
                      world,
                      chunkX,
                      chunkZ,
                      () -> {
                        try {
                          ChunkAccess access = ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);
                          if (access == null) {
                            promise.complete(null);
                            return;
                          }
                          ByteBuf payload = serializeChunk(world.getUID(), access, chunkX, chunkZ);
                          if (payload == null || !payload.isReadable()) {
                            promise.complete(null);
                            return;
                          }
                          promise.complete(
                              new ChunkBuildResult(payload, source));
                        } catch (Throwable ignored) {
                          promise.complete(null);
                        }
                      });
              if (!scheduled) {
                promise.complete(null);
              }
            })
        .exceptionally(
            e -> {
              promise.complete(null);
              return null;
            });
    return promise;
  }

  private ByteBuf serializeChunk(UUID worldId, ChunkAccess access, int chunkX, int chunkZ) {
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeByte(LEVEL_CHUNK_WITH_LIGHT_PACKET_ID);
      buf.writeInt(chunkX);
      buf.writeInt(chunkZ);
      writeHeightmaps(buf, extractHeightmapsData(access));

      LevelChunkSection[] sections = access.getSections();
      SectionBlob sectionBlob = getOrBuildSectionBlob(worldId, chunkX, chunkZ, sections);
      if (sectionBlob == null || sectionBlob.serializedSize() <= 0) {
        buf.release();
        return null;
      }
      VarInt.write(buf, sectionBlob.serializedSize());
      buf.writeBytes(sectionBlob.data());

      VarInt.write(buf, 0);
      byte[][] blockLight = convertStarlightToBytes(access.starlight$getBlockNibbles(), false);
      byte[][] skyLight = convertStarlightToBytes(access.starlight$getSkyNibbles(), true);
      writeLightData(buf, blockLight, skyLight);

      return buf;
    } catch (Throwable ignored) {
      buf.release();
      return null;
    }
  }

  private SectionBlob getOrBuildSectionBlob(
      UUID worldId, int chunkX, int chunkZ, LevelChunkSection[] sections) {
    if (sections == null) return null;
    if (worldId == null) {
      return buildSectionBlob(sections);
    }
    ChunkKey key = new ChunkKey(worldId, ChunkPos.asLong(chunkX, chunkZ));
    SectionBlob cached = sectionBlobCache.getIfPresent(key);
    if (cached != null) {
      return cached;
    }
    SectionBlob built = buildSectionBlob(sections);
    if (built == null) {
      return null;
    }
    sectionBlobCache.put(key, built);
    return built;
  }

  private SectionBlob buildSectionBlob(LevelChunkSection[] sections) {
    int serializedSize = 0;
    for (int i = 0, len = sections.length; i < len; i++) {
      LevelChunkSection section = sections[i];
      serializedSize += section.getSerializedSize();
      if (SharedConstants.getProtocolVersion() == 770) {
        serializedSize -=
            VarInt.getByteSize(section.states.data.storage().getRaw().length)
                + VarInt.getByteSize(
                    ((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length);
      }
    }
    ByteBuf sectionBuf = Unpooled.buffer(serializedSize);
    try {
      FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(sectionBuf);
      for (int i = 0, len = sections.length; i < len; i++) {
        sections[i].write(friendlyBuf, null, 0);
      }
      if (sectionBuf.writerIndex() != serializedSize) {
        return null;
      }
      byte[] data = new byte[serializedSize];
      sectionBuf.getBytes(0, data);
      return new SectionBlob(serializedSize, data);
    } catch (Throwable ignored) {
      return null;
    } finally {
      sectionBuf.release();
    }
  }

  @Override
  public void invalidate(UUID worldId, long chunkKey) {
    if (worldId == null) return;
    sectionBlobCache.invalidate(new ChunkKey(worldId, chunkKey));
  }

  @Override
  public void clearCaches() {
    sectionBlobCache.invalidateAll();
  }

  private ByteBuf serializeChunkFromTag(ServerLevel level, CompoundTag chunkTag, int chunkX, int chunkZ) {
    if (level == null || chunkTag == null) return null;
    if (!isChunkLit(chunkTag)) return null;
    PalettedContainerFactory factory = level.palettedContainerFactory();
    Codec<PalettedContainer<Holder<Biome>>> biomeCodec = factory.biomeContainerRWCodec();
    Codec<PalettedContainer<BlockState>> blockCodec = factory.blockStatesContainerCodec();
    int sectionCount = level.getSectionsCount();
    LevelChunkSection[] sections = new LevelChunkSection[sectionCount];

    int minSectionY = level.getMinSectionY();
    int minLightSectionY = minSectionY - 1;
    byte[][] blockLight = new byte[sectionCount + 2][];
    byte[][] skyLight = level.dimensionType().hasSkyLight() ? new byte[sectionCount + 2][] : null;
    ListTag sectionTags = chunkTag.getListOrEmpty(SerializableChunkData.SECTIONS_TAG);
    for (int i = 0; i < sectionTags.size(); ++i) {
      Optional<CompoundTag> sectionTagOptional = sectionTags.getCompound(i);
      if (sectionTagOptional.isEmpty()) continue;
      CompoundTag sectionTag = sectionTagOptional.get();
      int sectionY = sectionTag.getByte("Y").orElse((byte) 0);
      int sectionIndex = sectionY - minSectionY;
      if (sectionIndex >= 0 && sectionIndex < sections.length) {
        PalettedContainer<BlockState> blocks = factory.createForBlockStates();
        Object blocksValue = sectionTag.get("block_states");
        if (blocksValue instanceof CompoundTag blockStatesTag) {
          try {
            blocks = blockCodec.parse(NbtOps.INSTANCE, blockStatesTag).getOrThrow();
          } catch (Throwable ignored) {
          }
        }
        PalettedContainer<Holder<Biome>> biomes = factory.createForBiomes();
        Object biomesValue = sectionTag.get("biomes");
        if (biomesValue instanceof CompoundTag biomesTag) {
          try {
            biomes = biomeCodec.parse(NbtOps.INSTANCE, biomesTag).getOrThrow();
          } catch (Throwable ignored) {
          }
        }
        sections[sectionIndex] = new LevelChunkSection(blocks, biomes);
      }

      int lightIndex = sectionY - minLightSectionY;
      if (lightIndex >= 0 && lightIndex < blockLight.length) {
        Object blockLightTag = sectionTag.get(SerializableChunkData.BLOCK_LIGHT_TAG);
        if (blockLightTag instanceof ByteArrayTag byteArrayTag) {
          blockLight[lightIndex] = byteArrayTag.getAsByteArray();
        }
        if (skyLight != null) {
          Object skyLightTag = sectionTag.get(SerializableChunkData.SKY_LIGHT_TAG);
          if (skyLightTag instanceof ByteArrayTag byteArrayTag) {
            skyLight[lightIndex] = byteArrayTag.getAsByteArray();
          }
        }
      }
    }
    LevelChunkSection emptySection =
        new LevelChunkSection(factory.createForBlockStates(), factory.createForBiomes());
    for (int i = 0; i < sections.length; i++) {
      if (sections[i] == null) {
        sections[i] = emptySection;
      }
    }

    long[][] heightmapsData = extractHeightmapsDataFromTag(chunkTag);
    return serializeChunkData(chunkX, chunkZ, minSectionY, sections, blockLight, skyLight, heightmapsData);
  }

  private boolean isChunkLit(CompoundTag chunkTag) {
    Optional<String> statusName = chunkTag.getString("Status");
    if (statusName.isEmpty()) return false;
    ChunkStatus status = ChunkStatus.byName(statusName.get());
    if (status == null || !status.isOrAfter(ChunkStatus.LIGHT)) return false;
    return chunkTag.get(SerializableChunkData.IS_LIGHT_ON_TAG) != null;
  }

  private long[][] extractHeightmapsDataFromTag(CompoundTag chunkTag) {
    CompoundTag heightmaps = chunkTag.getCompoundOrEmpty(SerializableChunkData.HEIGHTMAPS_TAG);
    long[][] heightmapsData = new long[SENDABLE_HEIGHTMAP_TYPES.length][];
    if (heightmaps.isEmpty()) return heightmapsData;
    for (int i = 0, len = SENDABLE_HEIGHTMAP_TYPES.length; i < len; i++) {
      String key = SENDABLE_HEIGHTMAP_TYPES[i].getSerializationKey();
      Object value = heightmaps.get(key);
      if (value instanceof LongArrayTag longArrayTag) {
        heightmapsData[i] = longArrayTag.getAsLongArray();
      }
    }
    return heightmapsData;
  }

  private ByteBuf serializeChunkData(
      int chunkX,
      int chunkZ,
      int minSectionY,
      LevelChunkSection[] sections,
      byte[][] blockLight,
      byte[][] skyLight,
      long[][] heightmapsData) {
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeByte(LEVEL_CHUNK_WITH_LIGHT_PACKET_ID);
      buf.writeInt(chunkX);
      buf.writeInt(chunkZ);
      writeHeightmaps(buf, heightmapsData);
      int serializedSize = 0;
      for (int i = 0, len = sections.length; i < len; i++) {
        LevelChunkSection section = sections[i];
        serializedSize += section.getSerializedSize();
        if (SharedConstants.getProtocolVersion() == 770) {
          serializedSize -=
              VarInt.getByteSize(section.states.data.storage().getRaw().length)
                  + VarInt.getByteSize(
                      ((PalettedContainer<?>) section.getBiomes()).data.storage().getRaw().length);
        }
      }
      VarInt.write(buf, serializedSize);
      int expectedWriterIndex = buf.writerIndex() + serializedSize;
      FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(buf);
      for (int i = 0, len = sections.length; i < len; i++) {
        sections[i].write(friendlyBuf, null, 0);
      }
      if (buf.writerIndex() != expectedWriterIndex) {
        buf.release();
        return null;
      }
      VarInt.write(buf, 0);
      writeLightData(buf, blockLight, skyLight);
      return buf;
    } catch (Throwable ignored) {
      buf.release();
      return null;
    }
  }

  private long[][] extractHeightmapsData(ChunkAccess chunk) {
    long[][] heightmapsData = new long[SENDABLE_HEIGHTMAP_TYPES.length][];
    for (int i = 0, len = SENDABLE_HEIGHTMAP_TYPES.length; i < len; i++) {
      Heightmap.Types type = SENDABLE_HEIGHTMAP_TYPES[i];
      if (chunk.hasPrimedHeightmap(type)) {
        heightmapsData[i] = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
      }
    }
    return heightmapsData;
  }

  private void writeHeightmaps(ByteBuf buf, long[][] heightmapsData) {
    int heightmapsLen = heightmapsData.length;
    int heightmapsCount = 0;
    for (int i = 0; i < heightmapsLen; i++) {
      if (heightmapsData[i] != null) {
        heightmapsCount++;
      }
    }
    VarInt.write(buf, heightmapsCount);
    for (int i = 0; i < heightmapsLen; i++) {
      long[] data = heightmapsData[i];
      if (data != null) {
        VarInt.write(buf, SENDABLE_HEIGHTMAP_TYPE_IDS[i]);
        FriendlyByteBuf.writeLongArray(buf, data);
      }
    }
  }

  private byte[][] convertStarlightToBytes(SWMRNibbleArray[] layers, boolean allowEmpty) {
    if (GET_STORAGE_VISIBLE == null) {
      if (!allowEmpty) {
        return new byte[layers.length][];
      }
      return null;
    }
    try {
      int layerCount = layers.length;
      byte[][] byteLayers = new byte[layerCount][];
      boolean converted = false;
      for (int i = 0; i < layerCount; i++) {
        SWMRNibbleArray layer = layers[i];
        if (layer.isInitialisedVisible()) {
          byteLayers[i] = (byte[]) GET_STORAGE_VISIBLE.invoke(layer);
          converted = true;
        }
      }
      if (converted || !allowEmpty) return byteLayers;
      return null;
    } catch (Throwable exception) {
      throw new RuntimeException(exception);
    }
  }

  private void writeLightData(ByteBuf buf, byte[][] blockLight, byte[][] skyLight) {
    if (skyLight == null) {
      writeNoSkyLightData(buf, blockLight);
      return;
    }

    List<byte[]> skyData = new ArrayList<>(skyLight.length);
    BitSet notSkyEmpty = new BitSet();
    BitSet skyEmpty = new BitSet();
    int blockLightLen = blockLight.length;
    List<byte[]> blockData = new ArrayList<>(blockLightLen);
    BitSet notBlockEmpty = new BitSet();
    BitSet blockEmpty = new BitSet();

    for (int indexY = 0; indexY < blockLightLen; indexY++) {
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

    writeBitSet(buf, notSkyEmpty.toLongArray());
    writeBitSet(buf, notBlockEmpty.toLongArray());
    writeBitSet(buf, skyEmpty.toLongArray());
    writeBitSet(buf, blockEmpty.toLongArray());
    writeByteArrayList(buf, skyData);
    writeByteArrayList(buf, blockData);
  }

  private void writeNoSkyLightData(ByteBuf buf, byte[][] blockLight) {
    int blockLightLen = blockLight.length;
    List<byte[]> blockData = new ArrayList<>(blockLightLen);
    BitSet notBlockEmpty = new BitSet();
    BitSet blockEmpty = new BitSet();

    for (int indexY = 0; indexY < blockLightLen; indexY++) {
      byte[] block = blockLight[indexY];
      if (block == null) {
        blockEmpty.set(indexY);
      } else {
        notBlockEmpty.set(indexY);
        blockData.add(block);
      }
    }

    buf.writeByte(0);
    writeBitSet(buf, notBlockEmpty.toLongArray());
    buf.writeByte(0);
    writeBitSet(buf, blockEmpty.toLongArray());
    buf.writeByte(0);
    writeByteArrayList(buf, blockData);
  }

  private void writeBitSet(ByteBuf buf, long[] set) {
    int len = set.length;
    VarInt.write(buf, len);
    for (int i = 0; i < len; ++i) {
      buf.writeLong(set[i]);
    }
  }

  private void writeByteArrayList(ByteBuf buf, List<byte[]> list) {
    int len = list.size();
    if (len == 0) {
      buf.writeByte(0);
      return;
    }
    if (len == 1) {
      buf.writeByte(1);
      FriendlyByteBuf.writeByteArray(buf, list.getFirst());
      return;
    }
    VarInt.write(buf, len);
    for (int i = 0; i < len; ++i) {
      FriendlyByteBuf.writeByteArray(buf, list.get(i));
    }
  }

  private record ChunkKey(UUID worldId, long chunkKey) {}

  private record SectionBlob(int serializedSize, byte[] data) {}
}
