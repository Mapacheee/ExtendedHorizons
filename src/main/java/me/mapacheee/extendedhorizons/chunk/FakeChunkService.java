package me.mapacheee.extendedhorizons.chunk;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.viewdistance.PlayerDistancePreferenceService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FakeChunkService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FakeChunkService.class);
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
  private final FakeChunkRefreshCoordinator refreshCoordinator;
  private final FakeChunkRefreshLoopService refreshLoopService;
  private final FakeChunkRefreshLoopService.Actions refreshLoopActions;
  private final FakeChunkDispatchService dispatchService;
  private final FakeChunkPlannerService plannerService;
  private final FakeChunkDispatchService.Hooks dispatchHooks;
  private final Map<UUID, UUID> lastKnownWorldId = new ConcurrentHashMap<>();
  private final Map<UUID, Long> sessionEpoch = new ConcurrentHashMap<>();
  private final AtomicLong chunkLoadSamples = new AtomicLong();
  private final AtomicLong chunkLoadTotalMs = new AtomicLong();
  private final AtomicLong chunkLoadOver100Ms = new AtomicLong();

  @Inject
  public FakeChunkService(
      Container<Config> configContainer,
      PlayerDistancePreferenceService playerDistancePreferenceService,
      ChunkPacketCacheService chunkPacketCacheService) {
    this.configContainer = configContainer;
    this.playerDistancePreferenceService = playerDistancePreferenceService;
    this.chunkPacketCacheService = chunkPacketCacheService;
    this.refreshCoordinator = new FakeChunkRefreshCoordinator();
    this.refreshLoopService =
        new FakeChunkRefreshLoopService(
            configContainer, refreshCoordinator, chunkPacketCacheService);
    this.plannerService = new FakeChunkPlannerService();
    FakeChunkDispatchService.State dispatchState =
        new FakeChunkDispatchService.State(
            pendingQueues, queuedSets, inflightCounts, inflightKeys, enabled, LOGGER);
    this.dispatchService =
        new FakeChunkDispatchService(
            configContainer, chunkPacketCacheService, refreshCoordinator, dispatchState);
    this.refreshLoopActions = new RefreshLoopActionsImpl();
    this.dispatchHooks = new DispatchHooksImpl();
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

    runGlobal(
        () -> {
          if (keepAliveTask != null) {
            keepAliveTask.cancel();
            keepAliveTask = null;
          }
          var plugin = ExtendedHorizonsPlugin.getInstance();
          int initialDelay = Math.max(1, config().keepAliveInitialDelayTicks());
          int period = Math.max(1, config().keepAlivePeriodTicks());
          keepAliveTask =
              Bukkit.getServer()
                  .getGlobalRegionScheduler()
                  .runAtFixedRate(
                      plugin,
                      task -> {
                        if (!enabled.get()) return;
                        for (Player player : Bukkit.getOnlinePlayers()) {
                          ensureClientCacheRadius(player);
                          runForPlayer(
                              player,
                              () -> {
                                if (!enabled.get()) return;
                                if (!player.isOnline()) return;
                                int chunkX = player.getLocation().getBlockX() >> 4;
                                int chunkZ = player.getLocation().getBlockZ() >> 4;
                                UUID playerId = player.getUniqueId();
                                World world = player.getWorld();
                                lastKnownWorldId.putIfAbsent(playerId, world.getUID());

                                PlayerChunkTracker tracker = trackers.get(playerId);
                                if (tracker != null) {
                                  long now = System.currentTimeMillis();
                                  Long lastPlan = lastForcedPlanMs.get(playerId);
                                  Deque<Long> queue = pendingQueues.get(playerId);
                                  AtomicInteger inflight =
                                      inflightCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0));
                                  boolean needsRecoveryPlan =
                                      tracker.getSentChunks().isEmpty()
                                          || (queue != null && !queue.isEmpty())
                                          || inflight.get() > 0;
                                  if (needsRecoveryPlan
                                      && (lastPlan == null
                                          || now - lastPlan >= config().forcePlanIntervalMs())) {
                                    updatePlayerChunks(
                                        player, playerId, world, chunkX, chunkZ, true);
                                    lastForcedPlanMs.put(playerId, now);
                                  }
                                }

                                Deque<Long> queue = pendingQueues.get(playerId);
                                if (queue != null && !queue.isEmpty()) {
                                  if (tracker != null) {
                                    try {
                                      long expectedEpoch = getSessionEpoch(playerId);
                                      dispatchService.processQueue(
                                          player,
                                          world,
                                          playerId,
                                          tracker,
                                          expectedEpoch,
                                          dispatchHooks);
                                    } catch (Throwable t) {
                                      LOGGER.error(
                                          "processQueue failed for {}", player.getName(), t);
                                    }
                                  }
                                }
                                if (tracker != null) {
                                  refreshLoopService.tick(
                                      player, world, playerId, tracker, refreshLoopActions);
                                }
                              });
                        }
                      },
                      initialDelay,
                      period);
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
    refreshLoopService.clearAll();
    refreshCoordinator.clearAll();
    lastKnownWorldId.clear();
    sessionEpoch.clear();
  }

  private void scheduleWarmup(Player player) {
    if (player == null) return;
    var plugin = ExtendedHorizonsPlugin.getInstance();
    if (plugin == null || !plugin.isEnabled()) return;

    try {
      player
          .getScheduler()
          .runDelayed(
              plugin,
              task -> {
                if (!enabled.get()) return;
                if (!player.isOnline()) return;
                ensureClientCacheRadius(player);
                int chunkX = player.getLocation().getBlockX() >> 4;
                int chunkZ = player.getLocation().getBlockZ() >> 4;
                sendPacket(player, new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
                updatePlayerChunks(
                    player, player.getUniqueId(), player.getWorld(), chunkX, chunkZ, true);
              },
              null,
              config().warmupDelayTicks());
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
    runForPlayerDelayed(
        player,
        () -> {
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
        },
        1L);
    scheduleWarmup(player);
    debug(playerId, "tp", "[EH] teleport world=" + targetWorld.getName());
  }

  public void handleRealChunkInteraction(World world, int chunkX, int chunkZ) {
    if (world == null) return;
    handleRealChunkInteraction(world.getUID(), chunkX, chunkZ);
  }

  public void handleRealChunkInteraction(UUID worldId, int chunkX, int chunkZ) {
    if (worldId == null) return;
    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
    long now = System.currentTimeMillis();
    Long previousDirty = refreshCoordinator.getDirtySince(worldId, chunkKey);
    long minInvalidateIntervalMs = config().autoRefreshMinInvalidateIntervalMs();
    if (previousDirty != null && now - previousDirty < minInvalidateIntervalMs) {
      return;
    }
    refreshCoordinator.markDirty(worldId, chunkKey);
    chunkPacketCacheService.invalidate(worldId, chunkKey);
    Set<UUID> targets = refreshCoordinator.collectTargets(worldId, chunkKey, trackers);

    if (targets.isEmpty()) {
      refreshCoordinator.clearDirty(worldId, chunkKey);
      return;
    }

    for (UUID playerId : targets) {
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) {
        refreshCoordinator.removeSubscription(playerId, worldId, chunkKey);
        continue;
      }
      World playerWorld = player.getWorld();
      if (playerWorld == null || !worldId.equals(playerWorld.getUID())) {
        refreshCoordinator.removeSubscription(playerId, worldId, chunkKey);
        continue;
      }
      PlayerChunkTracker tracker = trackers.get(playerId);
      if (tracker == null || !tracker.getSentChunks().contains(chunkKey)) {
        refreshCoordinator.removeSubscription(playerId, worldId, chunkKey);
        continue;
      }
      runForPlayer(
          player,
          () -> refreshChunkForPlayer(player, playerWorld, tracker, chunkX, chunkZ, chunkKey));
    }
  }

  public void applyDistancePreference(Player player, int distance) {
    if (player == null) return;
    UUID playerId = player.getUniqueId();
    playerDistancePreferenceService.set(playerId, distance);
    runForPlayer(
        player,
        () -> {
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
    if (player == null || !player.isOnline()) return;
    UUID playerId = player.getUniqueId();
    World world = player.getWorld();
    if (world == null) return;
    int chunkX = player.getLocation().getBlockX() >> 4;
    int chunkZ = player.getLocation().getBlockZ() >> 4;
    UUID worldId = world.getUID();
    UUID lastWorldId = lastKnownWorldId.get(playerId);
    PlayerChunkTracker fastTracker = trackers.get(playerId);
    if (fastTracker != null
        && lastWorldId != null
        && lastWorldId.equals(worldId)
        && !fastTracker.hasMovedChunk(chunkX, chunkZ)) {
      return;
    }
    runForPlayer(
        player,
        () -> {
          if (!enabled.get()) return;
          if (!player.isOnline()) return;

          int currentChunkX = player.getLocation().getBlockX() >> 4;
          int currentChunkZ = player.getLocation().getBlockZ() >> 4;
          World currentWorld = player.getWorld();
          UUID currentWorldId = currentWorld.getUID();
          UUID knownWorldId = lastKnownWorldId.get(playerId);
          boolean worldChanged = knownWorldId == null || !knownWorldId.equals(currentWorldId);
          if (worldChanged) {
            resetPlayerState(playerId, true);
            lastKnownWorldId.put(playerId, currentWorldId);
            debug(playerId, "world_change", "[EH] world change detected to " + currentWorld.getName());
          }

          PlayerChunkTracker tracker = trackers.get(playerId);
          boolean movedChunk = tracker == null || tracker.hasMovedChunk(currentChunkX, currentChunkZ);
          if (movedChunk) {
            sendPacket(player, new ClientboundSetChunkCacheCenterPacket(currentChunkX, currentChunkZ));
          }

          try {
            updatePlayerChunks(player, playerId, currentWorld, currentChunkX, currentChunkZ, false);
          } catch (Throwable t) {
            LOGGER.error("updatePlayerChunks failed for {}", player.getName(), t);
          }
        });
  }

  private void updatePlayerChunks(
      Player player, UUID playerId, World world, int chunkX, int chunkZ, boolean force) {
    if (!enabled.get()) return;

    PlayerChunkTracker tracker = trackers.get(playerId);
    if (tracker == null) return;
    if (!isFakeChunksEnabled(world)) {
      disableFakeChunksForPlayer(player, playerId, world, tracker);
      return;
    }

    boolean moved = tracker.hasMovedChunk(chunkX, chunkZ);
    if (!moved && !force) return;
    if (moved) tracker.updatePosition(chunkX, chunkZ);

    int viewDistance = getTargetDistance(player);
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
      String skipMessage =
          "[EH] skip target="
              + viewDistance
              + " server="
              + serverDistance
              + " moved="
              + moved
              + " force="
              + force;
      debug(playerId, "skip", skipMessage);
      return;
    }

    Set<Long> sentChunks = new HashSet<>(tracker.getSentChunks());

    Deque<Long> queue = pendingQueues.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
    Set<Long> queued = queuedSets.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
    Set<Long> inflightSet =
        inflightKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

    FakeChunkPlannerService.PlanInput planInput =
        new FakeChunkPlannerService.PlanInput(
            chunkX,
            chunkZ,
            viewDistance,
            serverDistance,
            config().safeSquareFactor(),
            sentChunks,
            queue,
            queued,
            inflightSet);
    FakeChunkPlannerService.PlanResult plan = plannerService.build(planInput);

    for (Long chunkKey : plan.chunksToUnload()) {
      int cx = ChunkPos.getX(chunkKey);
      int cz = ChunkPos.getZ(chunkKey);
      tracker.markChunkUnloaded(cx, cz);
      refreshCoordinator.removeSubscription(playerId, world.getUID(), chunkKey);
      queued.remove(chunkKey);
      inflightSet.remove(chunkKey);
      sendUnloadPacket(player, cx, cz);
    }

    queue.clear();
    queued.clear();
    queue.addAll(plan.rebuiltQueue());
    queued.addAll(plan.rebuiltQueuedSet());

    for (Long key : plan.toAdd()) {
      queue.addLast(key);
      queued.add(key);
    }

    dispatchService.processQueue(
        player, world, playerId, tracker, getSessionEpoch(playerId), dispatchHooks);
    if (config().debugEnabled()) {
      AtomicInteger inflight = inflightCounts.get(playerId);
      int inflightCount = inflight == null ? 0 : inflight.get();
      String planDebug =
          "[EH] plan moved="
              + moved
              + " force="
              + force
              + " pos="
              + chunkX
              + ","
              + chunkZ
              + " target="
              + viewDistance
              + " server="
              + serverDistance
              + " safe="
              + plan.safeSquareRadius()
              + " needed="
              + plan.neededChunks().size()
              + " sent="
              + tracker.getSentChunks().size()
              + " unload="
              + plan.chunksToUnload().size()
              + " kept="
              + plan.kept()
              + " add="
              + plan.toAdd().size()
              + " queue="
              + queue.size()
              + " inflight="
              + inflightCount;
      debug(playerId, "plan", planDebug);
    }
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
    refreshCoordinator.clearPlayerSubscriptions(playerId);
    pendingQueues.remove(playerId);
    queuedSets.remove(playerId);
    inflightCounts.remove(playerId);
    inflightKeys.remove(playerId);
    lastSentRadius.remove(playerId);
    lastSentSimulationDistance.remove(playerId);
    lastForcedPlanMs.remove(playerId);
    refreshLoopService.clearPlayer(playerId);
  }

  private void refreshChunkForPlayer(
      Player player,
      World world,
      PlayerChunkTracker tracker,
      int chunkX,
      int chunkZ,
      long chunkKey) {
    if (!enabled.get()) return;
    if (player == null || !player.isOnline()) return;
    if (world == null) return;
    UUID playerId = player.getUniqueId();
    Set<Long> queued = queuedSets.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
    if (queued.contains(chunkKey)) return;
    Set<Long> inflight = inflightKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
    if (inflight.contains(chunkKey)) return;
    tracker.markChunkUnloaded(chunkX, chunkZ);
    sendUnloadPacket(player, chunkX, chunkZ);
    inflight.remove(chunkKey);
    Deque<Long> queue = pendingQueues.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
    if (queued.add(chunkKey)) {
      queue.addFirst(chunkKey);
    }
    dispatchService.processQueue(
        player, world, playerId, tracker, getSessionEpoch(playerId), dispatchHooks);
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
      LOGGER.info(
          "[EH] chunkLoad avg={}ms over100ms={}/{} cacheSize={} cacheHit={} cacheMiss={}",
          avg,
          chunkLoadOver100Ms.get(),
          sample,
          chunkPacketCacheService.estimatedSize(),
          chunkPacketCacheService.hitCount(),
          chunkPacketCacheService.missCount());
    }
  }

  private void debug(UUID playerId, String tag, String msg) {
    if (!config().debugEnabled()) return;
    if (playerId == null || tag == null || msg == null) return;
    long now = System.currentTimeMillis();
    String key = playerId + ":" + tag;
    Long last = lastDebugLogMs.get(key);
    if (last != null && now - last < 2000) return;
    lastDebugLogMs.put(key, now);
    LOGGER.info(msg);
  }

  private void inc(UUID playerId, String metric) {
    if (!config().debugEnabled()) return;
    if (playerId == null || metric == null) return;
    String key = playerId + ":" + metric;
    counters.merge(key, 1L, Long::sum);
    Long v = counters.get(key);
    if (v != null && (v % 200) == 0) {
      LOGGER.info("[EH] metric {}={}", metric, v);
    }
  }

  private void ensureClientCacheRadius(Player player) {
    runForPlayer(
        player,
        () -> {
          if (!enabled.get()) return;
          if (!player.isOnline()) return;
          UUID playerId = player.getUniqueId();
          int desired = getTargetDistance(player);
          int serverDistance;
          try {
            serverDistance = Bukkit.getServer().getViewDistance();
          } catch (Throwable ignored) {
            serverDistance = 10;
          }
          if (serverDistance < 2) serverDistance = 2;
          int target = isFakeChunksEnabled(player.getWorld()) ? desired : serverDistance;

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

  private int getTargetDistance(Player player) {
    if (player == null) return config().fakeTargetViewDistance();
    UUID playerId = player.getUniqueId();
    World world = player.getWorld();
    String worldName = world == null ? null : world.getName();
    int configuredDefault = config().fakeTargetViewDistance(worldName);
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
    if (playerId == null) return config().fakeTargetViewDistance();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return config().fakeTargetViewDistance();
    return getTargetDistance(player);
  }

  private void sendPacket(Player player, Packet<?> packet) {
    if (packet == null) return;
    runForPlayer(
        player,
        () -> {
          if (!enabled.get()) return;
          if (!player.isOnline()) return;
          try {
            ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
            serverPlayer.connection.send(packet);
          } catch (Exception e) {
            LOGGER.error("Error sending packet to {}", player.getName(), e);
          }
        });
  }

  private void sendPacketForSession(
      Player player, Packet<?> packet, UUID expectedWorldId, long expectedEpoch) {
    if (packet == null) return;
    runForPlayer(
        player,
        () -> {
          if (!enabled.get()) return;
          if (!isSessionValid(player, expectedWorldId, expectedEpoch)) return;
          try {
            ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
            serverPlayer.connection.send(packet);
          } catch (Exception e) {
            LOGGER.error("Error sending packet to {}", player.getName(), e);
          }
        });
  }

  private boolean isSessionValid(Player player, UUID expectedWorldId, long expectedEpoch) {
    if (player == null || !player.isOnline()) return false;
    if (expectedWorldId == null || !expectedWorldId.equals(player.getWorld().getUID()))
      return false;
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
    return cfg == null ? Config.empty() : cfg;
  }

  private boolean isFakeChunksEnabled(World world) {
    if (world == null) return false;
    return config().fakeChunksEnabledForWorld(world.getName());
  }

  private void disableFakeChunksForPlayer(
      Player player, UUID playerId, World world, PlayerChunkTracker tracker) {
    if (player == null || playerId == null || world == null || tracker == null) return;
    Set<Long> sent = new HashSet<>(tracker.getSentChunks());
    for (Long chunkKey : sent) {
      int cx = ChunkPos.getX(chunkKey);
      int cz = ChunkPos.getZ(chunkKey);
      tracker.markChunkUnloaded(cx, cz);
      sendUnloadPacket(player, cx, cz);
      refreshCoordinator.removeSubscription(playerId, world.getUID(), chunkKey);
    }
    pendingQueues.remove(playerId);
    queuedSets.remove(playerId);
    inflightCounts.remove(playerId);
    inflightKeys.remove(playerId);
    debug(playerId, "world_disabled", "[EH] fakechunks disabled for world=" + world.getName());
  }

  private final class RefreshLoopActionsImpl implements FakeChunkRefreshLoopService.Actions {

    @Override
    public void refreshChunkForPlayer(
        Player player,
        World world,
        PlayerChunkTracker tracker,
        int chunkX,
        int chunkZ,
        long chunkKey) {
      FakeChunkService.this.refreshChunkForPlayer(player, world, tracker, chunkX, chunkZ, chunkKey);
    }

    @Override
    public void handleRealChunkInteraction(World world, int chunkX, int chunkZ) {
      FakeChunkService.this.handleRealChunkInteraction(world, chunkX, chunkZ);
    }
  }

  private final class DispatchHooksImpl implements FakeChunkDispatchService.Hooks {

    @Override
    public boolean isSessionValid(Player player, UUID expectedWorldId, long expectedEpoch) {
      return FakeChunkService.this.isSessionValid(player, expectedWorldId, expectedEpoch);
    }

    @Override
    public void sendPacketForSession(
        Player player, Packet<?> packet, UUID expectedWorldId, long expectedEpoch) {
      FakeChunkService.this.sendPacketForSession(player, packet, expectedWorldId, expectedEpoch);
    }

    @Override
    public boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable) {
      return FakeChunkService.this.runAtChunk(world, chunkX, chunkZ, runnable);
    }

    @Override
    public void recordChunkLatency(long startedNs) {
      FakeChunkService.this.recordChunkLatency(startedNs);
    }

    @Override
    public void inc(UUID playerId, String metric) {
      FakeChunkService.this.inc(playerId, metric);
    }
  }
}
