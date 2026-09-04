package me.mapacheee.extendedhorizons.fakechunks.session;

import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkSendQueueEntry;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerSession {

    private static final long[] EMPTY_LONG_ARRAY = new long[0];
    private static final ChunkState DUMMY_STATE = new ChunkState();
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.8d;
    private static final double DIRECTION_WEIGHT = 0.3d;
    private static final long FAST_MOVEMENT_NANOS = 400_000_000L;
    private static final long BUILD_FAILED_RETRY_NANOS = 1_000_000_000L;
    private static final long NO_BUILD_RETRY_NANOS = Long.MAX_VALUE;
    private static final int STORAGE_RADIUS_PADDING = 3;
    private static final int UNSET_COORD = 0;
    private static final int PERMISSION_CAP_UNINITIALIZED = -2;
    private static final int OVERRIDE_DISTANCE_UNSET = -1;
    private static final long UNADVERTISED_CHUNK_KEY = ChunkKeyCodec.pack(Integer.MIN_VALUE, Integer.MIN_VALUE);

    private final UUID playerId;
    private volatile UUID worldId;
    private final AtomicLong epoch = new AtomicLong(0L);
    private final AtomicLong sendAttemptSequence = new AtomicLong();
    private volatile long chunkKey = UNADVERTISED_CHUNK_KEY;
    private volatile int distance;
    private volatile int storageRadius;
    private volatile int storageDiameter;
    private volatile int iterationIndex;
    private volatile long nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;
    private volatile int trackingTicker = 0;
    private volatile boolean enabled;
    private volatile boolean initiated;
    private volatile long[] chunksInDistance = EMPTY_LONG_ARRAY;
    private volatile ChunkState[] chunkStates = new ChunkState[0];
    private volatile int lastAdvertisedDistance = OVERRIDE_DISTANCE_UNSET;
    private volatile long lastAdvertisedChunkKey = UNADVERTISED_CHUNK_KEY;
    private volatile int serverViewDistance = 2;
    private volatile int playerOverrideDistance = OVERRIDE_DISTANCE_UNSET;
    private volatile boolean bandwidthLimiterEnabled;
    private volatile long bandwidthBytesPerSecond;
    private volatile long bandwidthCapacityBytes;
    private volatile long bandwidthTokens;
    private volatile long bandwidthLastRefillNanos;
    private final Deque<ChunkSendQueueEntry> chunkQueue = new ConcurrentLinkedDeque<>();
    private final Object dispatchLock = new Object();
    private final Map<UUID, Integer> trackedFarPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> trackingBuffer = ConcurrentHashMap.newKeySet();
    private final Set<Integer> usedFarEntityIdBuffer = ConcurrentHashMap.newKeySet();
    private final Set<Integer> serverTrackedEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> serverLoadedChunks = ConcurrentHashMap.newKeySet();
    private volatile double moveDirX;
    private volatile double moveDirZ;
    private volatile boolean hasMovementDirection;
    private volatile long lastChunkCrossNanos;
    private final List<Long> pendingUnloads = new ArrayList<>();
    private volatile int cachedPermissionCap = PERMISSION_CAP_UNINITIALIZED;
    private volatile boolean cachedHasBypass;
    private volatile long permissionCacheExpiryNanos;
    private volatile boolean closed;

    public PlayerSession(UUID playerId, UUID worldId) {
        this.playerId = playerId;
        this.worldId = worldId;
    }

    public int cachedPermissionCap() {
        return this.cachedPermissionCap;
    }

    public void cachedPermissionCap(int cap) {
        this.cachedPermissionCap = cap;
    }

    public boolean cachedHasBypass() {
        return this.cachedHasBypass;
    }

    public void cachedHasBypass(boolean hasBypass) {
        this.cachedHasBypass = hasBypass;
    }

    public long permissionCacheExpiryNanos() {
        return this.permissionCacheExpiryNanos;
    }

    public void permissionCacheExpiryNanos(long expiry) {
        this.permissionCacheExpiryNanos = expiry;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public UUID worldId() {
        return this.worldId;
    }

    public boolean closed() {
        return this.closed;
    }

    public long epoch() {
        return this.epoch.get();
    }

    public void bumpEpoch() {
        this.epoch.incrementAndGet();
    }

    public int incrementTrackingTicker() {
        return this.trackingTicker = (this.trackingTicker + 1) & Integer.MAX_VALUE;
    }

    public Set<UUID> trackingBuffer() {
        return this.trackingBuffer;
    }

    public Set<Integer> usedFarEntityIdBuffer() {
        return this.usedFarEntityIdBuffer;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean initiated() {
        return this.initiated;
    }

    public void initiated(boolean initiated) {
        this.initiated = initiated;
    }

    public int lastAdvertisedDistance() {
        return this.lastAdvertisedDistance;
    }

    public void lastAdvertisedDistance(int lastAdvertisedDistance) {
        this.lastAdvertisedDistance = lastAdvertisedDistance;
    }

    public long lastAdvertisedChunkKey() {
        return this.lastAdvertisedChunkKey;
    }

    public void lastAdvertisedChunkKey(long key) {
        this.lastAdvertisedChunkKey = key;
    }

    public void invalidateAdvertisedChunkKey() {
        this.lastAdvertisedChunkKey = UNADVERTISED_CHUNK_KEY;
    }

    public int serverViewDistance() {
        return this.serverViewDistance;
    }

    public void serverViewDistance(int serverViewDistance) {
        this.serverViewDistance = Math.max(2, serverViewDistance);
    }

    public int playerOverrideDistance() {
        return this.playerOverrideDistance;
    }

    public void playerOverrideDistance(int distance) {
        this.playerOverrideDistance = distance;
    }

    public void resetPlayerOverrideDistance() {
        this.playerOverrideDistance = OVERRIDE_DISTANCE_UNSET;
    }

    public void setWorld(UUID worldId) {
        this.worldId = worldId;
    }

    public void configureBandwidthLimiter(boolean enabled, long bytesPerSecond, long burstBytes) {
        long normalizedBytesPerSecond = Math.max(1L, bytesPerSecond);
        long normalizedBurst = Math.max(normalizedBytesPerSecond, burstBytes);
        if (this.bandwidthLimiterEnabled == enabled
            && this.bandwidthBytesPerSecond == normalizedBytesPerSecond
            && this.bandwidthCapacityBytes == normalizedBurst) {
            return;
        }

        this.bandwidthLimiterEnabled = enabled;
        this.bandwidthBytesPerSecond = normalizedBytesPerSecond;
        this.bandwidthCapacityBytes = normalizedBurst;
        this.bandwidthTokens = normalizedBurst;
        this.bandwidthLastRefillNanos = System.nanoTime();
    }

    public boolean tryConsumeBandwidth(long bytes) {
        if (!this.bandwidthLimiterEnabled || bytes <= 0L) {
            return true;
        }
        this.refillBandwidthTokens(System.nanoTime());
        long requiredTokens = Math.min(bytes, this.bandwidthCapacityBytes);
        if (this.bandwidthTokens < requiredTokens) {
            return false;
        }
        this.bandwidthTokens -= requiredTokens;
        return true;
    }

    public void resetBandwidthLimiter() {
        this.bandwidthTokens = this.bandwidthCapacityBytes;
        this.bandwidthLastRefillNanos = System.nanoTime();
    }

    public long chunkKey() {
        return this.chunkKey;
    }

    public void setChunkPos(int chunkX, int chunkZ) {
        this.chunkKey = ChunkKeyCodec.pack(chunkX, chunkZ);
    }

    public boolean hasChunkChanged(int chunkX, int chunkZ) {
        return this.chunkKey != ChunkKeyCodec.pack(chunkX, chunkZ);
    }

    public int distance() {
        return this.distance;
    }

    public Deque<ChunkSendQueueEntry> chunkQueue() {
        return this.chunkQueue;
    }

    public synchronized boolean hasPendingChunkWork() {
        if (!this.enabled) {
            return false;
        }
        if (!this.chunkQueue.isEmpty() || this.iterationIndex < this.chunksInDistance.length) {
            return true;
        }
        long retryAt = this.nextBuildRetryNanos;
        if (retryAt != NO_BUILD_RETRY_NANOS && System.nanoTime() - retryAt >= 0L) {
            this.iterationIndex = 0;
            this.nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;
            return true;
        }
        return false;
    }

    public boolean enqueueChunk(ChunkSendQueueEntry entry, UUID expectedWorldId, long expectedEpoch) {
        synchronized (this.dispatchLock) {
            if (this.closed || !this.enabled
                || !expectedWorldId.equals(this.worldId)
                || expectedEpoch != this.epoch.get()) {
                return false;
            }
            this.chunkQueue.addLast(entry);
            return true;
        }
    }

    public Map<UUID, Integer> trackedFarPlayers() {
        return this.trackedFarPlayers;
    }

    public void addServerTrackedEntity(int entityId) {
        this.serverTrackedEntityIds.add(entityId);
    }

    public void removeServerTrackedEntity(int entityId) {
        this.serverTrackedEntityIds.remove(entityId);
    }

    public boolean isServerTrackingEntity(int entityId) {
        return this.serverTrackedEntityIds.contains(entityId);
    }

    public void updateDistance(int newDistance) {
        this.distance = Math.max(2, newDistance);
        this.storageRadius = Math.max(2, this.distance) + STORAGE_RADIUS_PADDING;
        this.storageDiameter = this.storageRadius * 2 + 1;
        this.chunksInDistance = ChunkPlannerService.radiusIterationList(this.distance);
        this.iterationIndex = 0;
        this.nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;

        if (this.hasMovementDirection) {
            this.rebuildDirectionalOrder();
        }

        int size = this.storageDiameter * this.storageDiameter;
        ChunkState[] oldStates = this.chunkStates;
        if (oldStates.length == size) {
            return;
        }

        ChunkState[] newStates = new ChunkState[size];
        int centerX = ChunkKeyCodec.x(this.chunkKey);
        int centerZ = ChunkKeyCodec.z(this.chunkKey);
        for (ChunkState state : oldStates) {
            if (state == null || !state.hasCoords()) {
                continue;
            }
            int chunkX = state.chunkX();
            int chunkZ = state.chunkZ();
            if (!this.canStore(chunkX, chunkZ, centerX, centerZ)) {
                continue;
            }
            int idx = calcIndex(chunkX, chunkZ, this.storageDiameter);
            newStates[idx] = state;
        }
        for (int i = 0; i < size; i++) {
            if (newStates[i] == null) {
                newStates[i] = new ChunkState();
            }
        }
        this.chunkStates = newStates;
    }

    public void moveTo(int chunkX, int chunkZ) {
        long newKey = ChunkKeyCodec.pack(chunkX, chunkZ);
        long previous = this.chunkKey;
        if (newKey == previous) {
            return;
        }
        this.chunkKey = newKey;
        this.iterationIndex = 0;
        this.pruneServerLoadedChunks(chunkX, chunkZ);

        if (this.chunkStates.length == 0) {
            return;
        }

        int prevX = ChunkKeyCodec.x(previous);
        int prevZ = ChunkKeyCodec.z(previous);
        if (distanceSquared(prevX, prevZ, chunkX, chunkZ) > (long) this.distance * this.distance) {
            for (ChunkState state : this.chunkStates) {
                if (state.lifecycle() == ChunkLifecycle.EH_LOADED
                    || state.lifecycle() == ChunkLifecycle.EH_SENDING) {
                    this.pendingUnloads.add(ChunkKeyCodec.pack(state.chunkX(), state.chunkZ()));
                }
                state.reset();
            }
            this.clearChunkQueue();
            this.iterationIndex = 0;
            this.nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;
            this.enabled = false;
            this.hasMovementDirection = false;
            return;
        }

        long now = System.nanoTime();
        boolean movingFast = this.lastChunkCrossNanos > 0
            && (now - this.lastChunkCrossNanos) < FAST_MOVEMENT_NANOS;
        this.lastChunkCrossNanos = now;

        if (movingFast) {
            double moveX = (double) chunkX - prevX;
            double moveZ = (double) chunkZ - prevZ;
            double length = Math.hypot(moveX, moveZ);
            this.updateLookDirection(moveX / length, moveZ / length);
        } else if (this.hasMovementDirection) {
            this.hasMovementDirection = false;
            this.chunksInDistance = ChunkPlannerService.radiusIterationList(this.distance);
            this.iterationIndex = 0;
        }

        boolean cleanedAny = false;
        for (ChunkState state : this.chunkStates) {
            if (!state.hasCoords()) {
                continue;
            }
            int stateX = state.chunkX();
            int stateZ = state.chunkZ();
            if (!this.canStore(stateX, stateZ, chunkX, chunkZ)) {
                ChunkLifecycle lifecycle = state.lifecycle();
                if (lifecycle == ChunkLifecycle.EH_QUEUED) {
                    this.purgeQueuedChunk(stateX, stateZ);
                }
                if (lifecycle == ChunkLifecycle.EH_LOADED || lifecycle == ChunkLifecycle.EH_SENDING) {
                    this.pendingUnloads.add(ChunkKeyCodec.pack(stateX, stateZ));
                }
                state.reset();
                cleanedAny = true;
                continue;
            }
            if (ChunkPlannerService.isWithinRange(stateX - chunkX, stateZ - chunkZ, this.distance)) {
                continue;
            }
            if (state.lifecycle() == ChunkLifecycle.EH_QUEUED) {
                this.purgeQueuedChunk(stateX, stateZ);
            }
            if (state.lifecycle() == ChunkLifecycle.EH_LOADED
                || state.lifecycle() == ChunkLifecycle.EH_SENDING) {
                this.pendingUnloads.add(ChunkKeyCodec.pack(stateX, stateZ));
            }
            if (state.lifecycle() != ChunkLifecycle.SERVER_LOADED) {
                state.reset();
                cleanedAny = true;
            }
        }
        if (cleanedAny) {
            this.iterationIndex = 0;
        }
    }

    public List<Long> drainPendingUnloads() {
        if (this.pendingUnloads.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(this.pendingUnloads);
        this.pendingUnloads.clear();
        return result;
    }

    public void serverChunkAdd(int chunkX, int chunkZ) {
        this.serverLoadedChunks.add(ChunkKeyCodec.pack(chunkX, chunkZ));
        if (this.chunkStates.length == 0 || !this.canStore(chunkX, chunkZ)) {
            return;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        if (state.hasCoords() && !state.matches(chunkX, chunkZ)) {
            state.reset();
        }
        if (state.lifecycle() == ChunkLifecycle.EH_QUEUED) {
            this.purgeQueuedChunk(chunkX, chunkZ);
        }
        state.set(chunkX, chunkZ, ChunkLifecycle.SERVER_LOADED);
    }

    public boolean serverChunkRemove(int chunkX, int chunkZ) {
        this.serverLoadedChunks.remove(ChunkKeyCodec.pack(chunkX, chunkZ));
        if (this.chunkStates.length == 0) {
            return false;
        }
        int centerX = ChunkKeyCodec.x(this.chunkKey);
        int centerZ = ChunkKeyCodec.z(this.chunkKey);
        if (!ChunkPlannerService.isWithinRange(chunkX - centerX, chunkZ - centerZ, this.distance)) {
            if (this.canStore(chunkX, chunkZ, centerX, centerZ)) {
                ChunkState state = this.getState(chunkX, chunkZ);
                if (state.matches(chunkX, chunkZ)) {
                    state.reset();
                }
            }
            return false;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        if (!state.matches(chunkX, chunkZ)) {
            return false;
        }
        ChunkLifecycle lc = state.lifecycle();
        if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_SENDING) {
            return true;
        }
        if (lc == ChunkLifecycle.SERVER_LOADED) {
            state.set(chunkX, chunkZ, ChunkLifecycle.UNLOADED);
            this.iterationIndex = 0;
            return true;
        }
        return false;
    }

    public Long pollNextChunkKey() {
        int centerX = ChunkKeyCodec.x(this.chunkKey);
        int centerZ = ChunkKeyCodec.z(this.chunkKey);
        long[] offsets = this.chunksInDistance;
        while (this.iterationIndex < offsets.length) {
            long off = offsets[this.iterationIndex++];
            int chunkX = ChunkKeyCodec.x(off) + centerX;
            int chunkZ = ChunkKeyCodec.z(off) + centerZ;

            if (!this.canStore(chunkX, chunkZ, centerX, centerZ)) {
                continue;
            }

            ChunkState state = this.getState(chunkX, chunkZ);
            if (this.serverLoadedChunks.contains(ChunkKeyCodec.pack(chunkX, chunkZ))) {
                state.set(chunkX, chunkZ, ChunkLifecycle.SERVER_LOADED);
                continue;
            }
            if (state.lifecycle() == ChunkLifecycle.SERVER_LOADED) {
                state.reset();
            }
            if (state.hasCoords() && !state.matches(chunkX, chunkZ)) {
                state.reset();
            }
            if (state.lifecycle() == ChunkLifecycle.UNLOADED) {
                state.set(chunkX, chunkZ, ChunkLifecycle.EH_QUEUED);
                return ChunkKeyCodec.pack(chunkX, chunkZ);
            }
            if (state.lifecycle() == ChunkLifecycle.BUILD_FAILED) {
                long retryAt = state.failedAtNanos() + BUILD_FAILED_RETRY_NANOS;
                if (System.nanoTime() - retryAt >= 0L) {
                    state.set(chunkX, chunkZ, ChunkLifecycle.EH_QUEUED);
                    return ChunkKeyCodec.pack(chunkX, chunkZ);
                }
                this.scheduleBuildRetry(retryAt);
            }
        }
        return null;
    }

    public void onChunkBuildFailed(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            if (state.lifecycle() == ChunkLifecycle.EH_QUEUED
                || state.lifecycle() == ChunkLifecycle.EH_SENDING) {
                state.markBuildFailed();
                this.iterationIndex = 0;
                this.scheduleBuildRetry(state.failedAtNanos() + BUILD_FAILED_RETRY_NANOS);
            }
        }
    }

    public long beginChunkSend(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            if (state.lifecycle() != ChunkLifecycle.EH_QUEUED) {
                return 0L;
            }
            long attempt = this.sendAttemptSequence.incrementAndGet();
            if (attempt == 0L) {
                attempt = this.sendAttemptSequence.incrementAndGet();
            }
            state.beginSending(ChunkKeyCodec.x(chunkKey), ChunkKeyCodec.z(chunkKey), attempt);
            return attempt;
        }
    }

    public void onChunkSent(long chunkKey, long sendAttempt) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            if (state.lifecycle() == ChunkLifecycle.EH_SENDING && state.sendAttempt() == sendAttempt) {
                state.set(ChunkKeyCodec.x(chunkKey), ChunkKeyCodec.z(chunkKey), ChunkLifecycle.EH_LOADED);
            }
        }
    }

    public void onChunkSendFailed(long chunkKey, long sendAttempt) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            if (state.lifecycle() == ChunkLifecycle.EH_SENDING && state.sendAttempt() == sendAttempt) {
                state.markBuildFailed();
                this.iterationIndex = 0;
                this.scheduleBuildRetry(state.failedAtNanos() + BUILD_FAILED_RETRY_NANOS);
            }
        }
    }

    public void onChunkUnloaded(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            ChunkLifecycle lc = state.lifecycle();
            if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_SENDING
                || lc == ChunkLifecycle.EH_QUEUED || lc == ChunkLifecycle.BUILD_FAILED) {
                state.reset();
            }
        }
    }

    public boolean invalidateChunk(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            ChunkLifecycle lc = state.lifecycle();
            if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_SENDING
                || lc == ChunkLifecycle.EH_QUEUED || lc == ChunkLifecycle.BUILD_FAILED) {
                boolean wasLoaded = lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_SENDING;
                if (lc == ChunkLifecycle.EH_QUEUED) {
                    int cx = ChunkKeyCodec.x(chunkKey);
                    int cz = ChunkKeyCodec.z(chunkKey);
                    this.purgeQueuedChunk(cx, cz);
                }
                state.reset();
                this.iterationIndex = 0;
                return wasLoaded;
            }
            return false;
        }
    }

    public void invalidatePendingChunk(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        synchronized (state) {
            ChunkLifecycle lifecycle = state.lifecycle();
            if (lifecycle == ChunkLifecycle.EH_QUEUED) {
                this.purgeQueuedChunk(ChunkKeyCodec.x(chunkKey), ChunkKeyCodec.z(chunkKey));
                state.reset();
                this.iterationIndex = 0;
            } else if (lifecycle == ChunkLifecycle.BUILD_FAILED) {
                state.reset();
                this.iterationIndex = 0;
            }
        }
    }

    public boolean isEhLoaded(long chunkKey) {
        return this.getStateByKey(chunkKey).lifecycle() == ChunkLifecycle.EH_LOADED;
    }

    public boolean isChunkReadyForEntities(long chunkKey) {
        int targetChunkX = ChunkKeyCodec.x(chunkKey);
        int targetChunkZ = ChunkKeyCodec.z(chunkKey);
        int currentChunkX = ChunkKeyCodec.x(this.chunkKey);
        int currentChunkZ = ChunkKeyCodec.z(this.chunkKey);
        int dx = targetChunkX - currentChunkX;
        int dz = targetChunkZ - currentChunkZ;
        int serverDist = this.serverViewDistance;
        if (dx * dx + dz * dz <= serverDist * serverDist) {
            return true;
        }
        ChunkLifecycle lifecycle = this.getStateByKey(chunkKey).lifecycle();
        return lifecycle == ChunkLifecycle.SERVER_LOADED || lifecycle == ChunkLifecycle.EH_LOADED;
    }

    public boolean shouldReceiveBlockUpdate(long chunkKey) {
        ChunkLifecycle lifecycle = this.getStateByKey(chunkKey).lifecycle();
        return lifecycle == ChunkLifecycle.EH_LOADED || lifecycle == ChunkLifecycle.EH_SENDING;
    }


    public long[] loadedBvChunkKeys() {
        if (this.chunkStates.length == 0) {
            return EMPTY_LONG_ARRAY;
        }
        int count = 0;
        for (ChunkState state : this.chunkStates) {
            if (state.lifecycle() == ChunkLifecycle.EH_LOADED
                || state.lifecycle() == ChunkLifecycle.EH_SENDING) {
                count++;
            }
        }
        if (count == 0) {
            return EMPTY_LONG_ARRAY;
        }
        long[] keys = new long[count];
        int index = 0;
        for (ChunkState state : this.chunkStates) {
            if (state.lifecycle() == ChunkLifecycle.EH_LOADED
                || state.lifecycle() == ChunkLifecycle.EH_SENDING) {
                keys[index++] = ChunkKeyCodec.pack(state.chunkX(), state.chunkZ());
            }
        }
        return keys;
    }

    public void unloadEhChunks() {
        for (ChunkState state : this.chunkStates) {
            ChunkLifecycle lc = state.lifecycle();
            if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_SENDING
                || lc == ChunkLifecycle.EH_QUEUED || lc == ChunkLifecycle.BUILD_FAILED) {
                state.reset();
            }
        }
        this.clearChunkQueue();
        this.usedFarEntityIdBuffer.clear();
        this.nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;
    }

    public synchronized void handleDimensionReset() {
        this.bumpEpoch();
        this.enabled = false;
        for (ChunkState state : this.chunkStates) {
            state.reset();
        }
        this.clearChunkQueue();
        this.trackingBuffer.clear();
        this.usedFarEntityIdBuffer.clear();
        this.serverTrackedEntityIds.clear();
        this.serverLoadedChunks.clear();
        this.hasMovementDirection = false;
        this.lastChunkCrossNanos = 0L;
        this.resetBandwidthLimiter();
        this.iterationIndex = 0;
        this.nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;
        this.lastAdvertisedDistance = OVERRIDE_DISTANCE_UNSET;
        this.lastAdvertisedChunkKey = UNADVERTISED_CHUNK_KEY;
    }

    public void clearDispatchState() {
        this.enabled = false;
        this.clearChunkQueue();
        this.trackingBuffer.clear();
        this.usedFarEntityIdBuffer.clear();
        this.serverTrackedEntityIds.clear();
        this.resetBandwidthLimiter();
        this.lastAdvertisedDistance = OVERRIDE_DISTANCE_UNSET;
        this.lastAdvertisedChunkKey = UNADVERTISED_CHUNK_KEY;
        this.initiated = false;
        this.iterationIndex = 0;
        this.nextBuildRetryNanos = NO_BUILD_RETRY_NANOS;
    }

    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.bumpEpoch();
        this.clearDispatchState();
        this.serverLoadedChunks.clear();
    }

    private void updateLookDirection(double newDirX, double newDirZ) {
        if (this.hasMovementDirection) {
            double dot = this.moveDirX * newDirX + this.moveDirZ * newDirZ;
            if (dot >= DIRECTION_CHANGE_THRESHOLD) {
                return;
            }
        }

        this.moveDirX = newDirX;
        this.moveDirZ = newDirZ;
        this.hasMovementDirection = true;
        this.rebuildDirectionalOrder();
    }

    private void rebuildDirectionalOrder() {
        long[] base = ChunkPlannerService.radiusIterationList(this.distance);
        int len = base.length;
        double dirX = this.moveDirX;
        double dirZ = this.moveDirZ;

        int[] indices = new int[len];
        double[] keys = new double[len];
        for (int i = 0; i < len; i++) {
            indices[i] = i;
            int ox = ChunkKeyCodec.x(base[i]);
            int oz = ChunkKeyCodec.z(base[i]);
            double dist = Math.sqrt(ox * ox + oz * oz);
            if (dist <= 0) {
                keys[i] = -1.0d;
            } else {
                double alignment = (ox * dirX + oz * dirZ) / dist;
                keys[i] = dist * (1.0d - DIRECTION_WEIGHT * alignment);
            }
        }

        for (int i = 1; i < len; i++) {
            int current = indices[i];
            double currentKey = keys[current];
            int j = i - 1;
            while (j >= 0 && keys[indices[j]] > currentKey) {
                indices[j + 1] = indices[j];
                j--;
            }
            indices[j + 1] = current;
        }

        long[] sorted = new long[len];
        for (int i = 0; i < len; i++) {
            sorted[i] = base[indices[i]];
        }
        this.chunksInDistance = sorted;
        this.iterationIndex = 0;
    }

    private void refillBandwidthTokens(long nowNanos) {
        long last = this.bandwidthLastRefillNanos;
        if (last <= 0L) {
            this.bandwidthLastRefillNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - last;
        if (elapsed <= 0L) {
            return;
        }
        long add = (elapsed * this.bandwidthBytesPerSecond) / 1_000_000_000L;
        if (add <= 0L) {
            return;
        }
        long next = this.bandwidthTokens + add;
        if (next < 0L || next > this.bandwidthCapacityBytes) {
            next = this.bandwidthCapacityBytes;
        }
        this.bandwidthTokens = next;
        this.bandwidthLastRefillNanos = nowNanos;
    }

    private void purgeQueuedChunk(int chunkX, int chunkZ) {
        synchronized (this.dispatchLock) {
            this.chunkQueue.removeIf(entry -> {
                if (entry.chunkKey() == ChunkKeyCodec.pack(chunkX, chunkZ)) {
                    entry.releaseFuture();
                    return true;
                }
                return false;
            });
        }
    }

    private void scheduleBuildRetry(long retryAtNanos) {
        if (retryAtNanos < this.nextBuildRetryNanos) {
            this.nextBuildRetryNanos = retryAtNanos;
        }
    }

    private void pruneServerLoadedChunks(int centerX, int centerZ) {
        int retentionRadius = this.serverViewDistance + STORAGE_RADIUS_PADDING;
        this.serverLoadedChunks.removeIf(key ->
            Math.abs((long) ChunkKeyCodec.x(key) - centerX) > retentionRadius
                || Math.abs((long) ChunkKeyCodec.z(key) - centerZ) > retentionRadius
        );
    }

    private void clearChunkQueue() {
        synchronized (this.dispatchLock) {
            ChunkSendQueueEntry entry;
            while ((entry = this.chunkQueue.pollFirst()) != null) {
                entry.releaseFuture();
            }
        }
    }

    private ChunkState getStateByKey(long chunkKey) {
        int chunkX = ChunkKeyCodec.x(chunkKey);
        int chunkZ = ChunkKeyCodec.z(chunkKey);
        if (this.chunkStates.length == 0 || !this.canStore(chunkX, chunkZ)) {
            return DUMMY_STATE;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        return !state.hasCoords() || state.matches(chunkX, chunkZ) ? state : DUMMY_STATE;
    }

    private ChunkState getState(int chunkX, int chunkZ) {
        if (this.chunkStates.length == 0) {
            return DUMMY_STATE;
        }
        return this.chunkStates[calcIndex(chunkX, chunkZ, this.storageDiameter)];
    }

    private boolean canStore(int chunkX, int chunkZ) {
        int centerX = ChunkKeyCodec.x(this.chunkKey);
        int centerZ = ChunkKeyCodec.z(this.chunkKey);
        return this.canStore(chunkX, chunkZ, centerX, centerZ);
    }

    private boolean canStore(int chunkX, int chunkZ, int centerX, int centerZ) {
        return Math.abs((long) chunkX - centerX) <= this.storageRadius
            && Math.abs((long) chunkZ - centerZ) <= this.storageRadius;
    }

    private static int calcIndex(int chunkX, int chunkZ, int storageDiameter) {
        return Math.floorMod(chunkX, storageDiameter) * storageDiameter + Math.floorMod(chunkZ, storageDiameter);
    }

    private static long distanceSquared(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    public enum ChunkLifecycle {
        UNLOADED,
        SERVER_LOADED,
        EH_QUEUED,
        EH_SENDING,
        EH_LOADED,
        BUILD_FAILED,
    }

    public static final class ChunkState {

        private volatile int chunkX;
        private volatile int chunkZ;
        private volatile ChunkLifecycle lifecycle = ChunkLifecycle.UNLOADED;
        private volatile long failedAtNanos;
        private volatile long sendAttempt;

        public int chunkX() {
            return this.chunkX;
        }

        public int chunkZ() {
            return this.chunkZ;
        }

        public ChunkLifecycle lifecycle() {
            return this.lifecycle;
        }

        public long failedAtNanos() {
            return this.failedAtNanos;
        }

        public long sendAttempt() {
            return this.sendAttempt;
        }

        public synchronized void set(int chunkX, int chunkZ, ChunkLifecycle lifecycle) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.lifecycle = lifecycle;
            this.sendAttempt = 0L;
        }

        public synchronized void beginSending(int chunkX, int chunkZ, long attempt) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sendAttempt = attempt;
            this.lifecycle = ChunkLifecycle.EH_SENDING;
        }

        public synchronized void markBuildFailed() {
            this.lifecycle = ChunkLifecycle.BUILD_FAILED;
            this.failedAtNanos = System.nanoTime();
            this.sendAttempt = 0L;
        }

        public synchronized void reset() {
            this.set(UNSET_COORD, UNSET_COORD, ChunkLifecycle.UNLOADED);
            this.failedAtNanos = 0L;
        }

        public boolean hasCoords() {
            return this.lifecycle != ChunkLifecycle.UNLOADED || this.chunkX != UNSET_COORD || this.chunkZ != UNSET_COORD;
        }

        private boolean matches(int chunkX, int chunkZ) {
            return this.chunkX == chunkX && this.chunkZ == chunkZ;
        }
    }
}

