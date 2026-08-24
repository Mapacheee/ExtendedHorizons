package me.mapacheee.extendedhorizons.fakechunks.antixray;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.config.EhConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class AntiXrayService {

    private static final String MINECRAFT_PREFIX = "MINECRAFT:";
    private static final int MAX_CACHED_PROFILES = 32;

    private final Container<EhConfig> configContainer;
    private final Map<UUID, CachedProfile> cachedProfiles = new ConcurrentHashMap<>();

    @Inject
    public AntiXrayService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
    }

    public AntiXrayProcessor resolve(World world) {
        if (world == null) {
            return null;
        }

        EhConfig config = this.configContainer.get();
        String worldName = world.getName();
        boolean enabled = config.antiXrayEnabled(worldName);
        UUID worldId = world.getUID();
        if (!enabled) {
            this.cachedProfiles.remove(worldId);
            return null;
        }

        List<String> hiddenBlocks = config.antiXrayHiddenBlocks(worldName);
        CachedProfile cached = this.cachedProfiles.get(worldId);
        if (cached != null && cached.config() == config && cached.hiddenBlocks() == hiddenBlocks) {
            return cached.processor();
        }

        AntiXrayProcessor processor = this.createProcessor(world, hiddenBlocks);
        if (processor == null) {
            this.cachedProfiles.remove(worldId);
            return null;
        }
        this.cachedProfiles.put(worldId, new CachedProfile(config, hiddenBlocks, processor));
        return processor;
    }

    private AntiXrayProcessor createProcessor(World world, List<String> hiddenBlocks) {
        int[] hiddenStates = this.resolveHiddenStates(hiddenBlocks);
        if (hiddenStates.length == 0) {
            return null;
        }

        int[] above;
        int[] below;
        switch (world.getEnvironment()) {
            case NETHER -> {
                int netherrack = Block.BLOCK_STATE_REGISTRY.getId(Blocks.NETHERRACK.defaultBlockState());
                above = new int[]{netherrack};
                below = above;
            }
            case THE_END -> {
                int endStone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.END_STONE.defaultBlockState());
                above = new int[]{endStone};
                below = above;
            }
            default -> {
                above = new int[]{Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState())};
                below = new int[]{Block.BLOCK_STATE_REGISTRY.getId(Blocks.DEEPSLATE.defaultBlockState())};
            }
        }

        ReplacementPresets presets = ReplacementPresets.createStaticZeroSplit(above, below);
        return new AntiXrayProcessor(ReplacementStrategy.STATIC_ZERO, presets, hiddenStates, Block.BLOCK_STATE_REGISTRY.size());
    }

    private int[] resolveHiddenStates(List<String> hiddenBlocks) {
        if (hiddenBlocks == null || hiddenBlocks.isEmpty()) {
            return new int[0];
        }
        LinkedHashSet<Integer> states = new LinkedHashSet<>();
        for (String key : hiddenBlocks) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Material material = this.parseMaterial(key.trim());
            if (material == null || !material.isBlock()) {
                continue;
            }
            Block block = CraftMagicNumbers.getBlock(material);
            if (block == null) {
                continue;
            }
            for (var state : block.getStateDefinition().getPossibleStates()) {
                if (state.getFluidState().isEmpty()) {
                    states.add(Block.BLOCK_STATE_REGISTRY.getId(state));
                }
            }
        }
        return states.stream().mapToInt(Integer::intValue).toArray();
    }

    private Material parseMaterial(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        if (normalized.startsWith(MINECRAFT_PREFIX)) {
            normalized = normalized.substring(MINECRAFT_PREFIX.length());
        }
        return Material.matchMaterial(normalized, false);
    }

    private record CachedProfile(EhConfig config, List<String> hiddenBlocks, AntiXrayProcessor processor) { }

    public void invalidateAllProfiles() {
        this.cachedProfiles.clear();
    }
}



