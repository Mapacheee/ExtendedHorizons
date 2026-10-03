package me.mapacheee.extendedhorizons.fakechunks.farplayers;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public final class FarPlayerTrackingService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FarPlayerTrackingService.class);
  private static final int FAR_ENTITY_ID_RANGE_START = 1_000_000_000;
  private static final int FAR_ENTITY_ID_RANGE_END = 1_900_000_000;
  private static final int FAR_ENTITY_ID_ALLOCATION_ATTEMPTS = 10_000;
  private static final double FAR_RADIUS_PADDING = 0.35d;
  private static final int CHUNK_SHIFT = 4;
  private static final int ALLOCATION_FAILED = -1;
  private static final int SPAWN_RETRY_SECONDS = 5;

  private final Container<EhConfig> configContainer;
  private final FarPlayerCacheService cacheService;
  private final FarPlayerBackend backend;
  private final ChannelInjectionService channelInjectionService;
  private final AtomicLong spawnAttemptSequence = new AtomicLong();
  private final Cache<SpawnRetryKey, Boolean> spawnRetries = Caffeine.newBuilder()
    .maximumSize(4096)
    .expireAfterWrite(Duration.ofSeconds(SPAWN_RETRY_SECONDS))
    .build();
  private final Cache<UUID, Boolean> failureWarnings = Caffeine.newBuilder()
    .maximumSize(1024)
    .expireAfterWrite(Duration.ofSeconds(30))
    .build();

  @Inject
  public FarPlayerTrackingService(
    Container<EhConfig> configContainer,
    FarPlayerCacheService cacheService,
    FarPlayerBackend backend,
    ChannelInjectionService channelInjectionService
  ) {
    this.configContainer = configContainer;
    this.cacheService = cacheService;
    this.backend = backend;
    this.channelInjectionService = channelInjectionService;
  }

  public void track(
    UUID viewerId,
    long viewerChunkKey,
    PlayerSession session,
    Channel channel,
    int targetDistance,
    Collection<FarPlayerState> candidates
  ) {
    int viewerChunkX = ChunkKeyCodec.x(viewerChunkKey);
    int viewerChunkZ = ChunkKeyCodec.z(viewerChunkKey);

    int tick = session.incrementTrackingTicker();
    EhConfig config = this.configContainer.get();
    int moveTicks = Math.max(1, config.farPlayerMoveTicks());
    int equipTicks = Math.max(1, config.farPlayerEquipTicks());
    int equipInterval = Math.max(1, equipTicks / moveTicks);
    boolean syncMove = true;
    boolean syncEquip = Math.floorMod(tick, equipInterval) == 0;

    double farLimit = targetDistance + FAR_RADIUS_PADDING;
    double farLimitSq = farLimit * farLimit;

    Map<UUID, Integer> trackedFarPlayers = session.trackedFarPlayers();
    session.farPlayerSpawnAttempts().keySet().retainAll(trackedFarPlayers.keySet());
    Set<Integer> usedFarEntityIds = session.usedFarEntityIdBuffer();
    usedFarEntityIds.clear();
    usedFarEntityIds.addAll(trackedFarPlayers.values());

    Set<UUID> newlyRetained = session.trackingBuffer();
    newlyRetained.clear();

    for (FarPlayerState state : candidates) {
      if (state.uuid().equals(viewerId)) {
        continue;
      }

      Integer trackedEntityId = trackedFarPlayers.get(state.uuid());
      boolean alreadyTracked = trackedEntityId != null;

      if (session.isServerTrackingEntity(state.entityId())) {
        if (alreadyTracked) {
          this.despawnAndRemove(channel, trackedFarPlayers, usedFarEntityIds, state.uuid(), trackedEntityId);
        }
        continue;
      }

      int stateChunkX = (int) Math.floor(state.x()) >> CHUNK_SHIFT;
      int stateChunkZ = (int) Math.floor(state.z()) >> CHUNK_SHIFT;
      long stateChunkKey = ChunkKeyCodec.pack(stateChunkX, stateChunkZ);
      int relChunkX = stateChunkX - viewerChunkX;
      int relChunkZ = stateChunkZ - viewerChunkZ;
      double distSq = (double) relChunkX * relChunkX + (double) relChunkZ * relChunkZ;

      if (distSq > farLimitSq) {
        if (alreadyTracked) {
          this.despawnAndRemove(channel, trackedFarPlayers, usedFarEntityIds, state.uuid(), trackedEntityId);
        }
        continue;
      }

      if (!session.isChunkReadyForEntities(stateChunkKey)) {
        if (alreadyTracked) {
          this.despawnAndRemove(channel, trackedFarPlayers, usedFarEntityIds, state.uuid(), trackedEntityId);
        }
        continue;
      }

      newlyRetained.add(state.uuid());
      if (!alreadyTracked) {
        if (this.spawnRetries.getIfPresent(new SpawnRetryKey(session, session.epoch(), state.uuid())) != null) {
          continue;
        }
        int farEntityId = this.allocateFarEntityId(usedFarEntityIds, state.uuid(), state.entityId());
        if (farEntityId == ALLOCATION_FAILED) {
          continue;
        }
        this.spawn(channel, session, state, farEntityId);
      } else {
        this.moveAndSync(channel, trackedEntityId, state, syncMove, syncEquip);
      }
    }

    Iterator<Map.Entry<UUID, Integer>> iterator = trackedFarPlayers.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Integer> entry = iterator.next();
      if (!newlyRetained.contains(entry.getKey())) {
        this.despawn(channel, entry.getValue());
        iterator.remove();
        usedFarEntityIds.remove(entry.getValue());
      }
    }
    session.farPlayerSpawnAttempts().keySet().retainAll(trackedFarPlayers.keySet());
  }

  private void spawn(
    Channel channel,
    PlayerSession session,
    FarPlayerState state,
    int farEntityId
  ) {
    long expectedEpoch = session.epoch();
    long attemptId = this.spawnAttemptSequence.incrementAndGet();
    session.farPlayerSpawnAttempts().put(state.uuid(), attemptId);
    if (state.playerInfo() == null) {
      this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
        "player profile is missing", null);
      return;
    }
    FarPlayerState packetState = this.withEntityId(state, farEntityId);
    Object playerInfoPacket;
    try {
      playerInfoPacket = this.backend.createPlayerInfoPacket(packetState);
    } catch (RuntimeException | LinkageError exception) {
      this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
        "profile packet creation failed", exception);
      return;
    }
    if (playerInfoPacket == null) {
      this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
        "profile packet is missing", null);
      return;
    }
    ChannelPromise profilePromise;
    try {
      profilePromise = this.channelInjectionService.writeBypassFuture(channel, playerInfoPacket);
    } catch (RuntimeException | LinkageError exception) {
      this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
        "profile packet write failed", exception);
      return;
    }
    if (profilePromise == null || (profilePromise.isDone() && !profilePromise.isSuccess())) {
      this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
        "profile packet write failed", profilePromise == null ? null : profilePromise.cause());
      return;
    }

    Map<UUID, Integer> trackedFarPlayers = session.trackedFarPlayers();
    Set<Integer> usedFarEntityIds = session.usedFarEntityIdBuffer();
    trackedFarPlayers.put(state.uuid(), farEntityId);
    usedFarEntityIds.add(farEntityId);
    profilePromise.addListener(future -> {
      if (!future.isSuccess()) {
        this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
          "profile packet write failed", future.cause());
      }
    });
    if (!Objects.equals(trackedFarPlayers.get(state.uuid()), farEntityId)) {
      return;
    }

    try {
      ChannelPromise spawnPromise = this.channelInjectionService.writeBypassFuture(
        channel,
        this.backend.createSpawnPacket(packetState)
      );
      if (spawnPromise == null) {
        this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
          "spawn packet is missing", null);
        return;
      }
      spawnPromise.addListener(future -> {
        if (!future.isSuccess()) {
          this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
            "spawn packet write failed", future.cause());
        }
      });
      if (!Objects.equals(trackedFarPlayers.get(state.uuid()), farEntityId)) {
        return;
      }

      if (state.metadata() != null && !state.metadata().isEmpty()) {
        this.channelInjectionService.writeBypass(channel,
          this.backend.createMetadataPacket(farEntityId, state.metadata()));
      }

      this.channelInjectionService.writeBypass(channel,
        this.backend.createRotateHeadPacket(farEntityId, state.headYaw()));

      if (state.equipment() != null && !state.equipment().isEmpty()) {
        this.channelInjectionService.writeBypass(channel,
          this.backend.createEquipmentPacket(farEntityId, state.equipment()));
      }
    } catch (RuntimeException | LinkageError exception) {
      this.onSpawnFailed(channel, session, state, farEntityId, expectedEpoch, attemptId,
        "spawn packet preparation or write failed", exception);
    }
  }

  private void onSpawnFailed(Channel channel, PlayerSession session, FarPlayerState state,
    int farEntityId, long expectedEpoch, long attemptId, String reason, Throwable cause) {
    synchronized (session) {
      if (session.closed() || session.epoch() != expectedEpoch || !session.worldId().equals(state.worldId())
        || !session.farPlayerSpawnAttempts().remove(state.uuid(), attemptId)) {
        return;
      }
      if (session.trackedFarPlayers().remove(state.uuid(), farEntityId)) {
        session.usedFarEntityIdBuffer().remove(farEntityId);
        this.despawn(channel, farEntityId);
      }
      SpawnRetryKey retryKey = new SpawnRetryKey(session, expectedEpoch, state.uuid());
      if (this.spawnRetries.asMap().putIfAbsent(retryKey, Boolean.TRUE) != null) {
        return;
      }
      if (this.failureWarnings.asMap().putIfAbsent(state.uuid(), Boolean.TRUE) == null) {
        LOGGER.warn("Failed to initialize far player {} for viewer {}: {} (retry in {}s)",
          state.uuid(), session.playerId(), reason, SPAWN_RETRY_SECONDS);
      }
      if (this.configContainer.get().debugEnabled()) {
        LOGGER.info("EH far-player failure: viewer={} target={} world={} chunk=[{}, {}] entityId={} "
            + "reason={} profilePresent={} channelActive={} channelWritable={} retrySeconds={}",
          session.playerId(), state.uuid(), state.worldId(),
          (int) Math.floor(state.x()) >> CHUNK_SHIFT, (int) Math.floor(state.z()) >> CHUNK_SHIFT,
          farEntityId, reason, state.playerInfo() != null,
          channel != null && channel.isActive(), channel != null && channel.isWritable(),
          SPAWN_RETRY_SECONDS, cause);
      }
    }
  }

  @OnDisable
  public void onDisable() {
    this.spawnRetries.invalidateAll();
    this.failureWarnings.invalidateAll();
  }

  private record SpawnRetryKey(PlayerSession session, long epoch, UUID targetId) {
  }

  private void moveAndSync(Channel channel, int trackedEntityId, FarPlayerState state, boolean syncMove,
    boolean syncEquip) {
    if (syncMove) {
      FarPlayerState packetState = this.withEntityId(state, trackedEntityId);
      this.channelInjectionService.writeBypass(channel, this.backend.createMovePacket(packetState));
      this.channelInjectionService.writeBypass(channel,
        this.backend.createRotateHeadPacket(trackedEntityId, state.headYaw()));

      if (state.metadata() != null && !state.metadata().isEmpty()) {
        this.channelInjectionService.writeBypass(channel,
          this.backend.createMetadataPacket(trackedEntityId, state.metadata()));
      }
    }

    if (syncEquip && state.equipment() != null && !state.equipment().isEmpty()) {
      this.channelInjectionService.writeBypass(channel,
        this.backend.createEquipmentPacket(trackedEntityId, state.equipment()));
    }
  }

  private void despawnAndRemove(
    Channel channel,
    Map<UUID, Integer> trackedFarPlayers,
    Set<Integer> usedFarEntityIds,
    UUID uuid,
    int entityId
  ) {
    this.despawn(channel, entityId);
    trackedFarPlayers.remove(uuid, entityId);
    usedFarEntityIds.remove(entityId);
  }

  private void despawn(Channel channel, int entityId) {
    if (channel != null && channel.isActive()) {
      this.channelInjectionService.writeBypass(channel, this.backend.createDespawnPacket(entityId));
    }
  }

  public void clearTracked(Channel channel, PlayerSession session) {
    if (session == null) {
      return;
    }
    session.farPlayerSpawnAttempts().clear();
    if (channel == null) {
      return;
    }
    Map<UUID, Integer> tracked = session.trackedFarPlayers();
    if (!tracked.isEmpty()) {
      if (channel.isActive()) {
        for (int entityId : tracked.values()) {
          this.despawn(channel, entityId);
        }
      }
      tracked.clear();
    }
    session.trackingBuffer().clear();
    session.usedFarEntityIdBuffer().clear();
  }

  private int allocateFarEntityId(Set<Integer> usedFarEntityIds, UUID targetUuid, int realEntityId) {
    int idRange = FAR_ENTITY_ID_RANGE_END - FAR_ENTITY_ID_RANGE_START;
    int candidate = FAR_ENTITY_ID_RANGE_START + Math.floorMod(targetUuid.hashCode(), idRange);
    for (int attempts = 0; attempts < FAR_ENTITY_ID_ALLOCATION_ATTEMPTS; attempts++) {
      if (candidate != realEntityId && !usedFarEntityIds.contains(candidate)) {
        return candidate;
      }
      candidate++;
      if (candidate >= FAR_ENTITY_ID_RANGE_END) {
        candidate = FAR_ENTITY_ID_RANGE_START;
      }
    }
    return ALLOCATION_FAILED;
  }

  private FarPlayerState withEntityId(FarPlayerState state, int entityId) {
    if (state.entityId() == entityId) {
      return state;
    }
    return new FarPlayerState(
      entityId,
      state.uuid(),
      state.worldId(),
      state.playerInfo(),
      state.x(),
      state.y(),
      state.z(),
      state.yaw(),
      state.pitch(),
      state.headYaw(),
      state.equipment(),
      state.metadata()
    );
  }
}
