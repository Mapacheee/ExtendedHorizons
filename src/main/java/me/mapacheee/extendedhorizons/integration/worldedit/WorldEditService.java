package me.mapacheee.extendedhorizons.integration.worldedit;

import com.google.inject.Inject;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class WorldEditService {

    private final FakeChunkService fakeChunkService;
    private final ExtendedHorizonsPlugin plugin;
    private boolean enabled = false;

    private final Map<UUID, Set<Long>> modifiedChunks = new ConcurrentHashMap<>();

    @Inject
    public WorldEditService(FakeChunkService fakeChunkService, ExtendedHorizonsPlugin plugin) {
        this.fakeChunkService = fakeChunkService;
        this.plugin = plugin;
    }

    @OnEnable
    public void onEnable() {
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            try {
                WorldEdit.getInstance().getEventBus().register(this);
                enabled = true;
                startFlushTask();
            } catch (Throwable t) {
                plugin.getLogger().warning("[EH] Failed to register WorldEdit listener: " + t.getMessage());
            }
        }
    }

    @OnDisable
    public void onDisable() {
        if (enabled) {
            try {
                WorldEdit.getInstance().getEventBus().unregister(this);
            } catch (Throwable ignored) {
            }
        }
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (!enabled || event.getWorld() == null)
            return;

        World world = BukkitAdapter.adapt(event.getWorld());
        if (world == null)
            return;

        event.setExtent(new ChunkTrackingExtent(event.getExtent(), world.getUID()));
    }

    private void startFlushTask() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, (task) -> {
            if (modifiedChunks.isEmpty())
                return;

            for (UUID worldId : new HashSet<>(modifiedChunks.keySet())) {
                Set<Long> keys = modifiedChunks.remove(worldId);
                if (keys != null && !keys.isEmpty()) {
                    World world = Bukkit.getWorld(worldId);
                    if (world != null) {
                        fakeChunkService.refreshChunks(world, keys);
                    }
                }
            }
        }, 500L, 500L, TimeUnit.MILLISECONDS);
    }

    private class ChunkTrackingExtent extends AbstractDelegateExtent {
        private final UUID worldId;

        public ChunkTrackingExtent(Extent extent, UUID worldId) {
            super(extent);
            this.worldId = worldId;
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position,
                B block) throws WorldEditException {
            boolean result = super.setBlock(position, block);
            if (result) {
                recordChange(position);
            }
            return result;
        }

        private void recordChange(BlockVector3 pos) {
            int chunkX = pos.getBlockX() >> 4;
            int chunkZ = pos.getBlockZ() >> 4;
            long key = ChunkUtils.packChunkKey(chunkX, chunkZ);

            modifiedChunks.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }
}
