package me.mapacheee.extendedhorizons.api;

import com.google.inject.Inject;
import me.mapacheee.extendedhorizons.api.event.FakeChunkBatchLoadEvent.ChunkCoordinate;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of the ExtendedHorizons public API.
 * This service is registered as a singleton and can be accessed by other
 * plugins via APIModule's @Provides binding.
 */
public class ExtendedHorizonsAPIImpl implements ExtendedHorizonsAPI {

    private final FakeChunkService fakeChunkService;
    private final ViewDistanceService viewDistanceService;

    @Inject
    public ExtendedHorizonsAPIImpl(FakeChunkService fakeChunkService,
            ViewDistanceService viewDistanceService) {
        this.fakeChunkService = fakeChunkService;
        this.viewDistanceService = viewDistanceService;
    }

    @Override
    @NotNull
    public Set<ChunkCoordinate> getFakeChunksForPlayer(@NotNull Player player) {
        Set<Long> chunkKeys = fakeChunkService.getFakeChunksForPlayer(player.getUniqueId());
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return Collections.emptySet();
        }

        return chunkKeys.stream()
                .map(key -> new ChunkCoordinate(
                        ChunkUtils.unpackX(key),
                        ChunkUtils.unpackZ(key)))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isFakeChunk(@NotNull Player player, int chunkX, int chunkZ) {
        long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);
        return fakeChunkService.isFakeChunk(player.getUniqueId(), chunkKey);
    }

    @Override
    public int getFakeChunkCount(@NotNull Player player) {
        return fakeChunkService.getFakeChunkCount(player.getUniqueId());
    }

    @Override
    public void clearFakeChunks(@NotNull Player player) {
        fakeChunkService.clearPlayerFakeChunks(player, true);
    }

    @Override
    public void refreshFakeChunks(@NotNull Player player) {
        fakeChunkService.clearPlayerFakeChunks(player, true);

        Bukkit.getScheduler().runTaskLater(
                me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getInstance(),
                () -> {
                    if (player.isOnline()) {
                        viewDistanceService.updatePlayerView(player);
                    }
                },
                5L);
    }

    @Override
    public int getCacheSize() {
        return fakeChunkService.getCacheSize();
    }

    @Override
    public double getCacheHitRate() {
        return fakeChunkService.getCacheHitRate();
    }

    @Override
    public double getEstimatedMemoryUsageMB() {
        return fakeChunkService.getEstimatedMemoryUsageMB();
    }

    @Override
    public boolean isFakeChunksEnabledForWorld(@NotNull String worldName) {
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        return fakeChunkService.isFakeChunksEnabledForWorld(world);
    }
}
