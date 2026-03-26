package me.mapacheee.extendedhorizons.chunk;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.viewdistance.PlayerDistancePreferenceService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class FakeChunkService {

    private static final Logger logger = LoggerFactory.getLogger(FakeChunkService.class);
    private final Container<Config> configContainer;
    private final PlayerDistancePreferenceService playerDistancePreferenceService;
    private final ChunkPacketCacheService chunkPacketCacheService;
    private final Map<UUID, PlayerChunkTracker> trackers = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile ScheduledTask keepAliveTask;
    private final Map<UUID, Deque<Long>> pendingQueues = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> queuedSets = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> inflightCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> inflightKeys = new ConcurrentHashMap<>();
    private final Map<String, Long> lastDebugLogMs = new ConcurrentHashMap<>();
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSentRadius = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSentSimulationDistance = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastForcedPlanMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAutoRefreshMs = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> autoRefreshCursor = new ConcurrentHashMap<>();
    private final Map<ChunkPacketCacheService.ChunkKey, Set<UUID>> fakeChunkSubscribers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<ChunkPacketCacheService.ChunkKey>> playerFakeChunkIndex = new ConcurrentHashMap<>();
    private final Map<ChunkPacketCacheService.ChunkKey, Long> dirtyFakeChunks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastKnownWorldId = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionEpoch = new ConcurrentHashMap<>();
    private final AtomicLong chunkLoadSamples = new AtomicLong();
    private final AtomicLong chunkLoadTotalMs = new AtomicLong();
    private final AtomicLong chunkLoadOver100Ms = new AtomicLong();
    
    @Inject
    public FakeChunkService(
            Container<Config> configContainer,
            PlayerDistancePreferenceService playerDistancePreferenceService,
            ChunkPacketCacheService chunkPacketCacheService
    ) {
        this.configContainer = configContainer;
        this.playerDistancePreferenceService = playerDistancePreferenceService;
        this.chunkPacketCacheService = chunkPacketCacheService;
    }

    @OnEnable
    public void onEnable() {
        enabled.set(true);
        chunkPacketCacheService.rebuildCaches();
        for (Player player : Bukkit.getOnlinePlayers()) {
            trackers.put(player.getUniqueId(), new PlayerChunkTracker());
            ensureClientCacheRadius(player);
            handleMove(player);
        }

        runGlobal(() -> {
            if (keepAliveTask != null) {
                keepAliveTask.cancel();
                keepAliveTask = null;
            }
            var plugin = ExtendedHorizonsPlugin.getInstance();
            int initialDelay = Math.max(1, config().keepAliveInitialDelayTicks());
            int period = Math.max(1, config().keepAlivePeriodTicks());
            keepAliveTask = Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(
                    plugin,
                    task -> {
                        if (!enabled.get()) return;
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            ensureClientCacheRadius(player);
                            runForPlayer(player, () -> {
                                if (!enabled.get()) return;
                                if (!player.isOnline()) return;
                                int chunkX = player.getLocation().getBlockX() >> 4;
                                int chunkZ = player.getLocation().getBlockZ() >> 4;
                                UUID playerId = player.getUniqueId();
                                World world = player.getWorld();
                                lastKnownWorldId.putIfAbsent(playerId, world.getUID());

                                sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));

                                PlayerChunkTracker tracker = trackers.get(playerId);
                                if (tracker != null) {
                                    long now = System.currentTimeMillis();
                                    Long lastPlan = lastForcedPlanMs.get(playerId);
                                    if (lastPlan == null || now - lastPlan >= config().forcePlanIntervalMs()) {
                                        updatePlayerChunks(player, playerId, world, chunkX, chunkZ, true);
                                        lastForcedPlanMs.put(playerId, now);
                                    }
                                }

                                Deque<Long> queue = pendingQueues.get(playerId);
                                if (queue != null && !queue.isEmpty()) {
                                    if (tracker != null) {
                                        try {
                                            processQueue(player, world, playerId, chunkX, chunkZ, tracker);
                                        } catch (Throwable t) {
                                            logger.error("processQueue failed for {}", player.getName(), t);
                                        }
                                    }
                                }
                                if (tracker != null) {
                                    maybeAutoRefreshSentChunks(player, world, playerId, tracker);
                                }
                            });
                        }
                    },
                    initialDelay,
                    period
            );
        });
    }

    @OnDisable
    public void onDisable() {
        enabled.set(false);
        trackers.clear();
        if (keepAliveTask != null) {
            try {
                keepAliveTask.cancel();
            } catch (Throwable ignored) {
            }
            keepAliveTask = null;
        }
        pendingQueues.clear();
        queuedSets.clear();
        inflightCounts.clear();
        inflightKeys.clear();
        lastSentRadius.clear();
        lastSentSimulationDistance.clear();
        lastForcedPlanMs.clear();
        lastAutoRefreshMs.clear();
        autoRefreshCursor.clear();
        fakeChunkSubscribers.clear();
        playerFakeChunkIndex.clear();
        dirtyFakeChunks.clear();
        lastKnownWorldId.clear();
        sessionEpoch.clear();
    }

    private void scheduleWarmup(Player player) {
        if (player == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;

        try {
            player.getScheduler().runDelayed(plugin, task -> {
                if (!enabled.get()) return;
                if (!player.isOnline()) return;
                ensureClientCacheRadius(player);
                int chunkX = player.getLocation().getBlockX() >> 4;
                int chunkZ = player.getLocation().getBlockZ() >> 4;
                sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
                updatePlayerChunks(player, player.getUniqueId(), player.getWorld(), chunkX, chunkZ, true);
            }, null, config().warmupDelayTicks());
        } catch (Throwable ignored) {
        }
    }

    public void handleJoin(Player player) {
        UUID playerId = player.getUniqueId();
        bumpSessionEpoch(playerId);
        trackers.put(playerId, new PlayerChunkTracker());
        lastKnownWorldId.put(playerId, player.getWorld().getUID());
        ensureClientCacheRadius(player);
        handleMove(player);
        scheduleWarmup(player);
        debug(playerId, "join", "[EH] join world=" + player.getWorld().getName());
    }

    public void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        resetPlayerState(playerId, true);
        lastKnownWorldId.remove(playerId);
        cleanupPlayerDebugState(playerId);
        debug(playerId, "quit", "[EH] quit");
    }

    public void handleTeleport(Player player) {
        handleTeleport(player, null);
    }

    public void handleTeleport(Player player, Location target) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        resetPlayerState(playerId, true);
        World targetWorld = target == null ? player.getWorld() : target.getWorld();
        if (targetWorld == null) targetWorld = player.getWorld();
        lastKnownWorldId.put(playerId, targetWorld.getUID());
        ensureClientCacheRadius(player);
        handleMove(player);
        Location finalTarget = target;
        runForPlayerDelayed(player, () -> {
            if (!enabled.get()) return;
            if (!player.isOnline()) return;
            Location base = finalTarget != null ? finalTarget : player.getLocation();
            World world = base.getWorld();
            if (world == null) return;
            int chunkX = base.getBlockX() >> 4;
            int chunkZ = base.getBlockZ() >> 4;
            sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
            updatePlayerChunks(player, playerId, world, chunkX, chunkZ, true);
            world.getChunkAtAsync(chunkX, chunkZ, true);
        }, 1L);
        scheduleWarmup(player);
        debug(playerId, "tp", "[EH] teleport world=" + targetWorld.getName());
    }

    public void handleRealChunkInteraction(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        UUID worldId = world.getUID();
        ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
        dirtyFakeChunks.put(key, System.currentTimeMillis());
        chunkPacketCacheService.invalidate(worldId, chunkKey);
        Set<UUID> targets = new HashSet<>();
        Set<UUID> subscribers = fakeChunkSubscribers.get(key);
        if (subscribers != null && !subscribers.isEmpty()) {
            targets.addAll(new ArrayList<>(subscribers));
        }

        for (Map.Entry<UUID, PlayerChunkTracker> entry : trackers.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerChunkTracker tracker = entry.getValue();
            if (tracker == null) continue;
            if (!tracker.getSentChunks().contains(chunkKey)) continue;
            targets.add(playerId);
            addFakeChunkSubscription(playerId, worldId, chunkKey);
        }

        if (targets.isEmpty()) return;
        for (UUID playerId : targets) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                removeFakeChunkSubscription(playerId, worldId, chunkKey);
                continue;
            }
            if (!worldId.equals(player.getWorld().getUID())) {
                removeFakeChunkSubscription(playerId, worldId, chunkKey);
                continue;
            }
            PlayerChunkTracker tracker = trackers.get(playerId);
            if (tracker == null || !tracker.getSentChunks().contains(chunkKey)) {
                removeFakeChunkSubscription(playerId, worldId, chunkKey);
                continue;
            }
            runForPlayer(player, () -> refreshChunkForPlayer(player, world, tracker, chunkX, chunkZ, chunkKey));
        }
    }

    public void applyDistancePreference(Player player, int distance) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        playerDistancePreferenceService.set(playerId, distance);
        runForPlayer(player, () -> {
            if (!enabled.get()) return;
            if (!player.isOnline()) return;
            ensureClientCacheRadius(player);
            int chunkX = player.getLocation().getBlockX() >> 4;
            int chunkZ = player.getLocation().getBlockZ() >> 4;
            updatePlayerChunks(player, playerId, player.getWorld(), chunkX, chunkZ, true);
        });
    }

    public void handleMove(Player player) {
        if (!enabled.get()) return;
        runForPlayer(player, () -> {
            if (!enabled.get()) return;
            if (!player.isOnline()) return;

            int chunkX = player.getLocation().getBlockX() >> 4;
            int chunkZ = player.getLocation().getBlockZ() >> 4;
            World world = player.getWorld();
            UUID playerId = player.getUniqueId();
            UUID worldId = world.getUID();
            UUID lastWorldId = lastKnownWorldId.get(playerId);
            if (lastWorldId == null || !lastWorldId.equals(worldId)) {
                resetPlayerState(playerId, true);
                lastKnownWorldId.put(playerId, worldId);
                debug(playerId, "world_change", "[EH] world change detected to " + world.getName());
            }

            sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));

            try {
                updatePlayerChunks(player, playerId, world, chunkX, chunkZ, false);
            } catch (Throwable t) {
                logger.error("updatePlayerChunks failed for {}", player.getName(), t);
            }
        });
    }

    private void updatePlayerChunks(Player player, UUID playerId, World world, int chunkX, int chunkZ, boolean force) {
        if (!enabled.get()) return;

        PlayerChunkTracker tracker = trackers.get(playerId);
        if (tracker == null) return;

        boolean moved = tracker.hasMovedChunk(chunkX, chunkZ);
        if (!moved && !force) return;
        if (moved) tracker.updatePosition(chunkX, chunkZ);

        int viewDistance = getTargetDistance(playerId);
        int serverDistance;
        try {
            int globalDistance = Bukkit.getServer().getViewDistance();
            int playerDistance = player.getViewDistance();
            if (playerDistance > 0) {
                serverDistance = Math.min(globalDistance, playerDistance);
            } else {
                serverDistance = globalDistance;
            }
        } catch (Throwable ignored) {
            serverDistance = 10;
        }
        if (serverDistance < 2) serverDistance = 2;
        if (viewDistance <= serverDistance) {
            debug(playerId, "skip", "[EH] skip target=" + viewDistance + " server=" + serverDistance + " moved=" + moved + " force=" + force);
            return;
        }

        int effectiveRadius = viewDistance + 1;
        int safeSquareRadius = (int) Math.floor(serverDistance * config().safeSquareFactor());
        if (safeSquareRadius < 2) safeSquareRadius = 2;

        Set<Long> neededChunks = new HashSet<>();

        for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
            for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
                int cx = chunkX + dx;
                int cz = chunkZ + dz;

                int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
                if (chebyshev <= safeSquareRadius) continue;
                if (dx * dx + dz * dz > effectiveRadius * effectiveRadius) continue;

                neededChunks.add(ChunkPos.asLong(cx, cz));
            }
        }

        Set<Long> sentChunks = new HashSet<>(tracker.getSentChunks());
        int unloaded = 0;
        for (Long chunkKey : sentChunks) {
            if (!neededChunks.contains(chunkKey)) {
                int cx = ChunkPos.getX(chunkKey);
                int cz = ChunkPos.getZ(chunkKey);
                tracker.markChunkUnloaded(cx, cz);
                removeFakeChunkSubscription(playerId, world.getUID(), chunkKey);
                unloaded++;
            }
        }

        Deque<Long> queue = pendingQueues.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        Set<Long> queued = queuedSets.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        Set<Long> inflightSet = inflightKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        for (Long chunkKey : sentChunks) {
            if (!neededChunks.contains(chunkKey)) {
                queued.remove(chunkKey);
                inflightSet.remove(chunkKey);
                int cx = ChunkPos.getX(chunkKey);
                int cz = ChunkPos.getZ(chunkKey);
                sendUnloadPacket(player, cx, cz);
            }
        }

        Deque<Long> newQueue = new ConcurrentLinkedDeque<>();
        Set<Long> newQueued = ConcurrentHashMap.newKeySet();

        int kept = 0;
        for (Long existing : queue) {
            if (!neededChunks.contains(existing)) continue;
            if (tracker.getSentChunks().contains(existing)) continue;
            if (inflightSet.contains(existing)) continue;
            newQueue.addLast(existing);
            newQueued.add(existing);
            kept++;
        }

        queue.clear();
        queued.clear();
        queue.addAll(newQueue);
        queued.addAll(newQueued);

        List<Long> toAdd = new ArrayList<>();
        Set<Long> alreadySent = tracker.getSentChunks();
        for (Long chunkKey : neededChunks) {
            if (alreadySent.contains(chunkKey)) continue;
            if (queued.contains(chunkKey)) continue;
            if (inflightSet.contains(chunkKey)) continue;
            toAdd.add(chunkKey);
        }

        toAdd.sort(Comparator.comparingInt(key -> {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            int dx = cx - chunkX;
            int dz = cz - chunkZ;
            return dx * dx + dz * dz;
        }));

        for (Long key : toAdd) {
            queue.addLast(key);
            queued.add(key);
        }

        processQueue(player, world, playerId, chunkX, chunkZ, tracker);
        debug(playerId, "plan", "[EH] plan moved=" + moved + " force=" + force + " pos=" + chunkX + "," + chunkZ + " target=" + viewDistance + " server=" + serverDistance + " safe=" + safeSquareRadius + " needed=" + neededChunks.size() + " sent=" + tracker.getSentChunks().size() + " unload=" + unloaded + " kept=" + kept + " add=" + toAdd.size() + " queue=" + queue.size() + " inflight=" + inflightCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0)).get());
    }

    private void sendUnloadPacket(Player player, int x, int z) {
        sendPacket(player, new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z)));
    }

    private void resetPlayerState(UUID playerId, boolean resetTracker) {
        if (playerId == null) return;
        bumpSessionEpoch(playerId);
        if (resetTracker) {
            trackers.put(playerId, new PlayerChunkTracker());
        }
        clearPlayerFakeChunkSubscriptions(playerId);
        pendingQueues.remove(playerId);
        queuedSets.remove(playerId);
        inflightCounts.remove(playerId);
        inflightKeys.remove(playerId);
        lastSentRadius.remove(playerId);
        lastSentSimulationDistance.remove(playerId);
        lastForcedPlanMs.remove(playerId);
        lastAutoRefreshMs.remove(playerId);
        autoRefreshCursor.remove(playerId);
    }

    private void addFakeChunkSubscription(UUID playerId, UUID worldId, long chunkKey) {
        if (playerId == null || worldId == null) return;
        ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
        fakeChunkSubscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        playerFakeChunkIndex.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private void removeFakeChunkSubscription(UUID playerId, UUID worldId, long chunkKey) {
        if (playerId == null || worldId == null) return;
        ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
        Set<UUID> subscribers = fakeChunkSubscribers.get(key);
        if (subscribers != null) {
            subscribers.remove(playerId);
            if (subscribers.isEmpty()) fakeChunkSubscribers.remove(key);
        }
        Set<ChunkPacketCacheService.ChunkKey> keys = playerFakeChunkIndex.get(playerId);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) playerFakeChunkIndex.remove(playerId);
        }
    }

    private void clearPlayerFakeChunkSubscriptions(UUID playerId) {
        if (playerId == null) return;
        Set<ChunkPacketCacheService.ChunkKey> keys = playerFakeChunkIndex.remove(playerId);
        if (keys == null || keys.isEmpty()) return;
        for (ChunkPacketCacheService.ChunkKey key : keys) {
            Set<UUID> subscribers = fakeChunkSubscribers.get(key);
            if (subscribers == null) continue;
            subscribers.remove(playerId);
            if (subscribers.isEmpty()) fakeChunkSubscribers.remove(key);
        }
    }

    private void maybeAutoRefreshSentChunks(Player player, World world, UUID playerId, PlayerChunkTracker tracker) {
        if (!config().autoRefreshEnabled()) return;
        if (player == null || world == null || playerId == null || tracker == null) return;
        Set<Long> sent = tracker.getSentChunks();
        if (sent.isEmpty()) return;
        long now = System.currentTimeMillis();
        long periodMs = config().autoRefreshPeriodMs();
        Long last = lastAutoRefreshMs.get(playerId);
        if (last != null && now - last < periodMs) return;

        List<Long> sentList = new ArrayList<>(sent);
        int size = sentList.size();
        if (size == 0) return;
        sentList.sort(Long::compareTo);

        int perCycle = Math.min(config().autoRefreshChunksPerCycle(), size);
        UUID worldId = world.getUID();
        int refreshed = 0;
        long dirtyTtlMs = Math.max(3000L, periodMs * 10L);

        for (Long chunkKey : sentList) {
            if (refreshed >= perCycle) break;
            ChunkPacketCacheService.ChunkKey key = new ChunkPacketCacheService.ChunkKey(worldId, chunkKey);
            Long dirtySince = dirtyFakeChunks.get(key);
            if (dirtySince == null) continue;
            if (now - dirtySince > dirtyTtlMs) {
                dirtyFakeChunks.remove(key);
                continue;
            }
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            refreshChunkForPlayer(player, world, tracker, cx, cz, chunkKey);
            refreshed++;
        }

        for (Long chunkKey : sentList) {
            if (refreshed >= perCycle) break;
            if (!chunkPacketCacheService.hasRealPlayers(worldId, chunkKey)) continue;
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            refreshChunkForPlayer(player, world, tracker, cx, cz, chunkKey);
            refreshed++;
        }

        if (refreshed < perCycle) {
            AtomicInteger cursor = autoRefreshCursor.computeIfAbsent(playerId, k -> new AtomicInteger(0));
            int remaining = perCycle - refreshed;
            for (int i = 0; i < remaining; i++) {
                int idx = Math.floorMod(cursor.getAndIncrement(), size);
                long chunkKey = sentList.get(idx);
                int cx = ChunkPos.getX(chunkKey);
                int cz = ChunkPos.getZ(chunkKey);
                handleRealChunkInteraction(world, cx, cz);
            }
        }
        lastAutoRefreshMs.put(playerId, now);
    }

    private void refreshChunkForPlayer(Player player, World world, PlayerChunkTracker tracker, int chunkX, int chunkZ, long chunkKey) {
        if (!enabled.get()) return;
        if (player == null || !player.isOnline()) return;
        if (world == null) return;
        UUID playerId = player.getUniqueId();
        tracker.markChunkUnloaded(chunkX, chunkZ);
        sendUnloadPacket(player, chunkX, chunkZ);
        Set<Long> inflight = inflightKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        inflight.remove(chunkKey);
        Set<Long> queued = queuedSets.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        Deque<Long> queue = pendingQueues.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        if (queued.add(chunkKey)) {
            queue.addFirst(chunkKey);
        }
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        processQueue(player, world, playerId, playerChunkX, playerChunkZ, tracker);
    }

    private CompletableFuture<Boolean> sendFakeChunk(Player player, World world, int x, int z, PlayerChunkTracker tracker, UUID expectedWorldId, long expectedEpoch) {
        long startedNs = System.nanoTime();
        if (!enabled.get()) return CompletableFuture.completedFuture(false);
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(false);
        if (world == null) return CompletableFuture.completedFuture(false);
        if (!isSessionValid(player, expectedWorldId, expectedEpoch)) return CompletableFuture.completedFuture(false);
        long chunkKey = ChunkPos.asLong(x, z);
        if (!chunkPacketCacheService.shouldBypass(expectedWorldId, chunkKey)) {
            ClientboundLevelChunkWithLightPacket cached = chunkPacketCacheService.get(expectedWorldId, chunkKey);
            if (cached != null) {
                sendPacketForSession(player, cached, expectedWorldId, expectedEpoch);
                if (isSessionValid(player, expectedWorldId, expectedEpoch)) {
                    tracker.markChunkSent(x, z);
                    addFakeChunkSubscription(player.getUniqueId(), expectedWorldId, chunkKey);
                    inc(player.getUniqueId(), "fake_sent_cache");
                    recordChunkLatency(startedNs);
                    return CompletableFuture.completedFuture(true);
                }
            }
        }
        return world.getChunkAtAsync(x, z, true).thenCompose(chunk -> {
            if (!isSessionValid(player, expectedWorldId, expectedEpoch)) {
                return CompletableFuture.completedFuture(false);
            }
            if (chunk == null) {
                inc(player.getUniqueId(), "chunk_async_null");
                return CompletableFuture.completedFuture(false);
            }
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            boolean scheduled = runAtChunk(world, x, z, () -> {
                if (!enabled.get() || !isSessionValid(player, expectedWorldId, expectedEpoch)) {
                    future.complete(false);
                    return;
                }
                try {
                    ChunkAccess access = ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);
                    if (!(access instanceof LevelChunk nmsChunk)) {
                        inc(player.getUniqueId(), "chunk_not_full");
                        future.complete(false);
                        return;
                    }
                    LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();
                    BitSet[] lightMasks = getLightMasks(nmsChunk);
                    ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                            nmsChunk,
                            lightEngine,
                            lightMasks[0],
                            lightMasks[1],
                            true
                    );
                    chunkPacketCacheService.put(expectedWorldId, chunkKey, packet);
                    sendPacketForSession(player, packet, expectedWorldId, expectedEpoch);
                    if (!isSessionValid(player, expectedWorldId, expectedEpoch)) {
                        future.complete(false);
                        return;
                    }
                    tracker.markChunkSent(x, z);
                    addFakeChunkSubscription(player.getUniqueId(), expectedWorldId, chunkKey);
                    inc(player.getUniqueId(), "fake_sent");
                    future.complete(true);
                } catch (Throwable t) {
                    logger.error("Failed live-chunk packet {},{} for {}", x, z, player.getName(), t);
                    future.complete(false);
                }
            });
            if (!scheduled) {
                future.complete(false);
            }
            return future;
        }).exceptionally(e -> {
            logger.error("Failed to send fake chunk {},{} to {}", x, z, player.getName(), e);
            return false;
        }).whenComplete((ok, err) -> recordChunkLatency(startedNs));
    }

    private void recordChunkLatency(long startedNs) {
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        chunkLoadSamples.incrementAndGet();
        chunkLoadTotalMs.addAndGet(elapsedMs);
        if (elapsedMs > 100L) {
            chunkLoadOver100Ms.incrementAndGet();
        }
        long sample = chunkLoadSamples.get();
        if (config().debugEnabled() && sample % 250L == 0L) {
            long avg = sample == 0L ? 0L : chunkLoadTotalMs.get() / sample;
            logger.info("[EH] chunkLoad avg={}ms over100ms={}/{} cacheSize={} cacheHit={} cacheMiss={}",
                    avg,
                    chunkLoadOver100Ms.get(),
                    sample,
                    chunkPacketCacheService.estimatedSize(),
                    chunkPacketCacheService.hitCount(),
                    chunkPacketCacheService.missCount());
        }
    }

    private BitSet[] getLightMasks(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int sectionCount = sections.length;
        BitSet skyLight = new BitSet(sectionCount + 2);
        BitSet blockLight = new BitSet(sectionCount + 2);
        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) {
                skyLight.set(i + 1);
                blockLight.set(i + 1);
            }
        }
        return new BitSet[]{skyLight, blockLight};
    }

    private void debug(UUID playerId, String tag, String msg) {
        if (!config().debugEnabled()) return;
        if (playerId == null || tag == null || msg == null) return;
        long now = System.currentTimeMillis();
        String key = playerId + ":" + tag;
        Long last = lastDebugLogMs.get(key);
        if (last != null && now - last < 2000) return;
        lastDebugLogMs.put(key, now);
        logger.info(msg);
    }

    private void inc(UUID playerId, String metric) {
        if (!config().debugEnabled()) return;
        if (playerId == null || metric == null) return;
        String key = playerId + ":" + metric;
        counters.merge(key, 1L, Long::sum);
        Long v = counters.get(key);
        if (v != null && (v % 200) == 0) {
            logger.info("[EH] metric {}={}", metric, v);
        }
    }

    private void processQueue(Player player, World world, UUID playerId, int chunkX, int chunkZ, PlayerChunkTracker tracker) {
        if (!enabled.get()) return;
        if (player == null || !player.isOnline()) return;
        UUID expectedWorldId = world.getUID();
        long expectedEpoch = getSessionEpoch(playerId);

        Deque<Long> queue = pendingQueues.get(playerId);
        if (queue == null) return;

        Set<Long> queued = queuedSets.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        AtomicInteger inflight = inflightCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0));
        Set<Long> inflightSet = inflightKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        int sentThisCycle = 0;
        while (sentThisCycle < config().maxSendPerCycle() && inflight.get() < config().maxInflightPerPlayer()) {
            Long key = queue.pollFirst();
            if (key == null) break;
            queued.remove(key);
            if (tracker.getSentChunks().contains(key)) continue;

            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            inflightSet.add(key);
            inflight.incrementAndGet();
            sendFakeChunk(player, world, cx, cz, tracker, expectedWorldId, expectedEpoch).whenComplete((ok, err) -> {
                inflight.decrementAndGet();
                inflightSet.remove(key);
            });
            sentThisCycle++;
        }
    }

    private void ensureClientCacheRadius(Player player) {
        runForPlayer(player, () -> {
            if (!enabled.get()) return;
            if (!player.isOnline()) return;
            UUID playerId = player.getUniqueId();
            int desired = getTargetDistance(playerId);
            int serverDistance;
            try {
                serverDistance = Bukkit.getServer().getViewDistance();
            } catch (Throwable ignored) {
                serverDistance = 10;
            }
            if (serverDistance < 2) serverDistance = 2;
            int target = desired;

            Integer lastRadius = lastSentRadius.get(playerId);
            if (lastRadius == null || lastRadius != target) {
                sendPacket(player, new ClientboundSetChunkCacheRadiusPacket(target));
                lastSentRadius.put(playerId, target);
            }

            Integer lastSimulation = lastSentSimulationDistance.get(playerId);
            if (lastSimulation == null || lastSimulation != target) {
                sendPacket(player, new ClientboundSetSimulationDistancePacket(target));
                lastSentSimulationDistance.put(playerId, target);
            }

            debug(playerId, "radius", "[EH] radius target=" + target + " server=" + serverDistance);
        });
    }

    private int getTargetDistance(UUID playerId) {
        int configuredDefault = config().fakeTargetViewDistance();
        int target = getPermissionDistanceCap(playerId, configuredDefault);
        try {
            Integer preferredValue = playerDistancePreferenceService.get(playerId);
            int preferred;
            if (preferredValue != null && preferredValue >= 2) {
                preferred = preferredValue;
            } else {
                preferred = target;
            }
            target = Math.min(target, preferred);
        } catch (Throwable ignored) {
        }
        if (target < 2) target = 2;
        return target;
    }

    private int getPermissionDistanceCap(UUID playerId, int fallback) {
        if (playerId == null) return fallback;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return fallback;
        int permissionMax = Integer.MIN_VALUE;
        String prefix = "extendedhorizons.max.";
        for (PermissionAttachmentInfo permissionInfo : player.getEffectivePermissions()) {
            if (permissionInfo == null || !permissionInfo.getValue()) continue;
            String permission = permissionInfo.getPermission();
            if (permission == null || !permission.startsWith(prefix)) continue;
            String rawValue = permission.substring(prefix.length());
            try {
                int value = Integer.parseInt(rawValue);
                if (value >= 2 && value > permissionMax) {
                    permissionMax = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return permissionMax == Integer.MIN_VALUE ? fallback : permissionMax;
    }

    public int getAdvertisedDistance(UUID playerId) {
        return getTargetDistance(playerId);
    }

    private void sendPacket(Player player, Packet<?> packet) {
        if (packet == null) return;
        runForPlayer(player, () -> {
            if (!enabled.get()) return;
            if (!player.isOnline()) return;
            try {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                serverPlayer.connection.send(packet);
            } catch (Exception e) {
                logger.error("Error sending packet to {}", player.getName(), e);
            }
        });
    }

    private void sendPacketForSession(Player player, Packet<?> packet, UUID expectedWorldId, long expectedEpoch) {
        if (packet == null) return;
        runForPlayer(player, () -> {
            if (!enabled.get()) return;
            if (!isSessionValid(player, expectedWorldId, expectedEpoch)) return;
            try {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                serverPlayer.connection.send(packet);
            } catch (Exception e) {
                logger.error("Error sending packet to {}", player.getName(), e);
            }
        });
    }

    private boolean isSessionValid(Player player, UUID expectedWorldId, long expectedEpoch) {
        if (player == null || !player.isOnline()) return false;
        if (expectedWorldId == null || !expectedWorldId.equals(player.getWorld().getUID())) return false;
        UUID playerId = player.getUniqueId();
        long currentEpoch = getSessionEpoch(playerId);
        return currentEpoch == expectedEpoch;
    }

    private long bumpSessionEpoch(UUID playerId) {
        if (playerId == null) return 0L;
        return sessionEpoch.merge(playerId, 1L, Long::sum);
    }

    private long getSessionEpoch(UUID playerId) {
        if (playerId == null) return 0L;
        return sessionEpoch.getOrDefault(playerId, 0L);
    }

    private void cleanupPlayerDebugState(UUID playerId) {
        if (playerId == null) return;
        String prefix = playerId + ":";
        lastDebugLogMs.keySet().removeIf(key -> key != null && key.startsWith(prefix));
        counters.keySet().removeIf(key -> key != null && key.startsWith(prefix));
    }

    private void runForPlayer(Player player, Runnable runnable) {
        if (player == null || runnable == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            player.getScheduler().run(plugin, task -> runnable.run(), null);
        } catch (Throwable ignored) {
            debug(player.getUniqueId(), "sched_fail", "[EH] runForPlayer failed (ignored)");
        }
    }

    private void runForPlayerDelayed(Player player, Runnable runnable, long delayTicks) {
        if (player == null || runnable == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        long delay = Math.max(1L, delayTicks);
        try {
            player.getScheduler().runDelayed(plugin, task -> runnable.run(), null, delay);
        } catch (Throwable ignored) {
            debug(player.getUniqueId(), "sched_delay_fail", "[EH] runForPlayerDelayed failed (ignored)");
        }
    }

    private void runGlobal(Runnable runnable) {
        if (runnable == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
        } catch (Throwable ignored) {
        }
    }

    private boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (world == null || runnable == null) return false;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            Location loc = new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
            Bukkit.getServer().getRegionScheduler().execute(plugin, loc, runnable);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Config config() {
        Config cfg = configContainer.get();
        return cfg == null ? new Config(null, null, null) : cfg;
    }
}
