package me.mapacheee.extendedhorizons.api;

import com.google.inject.Inject;
import me.mapacheee.extendedhorizons.api.event.FakeChunkBatchLoadEvent.ChunkCoordinate;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import me.mapacheee.extendedhorizons.viewdistance.service.ChunkLoaderService;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
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
    private final ChunkLoaderService chunkLoaderService;

    @Inject
    public ExtendedHorizonsAPIImpl(FakeChunkService fakeChunkService,
            ViewDistanceService viewDistanceService,
            ChunkLoaderService chunkLoaderService) {
        this.fakeChunkService = fakeChunkService;
        this.viewDistanceService = viewDistanceService;
        this.chunkLoaderService = chunkLoaderService;
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

        player.getScheduler().runDelayed(
                ExtendedHorizonsPlugin.getInstance(),
                (task) -> {
                    if (player.isOnline()) {
                        viewDistanceService.updatePlayerView(player);
                    }
                },
                null, 5L);
    }

    @Override
    public int getCacheSize() {
        return chunkLoaderService.getPacketCacheSize();
    }

    @Override
    public double getCacheHitRate() {
        return chunkLoaderService.getCacheHitRate();
    }

    @Override
    public double getEstimatedMemoryUsageMB() {
        return chunkLoaderService.getEstimatedMemoryUsageMB();
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
