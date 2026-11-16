package me.mapacheee.extendedhorizons.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class ChunkMask implements Predicate<BlockState> {

  private static final ThreadLocal<int[]> INDICES = ThreadLocal.withInitial(() -> new int[4096]);

  private final Set<Block> hidden;
  private final BlockState upper;
  private final BlockState lower;

  ChunkMask(ServerLevel level, Set<Block> hidden) {
    this.hidden = hidden;
    if (level.dimensionType().hasCeiling() || level.dimension() == Level.NETHER) {
      this.upper = this.replacement(Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE);
      this.lower = this.upper;
    } else if (level.dimension() == Level.END) {
      this.upper = this.replacement(Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.STONE);
      this.lower = this.upper;
    } else {
      this.upper = this.replacement(Blocks.STONE, Blocks.ANDESITE, Blocks.COBBLESTONE);
      this.lower = this.replacement(Blocks.DEEPSLATE, Blocks.TUFF, Blocks.COBBLED_DEEPSLATE);
    }
  }

  private BlockState replacement(Block preferred, Block second, Block third) {
    for (Block block : List.of(preferred, second, third, Blocks.BEDROCK, Blocks.GRANITE)) {
      if (!this.hidden.contains(block)) {
        return block.defaultBlockState();
      }
    }
    throw new IllegalArgumentException("No safe replacement for ExtendedHorizons Anti-Xray");
  }

  @Override
  public boolean test(BlockState state) {
    return this.hidden.contains(state.getBlock());
  }

  void apply(ServerLevel level, LevelChunkSection[] sections) {
    for (int index = 0; index < sections.length; index++) {
      LevelChunkSection section = sections[index];
      if (!section.maybeHas(this)) {
        continue;
      }
      BlockState host = index + level.getMinSectionY() < 0 ? this.lower : this.upper;
      var masked = new LevelChunkSection(level.palettedContainerFactory().createForBlockStates(), section.getBiomes().copy());
      if (!(section.getStates().data.palette() instanceof GlobalPalette<?>)) {
        this.remap(section.getStates(), masked.getStates(), host);
        masked.recalcBlockCounts();
        sections[index] = masked;
        continue;
      }
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            BlockState state = section.getBlockState(x, y, z);
            masked.setBlockState(x, y, z, this.test(state) ? host : state, false);
          }
        }
      }
      sections[index] = masked;
    }
  }

  private void remap(PalettedContainer<BlockState> source, PalettedContainer<BlockState> target, BlockState host) {
    var data = source.data;
    var palette = data.palette();
    int[] indices = INDICES.get();
    data.storage().unpack(indices);
    int[] remap = new int[palette.getSize()];
    Arrays.fill(remap, -1);
    int size = 0;
    for (int id : indices) {
      if (remap[id] == -1) {
        BlockState state = palette.valueFor(id);
        target.getAndSetUnchecked(size & 15, size >> 8, (size >> 4) & 15, this.test(state) ? host : state);
        remap[id] = size++;
      }
    }
    var storage = target.data.storage();
    for (int id = 0; id < remap.length; id++) {
      if (remap[id] >= 0) {
        remap[id] = storage.get(remap[id]);
      }
    }
    for (int index = 0; index < indices.length; index++) {
      storage.set(index, remap[indices[index]]);
    }
  }
}
