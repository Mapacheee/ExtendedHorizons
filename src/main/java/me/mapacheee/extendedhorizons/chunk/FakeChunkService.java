package me.mapacheee.extendedhorizons.chunk;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.io.RegionFileService;
import me.mapacheee.extendedhorizons.chunk.pipeline.ChunkPipelineService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;
import me.mapacheee.extendedhorizons.viewdistance.ClientViewDistanceService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    private final Provider<RegionFileService> regionFileServiceProvider;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("extendedhorizons.debug", "true"));
    private final Provider<ChunkPipelineService> chunkPipelineServiceProvider;
    private final ClientViewDistanceService clientViewDistanceService;
    private final Map<UUID, PlayerChunkTracker> trackers = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile ScheduledTask keepAliveTask;
    private final Map<UUID, Deque<Long>> pendingQueues = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> queuedSets = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> inflightCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> inflightKeys = new ConcurrentHashMap<>();
    private final Map<String, Long> lastDebugLogMs = new ConcurrentHashMap<>();
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    
    private static final int FAKE_VIEW_DISTANCE = 32;
    private static final int MAX_SEND_PER_CYCLE = 25;
    private static final int MAX_INFLIGHT_PER_PLAYER = 16;

    @Inject
    public FakeChunkService(Provider<RegionFileService> regionFileServiceProvider, Provider<ChunkPipelineService> chunkPipelineServiceProvider, ClientViewDistanceService clientViewDistanceService) {
        this.regionFileServiceProvider = regionFileServiceProvider;
        this.chunkPipelineServiceProvider = chunkPipelineServiceProvider;
        this.clientViewDistanceService = clientViewDistanceService;
    }

    @OnEnable
    public void onEnable() {
        enabled.set(true);
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
                                sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));

                                UUID playerId = player.getUniqueId();
                                Deque<Long> queue = pendingQueues.get(playerId);
                                if (queue != null && !queue.isEmpty()) {
                                    World world = player.getWorld();
                                    PlayerChunkTracker tracker = trackers.get(playerId);
                                    if (tracker != null) {
                                        try {
                                            processQueue(player, world, playerId, chunkX, chunkZ, tracker);
                                        } catch (Throwable t) {
                                            logger.error("processQueue failed for {}", player.getName(), t);
                                        }
                                    }
                                }
                            });
                        }
                    },
                    20L,
                    10L
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
            }, null, 5L);

            player.getScheduler().runDelayed(plugin, task -> {
                if (!enabled.get()) return;
                if (!player.isOnline()) return;
                ensureClientCacheRadius(player);
                int chunkX = player.getLocation().getBlockX() >> 4;
                int chunkZ = player.getLocation().getBlockZ() >> 4;
                sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
                updatePlayerChunks(player, player.getUniqueId(), player.getWorld(), chunkX, chunkZ, true);
            }, null, 70L);
        } catch (Throwable ignored) {
        }
    }

    public void handleJoin(Player player) {
        trackers.put(player.getUniqueId(), new PlayerChunkTracker());
        ensureClientCacheRadius(player);
        handleMove(player);
        scheduleWarmup(player);
        debug(player.getUniqueId(), "join", "[EH] join world=" + player.getWorld().getName());
    }

    public void handleQuit(Player player) {
        trackers.remove(player.getUniqueId());
        pendingQueues.remove(player.getUniqueId());
        queuedSets.remove(player.getUniqueId());
        inflightCounts.remove(player.getUniqueId());
        inflightKeys.remove(player.getUniqueId());
        debug(player.getUniqueId(), "quit", "[EH] quit");
    }

    public void handleTeleport(Player player) {
        PlayerChunkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker != null) {
            tracker.clear();
            tracker.updatePosition(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }
        ensureClientCacheRadius(player);
        handleMove(player);
        scheduleWarmup(player);
        debug(player.getUniqueId(), "tp", "[EH] teleport world=" + player.getWorld().getName());
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
            serverDistance = Bukkit.getServer().getViewDistance();
        } catch (Throwable ignored) {
            serverDistance = 10;
        }
        if (serverDistance < 2) serverDistance = 2;
        if (viewDistance <= serverDistance) {
            debug(playerId, "skip", "[EH] skip target=" + viewDistance + " server=" + serverDistance + " moved=" + moved + " force=" + force);
            return;
        }
        
        Set<Long> neededChunks = new HashSet<>();

        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int cx = chunkX + dx;
                int cz = chunkZ + dz;
                
                if (Math.abs(dx) <= serverDistance && Math.abs(dz) <= serverDistance) continue;
                if (dx * dx + dz * dz > viewDistance * viewDistance) continue;

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
        debug(playerId, "plan", "[EH] plan moved=" + moved + " force=" + force + " pos=" + chunkX + "," + chunkZ + " target=" + viewDistance + " server=" + serverDistance + " needed=" + neededChunks.size() + " sent=" + tracker.getSentChunks().size() + " unload=" + unloaded + " kept=" + kept + " add=" + toAdd.size() + " queue=" + queue.size() + " inflight=" + inflightCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0)).get());
    }

    private void sendUnloadPacket(Player player, int x, int z) {
        sendPacket(player, new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z)));
    }

    private CompletableFuture<Boolean> sendFakeChunk(Player player, World world, int x, int z, PlayerChunkTracker tracker) {
        if (!enabled.get()) return CompletableFuture.completedFuture(false);
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(false);
        if (world == null) return CompletableFuture.completedFuture(false);
        RegionFileService regionFileService = regionFileServiceProvider.get();
        if (!regionFileService.hasChunk(world, x, z)) {
            inc(player.getUniqueId(), "disk_miss");
            return CompletableFuture.completedFuture(false);
        }

        return regionFileService.readChunkData(world, x, z).thenCompose(nbt -> {
            if (nbt == null) {
                inc(player.getUniqueId(), "nbt_null");
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            ChunkPipelineService chunkPipelineService = chunkPipelineServiceProvider.get();
            return chunkPipelineService.createPacket(world, x, z, nbt);
        }).thenApply(packets -> {
            if (packets == null || packets.chunkPacket() == null || !player.isOnline()) {
                inc(player.getUniqueId(), "packet_null");
                return false;
            }
            sendPacket(player, packets.chunkPacket());
            if (packets.lightPacket() != null) {
                sendPacket(player, packets.lightPacket());
                inc(player.getUniqueId(), "light_sent");
            } else {
                inc(player.getUniqueId(), "light_null");
            }
            tracker.markChunkSent(x, z);
            inc(player.getUniqueId(), "fake_sent");
            return true;
        }).exceptionally(e -> {
            logger.error("Failed to send fake chunk {},{} to {}", x, z, player.getName(), e);
            return false;
        });
    }

    private void debug(UUID playerId, String tag, String msg) {
        if (!DEBUG) return;
        if (playerId == null || tag == null || msg == null) return;
        long now = System.currentTimeMillis();
        String key = playerId + ":" + tag;
        Long last = lastDebugLogMs.get(key);
        if (last != null && now - last < 2000) return;
        lastDebugLogMs.put(key, now);
        logger.info(msg);
    }

    private void inc(UUID playerId, String metric) {
        if (!DEBUG) return;
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

        Deque<Long> queue = pendingQueues.get(playerId);
        if (queue == null) return;

        Set<Long> queued = queuedSets.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        AtomicInteger inflight = inflightCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0));
        Set<Long> inflightSet = inflightKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        int sentThisCycle = 0;
        while (sentThisCycle < MAX_SEND_PER_CYCLE && inflight.get() < MAX_INFLIGHT_PER_PLAYER) {
            Long key = queue.pollFirst();
            if (key == null) break;
            queued.remove(key);
            if (tracker.getSentChunks().contains(key)) continue;

            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            inflightSet.add(key);
            inflight.incrementAndGet();
            sendFakeChunk(player, world, cx, cz, tracker).whenComplete((ok, err) -> {
                inflight.decrementAndGet();
                inflightSet.remove(key);
            });
            sentThisCycle++;
        }
    }

    public boolean shouldCancelUnload(UUID playerId, int chunkX, int chunkZ) {
        if (playerId == null) return false;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        PlayerChunkTracker tracker = trackers.get(playerId);
        if (tracker != null && tracker.getSentChunks().contains(key)) return true;

        Set<Long> queued = queuedSets.get(playerId);
        if (queued != null && queued.contains(key)) return true;

        Set<Long> inflight = inflightKeys.get(playerId);
        return inflight != null && inflight.contains(key);
    }

    private void ensureClientCacheRadius(Player player) {
        runForPlayer(player, () -> {
            if (!enabled.get()) return;
            if (!player.isOnline()) return;
            int target = getTargetDistance(player.getUniqueId());
            sendPacket(player, new ClientboundSetChunkCacheRadiusPacket(target));
            sendPacket(player, new ClientboundSetSimulationDistancePacket(target));
            int serverDistance;
            try {
                serverDistance = Bukkit.getServer().getViewDistance();
            } catch (Throwable ignored) {
                serverDistance = -1;
            }
            debug(player.getUniqueId(), "radius", "[EH] radius target=" + target + " server=" + serverDistance);
        });
    }

    private int getTargetDistance(UUID playerId) {
        int target = FAKE_VIEW_DISTANCE;
        try {
            int client = clientViewDistanceService.getOrDefault(playerId, FAKE_VIEW_DISTANCE);
            target = Math.min(target, client);
        } catch (Throwable ignored) {
        }
        if (target < 2) target = 2;
        return target;
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

    private void runGlobal(Runnable runnable) {
        if (runnable == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
        } catch (Throwable ignored) {
            try {
                Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
            } catch (Throwable ignored2) {
            }
        }
    }
}
