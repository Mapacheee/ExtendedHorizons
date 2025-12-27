package me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
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
                if (chunk != null && !(chunk instanceof EmptyLevelChunk)) {
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
        if (chunk instanceof CraftChunk) {
            return ((CraftChunk) chunk)
                    .getHandle(ChunkStatus.FULL);
        }
        return null;
    }

    @Override
    public Object cloneChunk(Object chunk) {
        if (!(chunk instanceof LevelChunk))
            return null;
        LevelChunk original = (LevelChunk) chunk;

        final LevelChunkSection[] originalSections = original.getSections();
        final LevelChunkSection[] newSections = new LevelChunkSection[originalSections.length];

        LevelChunk newChunk = new LevelChunk(original.getLevel(), original.getPos()) {
            @Override
            public Map<BlockPos, BlockEntity> getBlockEntities() {
                return original.getBlockEntities();
            }

            @Override
            public LevelChunkSection[] getSections() {
                return newSections;
            }
        };

        // Copy critical data
        newChunk.setInhabitedTime(original.getInhabitedTime());

        for (int i = 0; i < originalSections.length; i++) {
            LevelChunkSection oldSection = originalSections[i];
            if (oldSection != null && !oldSection.hasOnlyAir()) {
                try {
                    // get section Y using reflection if method is missing
                    Field bottomYField = LevelChunkSection.class.getDeclaredField("bottomBlockY");
                    bottomYField.setAccessible(true);
                    int bottomY = bottomYField.getInt(oldSection);
                    int sectionY = bottomY >> 4;
                    LevelChunkSection newSection = null;
                    Object biomeRegistryOrContainer = null;

                    for (Constructor<?> c : LevelChunkSection.class.getDeclaredConstructors()) {
                        if (c.getParameterCount() == 2 && c.getParameterTypes()[0] == int.class) {
                            c.setAccessible(true);
                            Class<?> argType = c.getParameterTypes()[1];

                            if (net.minecraft.core.Registry.class.isAssignableFrom(argType)) {
                                biomeRegistryOrContainer = original.getLevel().registryAccess()
                                        .lookupOrThrow(Registries.BIOME);
                                newSection = (LevelChunkSection) c.newInstance(sectionY, biomeRegistryOrContainer);
                                break;
                            } else if (PalettedContainer.class.isAssignableFrom(argType)) {
                                biomeRegistryOrContainer = oldSection.getBiomes(); // already a container
                                newSection = (LevelChunkSection) c.newInstance(sectionY, biomeRegistryOrContainer);
                                break;
                            }
                        }
                    }

                    if (newSection == null) {
                        throw new RuntimeException("Could not find suitable LevelChunkSection constructor");
                    }

                    // Reflective copy fields

                    // 1. Biomes
                    Field biomesField = LevelChunkSection.class.getDeclaredField("biomes");
                    biomesField.setAccessible(true);
                    biomesField.set(newSection, oldSection.getBiomes().copy());

                    // 2. States (BlockStates)
                    Field statesField = LevelChunkSection.class.getDeclaredField("states");
                    statesField.setAccessible(true);
                    statesField.set(newSection, oldSection.getStates().copy());

                    // 3. Non-empty block count
                    Field nonEmptyCountField = LevelChunkSection.class
                            .getDeclaredField("nonEmptyBlockCount");
                    nonEmptyCountField.setAccessible(true);
                    short count = (short) nonEmptyCountField.getShort(oldSection);
                    nonEmptyCountField.setShort(newSection, count);

                    // 4. Ticking block count
                    Field tickingCountField = LevelChunkSection.class
                            .getDeclaredField("tickingBlockCount");
                    tickingCountField.setAccessible(true);
                    tickingCountField.setShort(newSection, (short) tickingCountField.getShort(oldSection));

                    newSections[i] = newSection;

                } catch (Exception e) {
                    e.printStackTrace();
                    newSections[i] = oldSection;
                }
            }
        }

        return newChunk;
    }

    @Override
    public void obfuscateChunk(Object chunkObj, boolean hideOres, boolean addFakeOres, double density) {
        if (!(chunkObj instanceof LevelChunk))
            return;
        LevelChunk chunk = (LevelChunk) chunkObj;
        Random random = new Random();

        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir())
                continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        if (hideOres && isValuableOre(block)) {
                            section.setBlockState(x, y, z, getReplacement(block).defaultBlockState(), false);
                        } else if (addFakeOres && shouldFakeOre(block)) {
                            if (random.nextDouble() < density) {
                                section.setBlockState(x, y, z, getRandomOre(block, random).defaultBlockState(), false);
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
