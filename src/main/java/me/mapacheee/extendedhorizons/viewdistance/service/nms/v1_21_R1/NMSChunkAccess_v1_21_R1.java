package me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Random;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import com.thewinterframework.service.annotation.Service;

@Service
public class NMSChunkAccess_v1_21_R1 implements NMSChunkAccess {

    @Override
    public Object getChunkIfLoaded(World world, int x, int z) {
        try {
            ServerLevel serverLevel = ((CraftWorld) world).getHandle();
            long chunkKey = ChunkPos.asLong(x, z);

            ChunkHolder chunkHolder = serverLevel.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunkKey);

            if (chunkHolder != null) {
                LevelChunk chunk = chunkHolder.getFullChunkNow();
                if (!(chunk instanceof EmptyLevelChunk)) {
                    return chunk;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public boolean isChunkLoaded(Object chunk) {
        return chunk instanceof LevelChunk && !((LevelChunk) chunk).isEmpty();
    }

    @Override
    public Object getNMSChunk(Chunk chunk) {
        if (!(chunk instanceof CraftChunk craftChunk)) {
            return null;
        }

        try {
            var chunkAccess = craftChunk.getHandle(ChunkStatus.FULL);
            if (chunkAccess instanceof LevelChunk levelChunk && !(levelChunk instanceof EmptyLevelChunk)) {
                return levelChunk;
            }

            ServerLevel serverLevel = craftChunk.getCraftWorld().getHandle();
            ChunkPos chunkPos = new ChunkPos(chunk.getX(), chunk.getZ());
            long chunkKey = chunkPos.toLong();

            ChunkHolder chunkHolder = serverLevel.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunkKey);
            if (chunkHolder != null) {
                LevelChunk fullChunk = chunkHolder.getFullChunkNow();
                if (!(fullChunk instanceof EmptyLevelChunk)) {
                    return fullChunk;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    @Override
    public Object cloneChunk(Object chunk) {
        if (!(chunk instanceof LevelChunk original)) {
            return null;
        }

        LevelChunk newChunk = new LevelChunk(original.getLevel(), original.getPos());
        newChunk.setInhabitedTime(original.getInhabitedTime());

        final LevelChunkSection[] originalSections = original.getSections();
        final LevelChunkSection[] newSections = newChunk.getSections();

        for (int i = 0; i < originalSections.length && i < newSections.length; i++) {
            LevelChunkSection oldSection = originalSections[i];

            if (oldSection != null && !oldSection.hasOnlyAir()) {
                newSections[i] = oldSection.copy();
            }
        }

        return newChunk;
    }

    @Override
    public void obfuscateChunk(Object chunkObj, boolean hideOres, boolean addFakeOres, double density) {
        if (!(chunkObj instanceof LevelChunk chunk))
            return;

        if (!hideOres && !addFakeOres)
            return;

        Random random = new Random();

        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir())
                continue;

            BlockState stone = null;
            BlockState deepslate = null;
            BlockState netherrack = null;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        if (hideOres) {
                            if (isValuableOre(block)) {
                                Block replacement = getReplacement(block);
                                BlockState replacementState;

                                if (replacement == Blocks.STONE) {
                                    if (stone == null) stone = Blocks.STONE.defaultBlockState();
                                    replacementState = stone;
                                } else if (replacement == Blocks.DEEPSLATE) {
                                    if (deepslate == null) deepslate = Blocks.DEEPSLATE.defaultBlockState();
                                    replacementState = deepslate;
                                } else {
                                    if (netherrack == null) netherrack = Blocks.NETHERRACK.defaultBlockState();
                                    replacementState = netherrack;
                                }

                                section.setBlockState(x, y, z, replacementState, false);
                                continue;
                            }
                        }

                        if (addFakeOres) {
                            if (shouldFakeOre(block) && random.nextDouble() < density) {
                                Block fakeOre = getRandomOre(block, random);
                                section.setBlockState(x, y, z, fakeOre.defaultBlockState(), false);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isValuableOre(Block block) {
        return block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE ||
                block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE ||
                block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE ||
                block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE ||
                block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE ||
                block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE ||
                block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE ||
                block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE ||
                block == Blocks.NETHER_QUARTZ_ORE || block == Blocks.NETHER_GOLD_ORE ||
                block == Blocks.ANCIENT_DEBRIS;
    }

    private boolean shouldFakeOre(Block block) {
        return block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.NETHERRACK
                || block == Blocks.END_STONE;
    }

    private Block getReplacement(Block block) {
        if (block == Blocks.DEEPSLATE_DIAMOND_ORE || block == Blocks.DEEPSLATE_GOLD_ORE ||
                block == Blocks.DEEPSLATE_IRON_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE ||
                block == Blocks.DEEPSLATE_COPPER_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE ||
                block == Blocks.DEEPSLATE_REDSTONE_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return Blocks.DEEPSLATE;
        } else if (block == Blocks.NETHER_QUARTZ_ORE || block == Blocks.NETHER_GOLD_ORE
                || block == Blocks.ANCIENT_DEBRIS) {
            return Blocks.NETHERRACK;
        } else {
            return Blocks.STONE;
        }
    }

    private Block getRandomOre(Block context, Random random) {
        if (context == Blocks.NETHERRACK) {
            return random.nextBoolean() ? Blocks.NETHER_QUARTZ_ORE : Blocks.NETHER_GOLD_ORE;
        } else if (context == Blocks.DEEPSLATE) {
            double r = random.nextDouble();
            if (r < 0.1)
                return Blocks.DEEPSLATE_DIAMOND_ORE;
            if (r < 0.3)
                return Blocks.DEEPSLATE_GOLD_ORE;
            if (r < 0.5)
                return Blocks.DEEPSLATE_IRON_ORE;
            return Blocks.DEEPSLATE_REDSTONE_ORE;
        } else {
            double r = random.nextDouble();
            if (r < 0.1)
                return Blocks.DIAMOND_ORE;
            if (r < 0.3)
                return Blocks.GOLD_ORE;
            if (r < 0.5)
                return Blocks.IRON_ORE;
            return Blocks.COAL_ORE;
        }
    }
}
