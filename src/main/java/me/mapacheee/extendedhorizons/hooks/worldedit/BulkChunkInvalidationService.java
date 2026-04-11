package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class BulkChunkInvalidationService {

    private final ChunkBuildCacheService cacheService;
    private final SessionRegistry sessionRegistry;
    private final ConcurrentHashMap<UUID, Set<Long>> pendingInvalidations = new ConcurrentHashMap<>();
    
    private volatile ScheduledTask processorTask;

    @Inject
    public BulkChunkInvalidationService(ChunkBuildCacheService cacheService, SessionRegistry sessionRegistry) {
        this.cacheService = cacheService;
        this.sessionRegistry = sessionRegistry;
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
            }

            this.sessionRegistry.forEachSession(session -> {
                if (worldId.equals(session.worldId())) {
                    for (Long chunkKey : keys) {
                        session.invalidateChunk(chunkKey);
                    }
                }
            });
        }
    }
}
