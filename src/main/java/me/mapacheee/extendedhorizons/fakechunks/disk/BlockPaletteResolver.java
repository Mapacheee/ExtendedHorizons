package me.mapacheee.extendedhorizons.fakechunks.disk;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.block.CraftBiome;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves block state names (from NBT palette) to their global network IDs.
 *
 * Uses only NMS classes that work via PaperPluginClassLoader:
 *   - Block / BlockState / BLOCK_STATE_REGISTRY (same as AntiXrayService)
 *   - CraftMagicNumbers (block name lookup)
 *   - CraftBiome (biome ID lookup)
 *
 * The lookup table is built once on a Bukkit async thread via
 * {@link #scheduleBuildAsync()} and must be triggered during plugin startup.
 * After initialization all resolve methods are pure Map lookups.
 */
public final class BlockPaletteResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockPaletteResolver.class);
    private static final Map<String, Integer> BLOCK_STATE_MAP = new ConcurrentHashMap<>(32768);
    private static final Map<String, Integer> BIOME_MAP = new ConcurrentHashMap<>(128);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean BUILD_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicInteger CACHED_TOTAL_BLOCK_STATES = new AtomicInteger(16384);
    private static final AtomicInteger CACHED_TOTAL_BIOMES = new AtomicInteger(64);
    private static final AtomicInteger CACHED_AIR_ID = new AtomicInteger(0);

    private BlockPaletteResolver() {}

    /** Returns true if the lookup table has been fully built. */
    public static boolean isInitialized() {
        return INITIALIZED.get();
    }

    /**
     * Schedules an async Bukkit task to build the lookup tables.
     * Safe to call from any thread. No-ops if already scheduled.
     */
    public static void scheduleBuildAsync() {
        if (BUILD_SCHEDULED.getAndSet(true)) {
            return;
        }
        LOGGER.info("Scheduling BlockPaletteResolver async initialization...");
        Bukkit.getScheduler().runTaskAsynchronously(
            getPlugin(),
            BlockPaletteResolver::buildLookupTable
        );
    }

    /**
     * Builds the complete block state and biome lookup tables synchronously.
     * Can be called from any Bukkit thread (sync or async).
     */
    private static void buildLookupTable() {
        if (INITIALIZED.get()) {
            return;
        }

        long start = System.nanoTime();
        int blockStateCount = 0;
        int blockCount = 0;

        for (Material material : Material.values()) {
            if (!material.isBlock() || material.isLegacy()) {
                continue;
            }

            Block nmsBlock;
            try {
                nmsBlock = CraftMagicNumbers.getBlock(material);
            } catch (Throwable t) {
                LOGGER.debug("Could not get NMS block for {}: {}", material, t.getMessage());
                continue;
            }
            if (nmsBlock == null) {
                continue;
            }

            blockCount++;
            for (BlockState state : nmsBlock.getStateDefinition().getPossibleStates()) {
                String key = buildBlockStateKey(material, state);
                int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                BLOCK_STATE_MAP.put(key, id);
                blockStateCount++;
            }
        }

        try {
            CACHED_AIR_ID.set(
                Block.BLOCK_STATE_REGISTRY.getId(
                    Blocks.AIR.defaultBlockState()
                )
            );
            CACHED_TOTAL_BLOCK_STATES.set(Block.BLOCK_STATE_REGISTRY.size());
        } catch (Throwable t) {
            LOGGER.error("Failed to cache air / total block states: {}", t.getMessage(), t);
        }


        int biomeCount = 0;
        try {
            MinecraftServer server = MinecraftServer.getServer();
          Registry<Biome> biomeRegistry =
            server.registryAccess().lookupOrThrow(Registries.BIOME);
          CACHED_TOTAL_BIOMES.set(biomeRegistry.size());

          for (org.bukkit.block.Biome biome : org.bukkit.Registry.BIOME) {
              try {
                  String key = biome.getKey().toString();
                  Holder<Biome> holder =
                      CraftBiome.bukkitToMinecraftHolder(biome);
                  int id = biomeRegistry.getId(holder.value());
                  if (id >= 0) {
                      BIOME_MAP.put(key, id);
                      biomeCount++;
                  } else {
                      LOGGER.debug("Biome '{}' not found in registry", key);
                  }
              } catch (Throwable t) {
                  LOGGER.debug("Could not resolve biome {}: {}", biome, t.getMessage());
              }
          }
        } catch (Throwable t) {
            LOGGER.error("Failed to build biome lookup table: {}", t.getMessage(), t);
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        INITIALIZED.set(true);
        LOGGER.info(
            "BlockPaletteResolver ready in {}ms: {} blocks, {} block states, {} biomes, air ID={}",
            elapsed, blockCount, blockStateCount, biomeCount, CACHED_AIR_ID.get()
        );
    }

    /**
     * Builds the NBT palette key for a block state.
     * Format: "minecraft:stone" or "minecraft:oak_stairs[facing=north,half=bottom,...]"
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String buildBlockStateKey(Material material, BlockState state) {
        String blockName = material.getKey().toString();

        var properties = state.getProperties();
        if (properties.isEmpty()) {
            return blockName;
        }

        StringBuilder sb = new StringBuilder(blockName);
        sb.append('[');
        boolean first = true;
        for (Property<?> prop : properties) {
            if (!first) sb.append(',');
            sb.append(prop.getName())
              .append('=')
              .append(((Property) prop).getName(state.getValue(prop)));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Resolves a block state name (e.g. "minecraft:oak_stairs[facing=north,...]")
     * to its global network ID. Returns air ID for unknown states.
     */
    public static int resolveBlockStateId(String qualifiedName) {
        Integer id = BLOCK_STATE_MAP.get(qualifiedName);
        if (id != null) {
            return sanitizeBlockStateId(id);
        }

        int bracket = qualifiedName.indexOf('[');
        if (bracket > 0) {
            Integer defaultId = BLOCK_STATE_MAP.get(qualifiedName.substring(0, bracket));
            if (defaultId != null) {
                int sanitized = sanitizeBlockStateId(defaultId);
                BLOCK_STATE_MAP.put(qualifiedName, sanitized);
                return sanitized;
            }
        }

        LOGGER.debug("Unknown block state: '{}', falling back to air", qualifiedName);
        return CACHED_AIR_ID.get();
    }

    /**
     * Resolves a biome name (e.g. "minecraft:plains") to its registry ID.
     * Returns 0 (plains) for unknown biomes.
     */
    public static int resolveBiomeId(String biomeName) {
        Integer id = BIOME_MAP.get(biomeName);
        if (id != null) {
            return sanitizeBiomeId(id);
        }
        LOGGER.debug("Unknown biome: '{}', falling back to plains (0)", biomeName);
        return 0;
    }

    private static int sanitizeBlockStateId(int id) {
        int max = CACHED_TOTAL_BLOCK_STATES.get();
        if (id < 0 || (max > 0 && id >= max)) {
            return CACHED_AIR_ID.get();
        }
        return id;
    }

    private static int sanitizeBiomeId(int id) {
        int max = CACHED_TOTAL_BIOMES.get();
        if (id < 0 || (max > 0 && id >= max)) {
            return 0;
        }
        return id;
    }

    /** Total registered block states (for bits-per-entry calculation). */
    public static int totalBlockStates() {
        return CACHED_TOTAL_BLOCK_STATES.get();
    }

    /** Total registered biomes (for bits-per-entry calculation). */
    public static int totalBiomes() {
        return CACHED_TOTAL_BIOMES.get();
    }

    /** Clears all caches (for hot-reload). */
    public static void clearCaches() {
        BLOCK_STATE_MAP.clear();
        BIOME_MAP.clear();
        INITIALIZED.set(false);
        BUILD_SCHEDULED.set(false);
        CACHED_TOTAL_BLOCK_STATES.set(16384);
        CACHED_TOTAL_BIOMES.set(64);
        CACHED_AIR_ID.set(0);
    }

    /** Returns the plugin instance needed to schedule Bukkit tasks. */
    private static Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("ExtendedHorizons");
    }
}
