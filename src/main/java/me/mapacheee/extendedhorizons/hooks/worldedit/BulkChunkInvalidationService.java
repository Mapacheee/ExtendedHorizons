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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class BulkChunkInvalidationService {

    private final ChunkBuildCacheService cacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;
    private final ChunkDispatchService dispatchService;
    private final ConcurrentHashMap<UUID, Set<Long>> pendingInvalidations = new ConcurrentHashMap<>();

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
        if (worldId == null) return;
        this.pendingInvalidations.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet()).add(chunkKey);
    }

    private void processPending() {
        if (this.pendingInvalidations.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, Set<Long>>> iterator = this.pendingInvalidations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Set<Long>> entry = iterator.next();
            UUID worldId = entry.getKey();
            Set<Long> keys = entry.getValue();

            iterator.remove();

            if (keys.isEmpty()) {
                continue;
            }

            for (Long chunkKey : keys) {
                this.cacheService.invalidate(worldId, chunkKey);
                this.antiXrayPayloadCacheService.invalidateChunk(worldId, chunkKey);
                this.lightPayloadCacheService.invalidate(worldId, chunkKey);
            }

            this.sessionRegistry.forEachSession(session -> {
                if (!worldId.equals(session.worldId())) {
                    return;
                }
                List<Long> unloadKeys = null;
                for (Long chunkKey : keys) {
                    boolean wasLoaded = session.invalidateChunk(chunkKey);
                    if (wasLoaded) {
                        if (unloadKeys == null) {
                            unloadKeys = new ArrayList<>();
                        }
                        unloadKeys.add(chunkKey);
                    }
                }
                if (unloadKeys != null && !unloadKeys.isEmpty()) {
                    Player player = Bukkit.getPlayer(session.playerId());
                    if (player == null) {
                        return;
                    }
                    Channel channel = this.channelInjectionService.resolveChannel(player);
                    if (channel == null || !channel.isActive()) {
                        return;
                    }
                    List<Long> toUnload = unloadKeys;
                    this.channelInjectionService.executeOnEventLoop(channel, () -> {
                        for (long key : toUnload) {
                            this.dispatchService.sendUnload(channel, session, key);
                        }
                    });
                }
            });
        }
    }
}
