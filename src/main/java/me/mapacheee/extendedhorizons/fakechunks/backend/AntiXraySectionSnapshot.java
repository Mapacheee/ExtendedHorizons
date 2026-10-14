package me.mapacheee.extendedhorizons.fakechunks.backend;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;

record AntiXraySectionSnapshot(
  int sectionY,
  short nonEmptyBlockCount,
  short fluidCount,
  PalettedContainerRO<BlockState> states,
  PalettedContainerRO<Holder<Biome>> biomes
) {
}


