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
import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkDispatchService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
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
    private final ChunkDispatchService dispatchService;
    private final PendingChunkInvalidations pendingInvalidations = new PendingChunkInvalidations();

    private volatile ScheduledTask processorTask;

    @Inject
    public BulkChunkInvalidationService(
        ChunkBuildCacheService cacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService,
        LightPayloadCacheService lightPayloadCacheService,
        SessionRegistry sessionRegistry,
        ChannelInjectionService channelInjectionService,
        ChunkDispatchService dispatchService
    ) {
        this.cacheService = cacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
        this.lightPayloadCacheService = lightPayloadCacheService;
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
        this.dispatchService = dispatchService;
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
                this.channelInjectionService.executeForSession(channel, session, worldId, epoch, () -> {
                    for (int i = 0; i < count; i++) {
                        long key = keyArray[i];
                        if (session.invalidateChunk(key)) {
                            this.dispatchService.sendUnload(channel, session, key);
                        }
                    }
                    this.channelInjectionService.flush(channel);
                });
            });
        }

    }
}

