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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class BulkChunkInvalidationService {

    private static final long COOLDOWN_NANOS = 2_000_000_000L;

    private final ChunkBuildCacheService cacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;
    private final ChunkDispatchService dispatchService;
    private final ConcurrentHashMap<UUID, Set<Long>> pendingInvalidations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> cooldownMap = new ConcurrentHashMap<>();

    private final List<Long> unloadBuffer = new ArrayList<>();

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
        this.cooldownMap.clear();
    }

    public void queueInvalidation(UUID worldId, long chunkKey) {
        if (worldId == null) return;
        long compositeKey = compositeKey(worldId, chunkKey);
        if (isOnCooldown(compositeKey)) return;
        this.pendingInvalidations.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet()).add(chunkKey);
    }

    public void queueInvalidationBatch(UUID worldId, Collection<Long> chunkKeys) {
        if (worldId == null || chunkKeys == null || chunkKeys.isEmpty()) return;
        Set<Long> set = this.pendingInvalidations.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet());
        for (Long key : chunkKeys) {
            long compositeKey = compositeKey(worldId, key);
            if (!isOnCooldown(compositeKey)) {
                set.add(key);
            }
        }
    }

    private void processPending() {
        if (this.pendingInvalidations.isEmpty()) {
            evictExpiredCooldowns();
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

            long[] keyArray = new long[keys.size()];
            int idx = 0;
            for (Long key : keys) {
                keyArray[idx++] = key;
            }
            final int count = idx;

            long now = System.nanoTime();
            for (int i = 0; i < count; i++) {
                long compositeKey = compositeKey(worldId, keyArray[i]);
                this.cooldownMap.put(compositeKey, now);
                this.cacheService.invalidate(worldId, keyArray[i]);
                this.antiXrayPayloadCacheService.invalidateChunk(worldId, keyArray[i]);
                this.lightPayloadCacheService.invalidate(worldId, keyArray[i]);
            }

            this.sessionRegistry.forEachSession(session -> {
                if (!worldId.equals(session.worldId())) {
                    return;
                }
                this.unloadBuffer.clear();
                for (int i = 0; i < count; i++) {
                    boolean wasLoaded = session.invalidateChunk(keyArray[i]);
                    if (wasLoaded) {
                        this.unloadBuffer.add(keyArray[i]);
                    }
                }
                if (this.unloadBuffer.isEmpty()) {
                    return;
                }
                Player player = Bukkit.getPlayer(session.playerId());
                if (player == null) {
                    return;
                }
                Channel channel = this.channelInjectionService.resolveChannel(player);
                if (channel == null || !channel.isActive()) {
                    return;
                }
                long[] toUnload = new long[this.unloadBuffer.size()];
                for (int i = 0; i < toUnload.length; i++) {
                    toUnload[i] = this.unloadBuffer.get(i);
                }
                this.channelInjectionService.executeOnEventLoop(channel, () -> {
                    for (long key : toUnload) {
                        this.dispatchService.sendUnload(channel, session, key);
                    }
                });
            });
        }

        evictExpiredCooldowns();
    }

    private boolean isOnCooldown(long compositeKey) {
        Long lastTime = this.cooldownMap.get(compositeKey);
        return lastTime != null && (System.nanoTime() - lastTime) < COOLDOWN_NANOS;
    }

    private void evictExpiredCooldowns() {
        long now = System.nanoTime();
        if (this.cooldownMap.size() > 10_000) {
            this.cooldownMap.entrySet().removeIf(e -> (now - e.getValue()) >= COOLDOWN_NANOS);
        }
    }

    private static long compositeKey(UUID worldId, long chunkKey) {
        return worldId.hashCode() * 31L + chunkKey;
    }
}

