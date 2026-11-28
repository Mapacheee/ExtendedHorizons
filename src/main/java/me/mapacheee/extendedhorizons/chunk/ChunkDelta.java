package me.mapacheee.extendedhorizons.chunk;

import io.netty.buffer.ByteBufUtil;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import me.mapacheee.extendedhorizons.compat.PacketAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record ChunkDelta(List<Packet<?>> packets, int bytes) {

  private static final int MAX_BLOCKS = 512;

  static ChunkDelta between(ServerLevel level, ChunkData previous, ChunkData.Signature signature, ChunkData next) {
    long key = PacketAccess.chunkKey(next.packet());
    int x = ChunkPos.getX(key);
    int z = ChunkPos.getZ(key);
    var before = PacketAccess.chunkData(previous.packet()).getReadBuffer();
    var after = PacketAccess.chunkData(next.packet()).getReadBuffer();
    try {
      var oldEntities = blockEntities(previous.packet(), x, z);
      var newEntities = blockEntities(next.packet(), x, z);
      var removed = new HashSet<>(oldEntities.keySet());
      removed.removeAll(newEntities.keySet());
      var packets = new ArrayList<Packet<?>>();
      var oldSection = new LevelChunkSection(level.palettedContainerFactory());
      var newSection = new LevelChunkSection(level.palettedContainerFactory());
      int changed = 0;
      int bytes = 0;
      for (int index = 0; index < level.getSectionsCount(); index++) {
        int sectionY = level.getMinSectionY() + index;
        int oldStart = before.readerIndex();
        int newStart = after.readerIndex();
        oldSection.read(before);
        newSection.read(after);
        int sectionBytes = before.readerIndex() - oldStart;
        if (sectionBytes == after.readerIndex() - newStart && ByteBufUtil.equals(before, oldStart, after, newStart, sectionBytes)) {
          continue;
        }
        if (!sameBiomes(oldSection, newSection)) {
          return null;
        }
        var positions = new ShortOpenHashSet();
        var section = SectionPos.of(x, sectionY, z);
        for (int i = 0; i < 4096; i++) {
          int lx = i >> 8;
          int ly = i & 15;
          int lz = (i >> 4) & 15;
          if (oldSection.getBlockState(lx, ly, lz) != newSection.getBlockState(lx, ly, lz)) {
            if (++changed > MAX_BLOCKS) {
              return null;
            }
            short relative = (short) i;
            positions.add(relative);
            if (!removed.isEmpty()) {
              removed.remove(section.relativeToBlockPos(relative));
            }
          }
        }
        if (!positions.isEmpty()) {
          packets.add(new ClientboundSectionBlocksUpdatePacket(section, positions, newSection));
          bytes += 16 + 8 * positions.size();
        }
      }
      if (before.isReadable() || after.isReadable() || !removed.isEmpty()) {
        return null;
      }
      for (var entry : newEntities.entrySet()) {
        BlockEntity entity = entry.getValue();
        BlockEntity old = oldEntities.get(entry.getKey());
        if (!entity.equals(old) && (entity.tag() != null || old != null)) {
          CompoundTag tag = entity.tag() == null ? new CompoundTag() : entity.tag();
          packets.add(new ClientboundBlockEntityDataPacket(entry.getKey(), entity.type(), tag));
          bytes += 16 + tag.sizeInBytes();
        }
      }
      if (!signature.sameLight(next.signature())) {
        packets.add(next.lightPacket());
        bytes += next.signature().lightBytes() + 10;
      }
      return bytes < next.bytes() ? new ChunkDelta(List.copyOf(packets), bytes) : null;
    } finally {
      before.release();
      after.release();
    }
  }

  private static boolean sameBiomes(LevelChunkSection before, LevelChunkSection after) {
    for (int i = 0; i < 64; i++) {
      int x = i & 3;
      int y = i >> 4;
      int z = (i >> 2) & 3;
      if (!before.getNoiseBiome(x, y, z).equals(after.getNoiseBiome(x, y, z))) {
        return false;
      }
    }
    return true;
  }

  private static Map<BlockPos, BlockEntity> blockEntities(ClientboundLevelChunkWithLightPacket packet, int x, int z) {
    Map<BlockPos, BlockEntity> entities = new HashMap<>();
    PacketAccess.forEachBlockEntity(PacketAccess.chunkData(packet), x, z,
      (pos, type, tag) -> entities.put(pos.immutable(), new BlockEntity(type, tag)));
    return entities;
  }

  private record BlockEntity(BlockEntityType<?> type, CompoundTag tag) {}
}
