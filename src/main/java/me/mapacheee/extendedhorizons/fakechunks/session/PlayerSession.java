package me.mapacheee.extendedhorizons.fakechunks.session;

import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkSendQueueEntry;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

public final class PlayerSession {

    private static final long[] EMPTY_LONG_ARRAY = new long[0];
    private static final ChunkState DUMMY_STATE = new ChunkState();
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.8d;
    private static final double DIRECTION_WEIGHT = 0.3d;
    private static final long FAST_MOVEMENT_NANOS = 400_000_000L;
    private static final long BUILD_FAILED_RETRY_NANOS = 1_000_000_000L;
    private final UUID playerId;
    private volatile UUID worldId;
    private final AtomicLong epoch = new AtomicLong(0L);
    private volatile long chunkKey = ChunkKeyCodec.pack(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private volatile int distance;
    private volatile int storageRadius;
    private volatile int storageDiameter;
    private volatile int iterationIndex;
    private volatile int trackingTicker = 0;
    private volatile boolean enabled;
    private volatile boolean initiated;
    private volatile long[] chunksInDistance = EMPTY_LONG_ARRAY;
    private volatile ChunkState[] chunkStates = new ChunkState[0];
    private volatile int lastAdvertisedDistance = -1;
    private volatile int serverViewDistance = 2;
    private volatile int playerOverrideDistance = -1;
    private volatile boolean bandwidthLimiterEnabled;
    private volatile long bandwidthBytesPerSecond;
    private volatile long bandwidthCapacityBytes;
    private volatile long bandwidthTokens;
    private volatile long bandwidthLastRefillNanos;
    private final Deque<ChunkSendQueueEntry> chunkQueue = new ConcurrentLinkedDeque<>();
    private final Map<UUID, Integer> trackedFarPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> trackingBuffer = new HashSet<>();
    private final Set<Integer> serverTrackedEntityIds = ConcurrentHashMap.newKeySet();
    private volatile double moveDirX;
    private volatile double moveDirZ;
    private volatile boolean hasMovementDirection;
    private volatile long lastChunkCrossNanos;
    private final List<Long> pendingUnloads = new ArrayList<>();

    public PlayerSession(UUID playerId, UUID worldId) {
        this.playerId = playerId;
        this.worldId = worldId;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public UUID worldId() {
        return this.worldId;
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
        this.playerOverrideDistance = -1;
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
        if (this.bandwidthTokens < bytes) {
            return false;
        }
        this.bandwidthTokens -= bytes;
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
        this.storageRadius = Math.max(2, this.distance) + 3;
        this.storageDiameter = this.storageRadius * 2 + 1;
        this.chunksInDistance = ChunkPlannerService.radiusIterationList(this.distance);
        this.iterationIndex = 0;

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

    public void moveTo(int chunkX, int chunkZ, float yaw) {
        long newKey = ChunkKeyCodec.pack(chunkX, chunkZ);
        long previous = this.chunkKey;
        if (newKey == previous) {
            return;
        }
        this.chunkKey = newKey;
        this.iterationIndex = 0;

        if (!this.enabled || this.chunkStates.length == 0) {
            return;
        }

        int prevX = ChunkKeyCodec.x(previous);
        int prevZ = ChunkKeyCodec.z(previous);
        if (distanceSquared(prevX, prevZ, chunkX, chunkZ) > this.distance * this.distance) {
            for (ChunkState state : this.chunkStates) {
                if (state.lifecycle() == ChunkLifecycle.EH_LOADED) {
                    this.pendingUnloads.add(ChunkKeyCodec.pack(state.chunkX(), state.chunkZ()));
                }
                state.reset();
            }
            this.clearChunkQueue();
            this.iterationIndex = 0;
            this.enabled = false;
            this.hasMovementDirection = false;
            return;
        }

        long now = System.nanoTime();
        boolean movingFast = this.lastChunkCrossNanos > 0
            && (now - this.lastChunkCrossNanos) < FAST_MOVEMENT_NANOS;
        this.lastChunkCrossNanos = now;

        if (movingFast) {
            double radians = Math.toRadians(yaw);
            this.updateLookDirection(-Math.sin(radians), Math.cos(radians));
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
            if (ChunkPlannerService.isWithinRange(stateX - chunkX, stateZ - chunkZ, this.distance)) {
                continue;
            }
            if (state.lifecycle() == ChunkLifecycle.EH_QUEUED) {
                this.purgeQueuedChunk(stateX, stateZ);
            }
            if (state.lifecycle() == ChunkLifecycle.EH_LOADED) {
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
        if (this.chunkStates.length == 0 || !this.canStore(chunkX, chunkZ)) {
            return;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        if (state.lifecycle() == ChunkLifecycle.EH_QUEUED) {
            this.purgeQueuedChunk(chunkX, chunkZ);
        }
        state.set(chunkX, chunkZ, ChunkLifecycle.SERVER_LOADED);
    }

    public boolean serverChunkRemove(int chunkX, int chunkZ) {
        if (this.chunkStates.length == 0) {
            return false;
        }
        int centerX = ChunkKeyCodec.x(this.chunkKey);
        int centerZ = ChunkKeyCodec.z(this.chunkKey);
        if (!ChunkPlannerService.isWithinRange(chunkX - centerX, chunkZ - centerZ, this.distance)) {
            if (this.canStore(chunkX, chunkZ, centerX, centerZ)) {
                this.getState(chunkX, chunkZ).reset();
            }
            return false;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        ChunkLifecycle lc = state.lifecycle();
        if (lc == ChunkLifecycle.EH_LOADED) {
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
            if (state.lifecycle() == ChunkLifecycle.UNLOADED) {
                state.set(chunkX, chunkZ, ChunkLifecycle.EH_QUEUED);
                return ChunkKeyCodec.pack(chunkX, chunkZ);
            }
            if (state.lifecycle() == ChunkLifecycle.BUILD_FAILED
                && System.nanoTime() - state.failedAtNanos() > BUILD_FAILED_RETRY_NANOS) {
                state.set(chunkX, chunkZ, ChunkLifecycle.EH_QUEUED);
                return ChunkKeyCodec.pack(chunkX, chunkZ);
            }
        }
        return null;
    }

    public void onChunkBuildFailed(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        if (state.lifecycle() == ChunkLifecycle.EH_QUEUED) {
            state.markBuildFailed();
            this.iterationIndex = 0;
        }
    }

    public void onChunkSent(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        if (state.lifecycle() == ChunkLifecycle.EH_QUEUED) {
            state.set(ChunkKeyCodec.x(chunkKey), ChunkKeyCodec.z(chunkKey), ChunkLifecycle.EH_LOADED);
        }
    }

    public void onChunkUnloaded(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        ChunkLifecycle lc = state.lifecycle();
        if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_QUEUED || lc == ChunkLifecycle.BUILD_FAILED) {
            state.reset();
        }
    }

    public boolean invalidateChunk(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        ChunkLifecycle lc = state.lifecycle();
        if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_QUEUED || lc == ChunkLifecycle.BUILD_FAILED) {
            boolean wasLoaded = (lc == ChunkLifecycle.EH_LOADED);
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

    public long[] loadedBvChunkKeys() {
        if (this.chunkStates.length == 0) {
            return EMPTY_LONG_ARRAY;
        }
        LongStream.Builder builder = LongStream.builder();
        for (ChunkState state : this.chunkStates) {
            if (state.lifecycle() == ChunkLifecycle.EH_LOADED) {
                builder.add(ChunkKeyCodec.pack(state.chunkX(), state.chunkZ()));
            }
        }
        return builder.build().toArray();
    }

    public void unloadEhChunks() {
        for (ChunkState state : this.chunkStates) {
            ChunkLifecycle lc = state.lifecycle();
            if (lc == ChunkLifecycle.EH_LOADED || lc == ChunkLifecycle.EH_QUEUED || lc == ChunkLifecycle.BUILD_FAILED) {
                state.reset();
            }
        }
        this.clearChunkQueue();
    }

    public void handleDimensionReset() {
        for (ChunkState state : this.chunkStates) {
            state.reset();
        }
        this.clearChunkQueue();
        this.trackingBuffer.clear();
        this.serverTrackedEntityIds.clear();
        this.hasMovementDirection = false;
        this.lastChunkCrossNanos = 0L;
        this.resetBandwidthLimiter();
        this.enabled = false;
        this.iterationIndex = 0;
        this.lastAdvertisedDistance = -1;
    }

    public void clearDispatchState() {
        this.clearChunkQueue();
        this.trackingBuffer.clear();
        this.serverTrackedEntityIds.clear();
        this.resetBandwidthLimiter();
        this.lastAdvertisedDistance = -1;
        this.enabled = false;
        this.initiated = false;
        this.iterationIndex = 0;
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

        Integer[] indices = new Integer[len];
        for (int i = 0; i < len; i++) {
            indices[i] = i;
        }

        Arrays.sort(indices, Comparator.comparingDouble(i -> {
            int ox = ChunkKeyCodec.x(base[i]);
            int oz = ChunkKeyCodec.z(base[i]);
            double dist = Math.sqrt(ox * ox + oz * oz);
            if (dist <= 0) {
                return -1.0d;
            }
            double alignment = (ox * dirX + oz * dirZ) / dist;
            return dist * (1.0d - DIRECTION_WEIGHT * alignment);
        }));

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
        this.chunkQueue.removeIf(entry -> {
            if (entry.chunkKey() == ChunkKeyCodec.pack(chunkX, chunkZ)) {
                entry.releaseFuture();
                return true;
            }
            return false;
        });
    }

    private void clearChunkQueue() {
        for (ChunkSendQueueEntry entry : this.chunkQueue) {
            entry.releaseFuture();
        }
        this.chunkQueue.clear();
    }

    private ChunkState getStateByKey(long chunkKey) {
        int chunkX = ChunkKeyCodec.x(chunkKey);
        int chunkZ = ChunkKeyCodec.z(chunkKey);
        if (this.chunkStates.length == 0 || !this.canStore(chunkX, chunkZ)) {
            return DUMMY_STATE;
        }
        return this.getState(chunkX, chunkZ);
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
        return Math.abs(chunkX - centerX) <= this.storageRadius && Math.abs(chunkZ - centerZ) <= this.storageRadius;
    }

    private static int calcIndex(int chunkX, int chunkZ, int storageDiameter) {
        return Math.floorMod(chunkX, storageDiameter) * storageDiameter + Math.floorMod(chunkZ, storageDiameter);
    }

    private static int distanceSquared(int x1, int z1, int x2, int z2) {
        int dx = x1 - x2;
        int dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    public enum ChunkLifecycle {
        UNLOADED,
        SERVER_LOADED,
        EH_QUEUED,
        EH_LOADED,
        BUILD_FAILED,
    }

    public static final class ChunkState {

        private int chunkX;
        private int chunkZ;
        private ChunkLifecycle lifecycle = ChunkLifecycle.UNLOADED;
        private long failedAtNanos;

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

        public void set(int chunkX, int chunkZ, ChunkLifecycle lifecycle) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.lifecycle = lifecycle;
        }

        public void markBuildFailed() {
            this.lifecycle = ChunkLifecycle.BUILD_FAILED;
            this.failedAtNanos = System.nanoTime();
        }

        public void reset() {
            this.set(0, 0, ChunkLifecycle.UNLOADED);
            this.failedAtNanos = 0L;
        }

        public boolean hasCoords() {
            return this.lifecycle != ChunkLifecycle.UNLOADED || this.chunkX != 0 || this.chunkZ != 0;
        }
    }
}

