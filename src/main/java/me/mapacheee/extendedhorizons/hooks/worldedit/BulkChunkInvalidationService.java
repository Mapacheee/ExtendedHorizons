package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.netty.channel.Channel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.LightPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public final class BulkChunkInvalidationService {

    private static final int MAX_INVALIDATIONS_PER_TICK = 256;

    private final ChunkBuildCacheService cacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;
    private final PendingChunkInvalidations pendingInvalidations = new PendingChunkInvalidations();

    private volatile ScheduledTask processorTask;
    private int refreshTicks;

    @Inject
    public BulkChunkInvalidationService(
        ChunkBuildCacheService cacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService,
        LightPayloadCacheService lightPayloadCacheService,
        SessionRegistry sessionRegistry,
        ChannelInjectionService channelInjectionService
    ) {
        this.cacheService = cacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
        this.lightPayloadCacheService = lightPayloadCacheService;
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
    }

    @OnEnable
    public void onEnable() {
        ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        this.processorTask = FoliaTaskUtil.runGlobalTimer(plugin, this::processPending, 1L, 1L);
    }

    @OnDisable
    public void onDisable() {
        if (this.processorTask != null) {
            this.processorTask.cancel();
            this.processorTask = null;
        }
        this.pendingInvalidations.clear();
    }

    public void queueInvalidation(UUID worldId, long chunkKey) {
        this.pendingInvalidations.offer(worldId, chunkKey, System.nanoTime());
    }

    public void queueInvalidationBatch(UUID worldId, Collection<Long> chunkKeys) {
        if (worldId == null || chunkKeys == null || chunkKeys.isEmpty()) return;
        for (Long key : chunkKeys) {
            if (key != null) this.queueInvalidation(worldId, key);
        }
    }

    private void processPending() {
        if (++this.refreshTicks >= 20) {
            this.refreshTicks = 0;
            this.queuePeriodicRefreshes();
        }
        for (Map.Entry<UUID, List<Long>> entry : this.pendingInvalidations
            .drain(System.nanoTime(), MAX_INVALIDATIONS_PER_TICK).entrySet()) {
            UUID worldId = entry.getKey();
            long[] keyArray = entry.getValue().stream().mapToLong(Long::longValue).toArray();
            final int count = keyArray.length;
            for (int i = 0; i < count; i++) {
                this.cacheService.invalidate(worldId, keyArray[i]);
                this.antiXrayPayloadCacheService.invalidateChunk(worldId, keyArray[i]);
                this.lightPayloadCacheService.invalidate(worldId, keyArray[i]);
            }

            this.sessionRegistry.forEachSession(session -> {
                if (!worldId.equals(session.worldId())) {
                    return;
                }
                long epoch = session.epoch();
                Player player = Bukkit.getPlayer(session.playerId());
                if (player == null) {
                    return;
                }
                Channel channel = this.channelInjectionService.resolveChannel(player);
                if (channel == null || !channel.isActive()) {
                    return;
                }
                this.refreshSession(channel, session, worldId, epoch, keyArray);
            });
        }

    }

    public void queueInvalidationWithNeighbors(UUID worldId, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                this.queueInvalidation(worldId, ChunkKeyCodec.pack(chunkX + dx, chunkZ + dz));
            }
        }
    }

    void refreshSession(Channel channel, PlayerSession session, UUID worldId, long epoch, long[] keys) {
        this.channelInjectionService.executeForSession(channel, session, worldId, epoch, () -> {
            for (long key : keys) session.requestChunkRefresh(key);
        });
    }

    private void queuePeriodicRefreshes() {
        this.sessionRegistry.forEachSession(session -> {
            if (!session.enabled() || session.closed()) return;
            UUID worldId = session.worldId();
            long epoch = session.epoch();
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null) return;
            Channel channel = this.channelInjectionService.resolveChannel(player);
            if (channel == null || !channel.isActive()) return;
            this.channelInjectionService.executeForSession(channel, session, worldId, epoch, () -> {
                if (!session.enabled()) return;
                Long key = session.pollChunkForRefresh(System.nanoTime());
                if (key != null) this.queueInvalidation(worldId, key);
            });
        });
    }
}

