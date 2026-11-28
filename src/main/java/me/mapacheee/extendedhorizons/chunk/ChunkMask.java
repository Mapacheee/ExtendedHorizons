package me.mapacheee.extendedhorizons.chunk;

import io.papermc.paper.configuration.type.EngineMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class ChunkMask {

  private static final ThreadLocal<int[]> INDICES = ThreadLocal.withInitial(() -> new int[4096]);

  private final Set<Block> hidden;
  private final Set<Block> hiddenBelowLimit;
  private final int nativeMaxSection;
  private final BlockState upper;
  private final BlockState lower;

  ChunkMask(ServerLevel level, Set<Block> hidden) {
    this.hidden = hidden;
    var config = level.paperConfig().anticheat.antiXray;
    this.nativeMaxSection = config.enabled ? config.maxBlockHeight >> 4 : level.getMinSectionY();
    var sensitive = new HashSet<>(hidden);
    if (this.nativeMaxSection > level.getMinSectionY()) {
      sensitive.addAll(config.hiddenBlocks);
    }
    Set<Block> targets = new HashSet<>(sensitive);
    if (this.nativeMaxSection > level.getMinSectionY() && config.engineMode != EngineMode.HIDE) {
      targets.addAll(config.replacementBlocks);
    }
    targets.removeIf(block -> block.defaultBlockState().isAir());
    this.hiddenBelowLimit = Set.copyOf(targets);
    if (level.dimensionType().hasCeiling() || level.dimension() == Level.NETHER) {
      this.upper = replacement(sensitive, Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE);
      this.lower = this.upper;
    } else if (level.dimension() == Level.END) {
      this.upper = replacement(sensitive, Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.STONE);
      this.lower = this.upper;
    } else {
      this.upper = replacement(sensitive, Blocks.STONE, Blocks.ANDESITE, Blocks.COBBLESTONE);
      this.lower = replacement(sensitive, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.COBBLED_DEEPSLATE);
    }
  }

  private static BlockState replacement(Set<Block> hidden, Block preferred, Block second, Block third) {
    for (Block block : List.of(preferred, second, third, Blocks.BEDROCK, Blocks.GRANITE)) {
      if (!hidden.contains(block)) {
        return block.defaultBlockState();
      }
    }
    throw new IllegalArgumentException("No safe replacement for ExtendedHorizons Anti-Xray");
  }

  boolean empty() {
    return this.hiddenBelowLimit.isEmpty();
  }

  private Set<Block> targets(int sectionY) {
    return sectionY < this.nativeMaxSection ? this.hiddenBelowLimit : this.hidden;
  }

  boolean test(BlockState state, int y) {
    return this.targets(y >> 4).contains(state.getBlock());
  }

  void apply(ServerLevel level, LevelChunkSection[] sections) {
    for (int index = 0; index < sections.length; index++) {
      LevelChunkSection section = sections[index];
      int sectionY = index + level.getMinSectionY();
      Set<Block> targets = this.targets(sectionY);
      if (targets.isEmpty()) {
        continue;
      }
      BlockState host = sectionY < 0 ? this.lower : this.upper;
      Predicate<BlockState> replace = state -> state != host && targets.contains(state.getBlock());
      if (!section.maybeHas(replace)) {
        continue;
      }
      var masked = new LevelChunkSection(level.palettedContainerFactory().createForBlockStates(), section.getBiomes().copy());
      if (!(section.getStates().data.palette() instanceof GlobalPalette<?>)) {
        this.remap(section.getStates(), masked.getStates(), host, replace);
        masked.recalcBlockCounts();
        sections[index] = masked;
        continue;
      }
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            BlockState state = section.getBlockState(x, y, z);
            masked.setBlockState(x, y, z, replace.test(state) ? host : state, false);
          }
        }
      }
      sections[index] = masked;
    }
  }

  private void remap(PalettedContainer<BlockState> source, PalettedContainer<BlockState> target, BlockState host,
    Predicate<BlockState> replace) {
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
        target.getAndSetUnchecked(size & 15, size >> 8, (size >> 4) & 15, replace.test(state) ? host : state);
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
