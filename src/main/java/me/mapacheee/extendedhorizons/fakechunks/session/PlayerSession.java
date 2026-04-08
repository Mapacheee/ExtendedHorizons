package me.mapacheee.extendedhorizons.fakechunks.session;

import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkSendQueueEntry;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import net.minecraft.world.level.ChunkPos;

import java.util.Deque;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerSession {

    private static final long[] EMPTY_LONG_ARRAY = new long[0];
    private static final ChunkState[] EMPTY_STATE_ARRAY = new ChunkState[0];

    private final UUID playerId;
    private volatile UUID worldId;
    private final AtomicLong epoch = new AtomicLong(0L);

    private volatile long chunkKey = ChunkPos.asLong(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private volatile int distance;
    private volatile int storageRadius;
    private volatile int storageDiameter;
    private volatile int iterationIndex;

    private volatile boolean enabled;
    private volatile boolean initiated;

    private volatile long[] chunksInDistance = EMPTY_LONG_ARRAY;
    private volatile ChunkState[] chunkStates = EMPTY_STATE_ARRAY;

    private volatile int lastAdvertisedDistance = -1;
    private volatile int serverViewDistance = 2;

    private final Deque<ChunkSendQueueEntry> chunkQueue = new ConcurrentLinkedDeque<>();

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

    public void setWorld(UUID worldId) {
        this.worldId = worldId;
    }

    public long chunkKey() {
        return this.chunkKey;
    }

    public void setChunkPos(int chunkX, int chunkZ) {
        this.chunkKey = ChunkPos.asLong(chunkX, chunkZ);
    }

    public boolean hasChunkChanged(int chunkX, int chunkZ) {
        return this.chunkKey != ChunkPos.asLong(chunkX, chunkZ);
    }

    public int distance() {
        return this.distance;
    }

    public Deque<ChunkSendQueueEntry> chunkQueue() {
        return this.chunkQueue;
    }

    public void updateDistance(int newDistance) {
        this.distance = Math.max(2, newDistance);
        this.storageRadius = Math.max(2, this.distance) + 3;
        this.storageDiameter = this.storageRadius * 2 + 1;
        this.chunksInDistance = ChunkPlannerService.radiusIterationList(this.distance);
        this.iterationIndex = 0;

        int size = this.storageDiameter * this.storageDiameter;
        ChunkState[] oldStates = this.chunkStates;
        if (oldStates.length == size) {
            return;
        }

        ChunkState[] newStates = new ChunkState[size];
        int centerX = ChunkPos.getX(this.chunkKey);
        int centerZ = ChunkPos.getZ(this.chunkKey);
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
        long newKey = ChunkPos.asLong(chunkX, chunkZ);
        long previous = this.chunkKey;
        if (newKey == previous) {
            return;
        }
        this.chunkKey = newKey;
        this.iterationIndex = 0;

        if (!this.enabled || this.chunkStates.length == 0) {
            return;
        }

        int prevX = ChunkPos.getX(previous);
        int prevZ = ChunkPos.getZ(previous);
        if (distanceSquared(prevX, prevZ, chunkX, chunkZ) > this.distance * this.distance) {
            this.unloadBvChunks();
            this.enabled = false;
            return;
        }

        for (ChunkState state : this.chunkStates) {
            if (!state.hasCoords()) {
                continue;
            }
            int stateX = state.chunkX();
            int stateZ = state.chunkZ();
            if (ChunkPlannerService.isWithinRange(stateX - chunkX, stateZ - chunkZ, this.distance)) {
                continue;
            }
            if (state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
                this.purgeQueuedChunk(stateX, stateZ);
            }
            if (state.lifecycle() != ChunkLifecycle.SERVER_LOADED) {
                state.reset();
            }
        }
    }

    public void serverChunkAdd(int chunkX, int chunkZ) {
        if (!this.canStore(chunkX, chunkZ)) {
            return;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        if (state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
            this.purgeQueuedChunk(chunkX, chunkZ);
        }
        state.set(chunkX, chunkZ, ChunkLifecycle.SERVER_LOADED);
    }

    public boolean serverChunkRemove(int chunkX, int chunkZ) {
        int centerX = ChunkPos.getX(this.chunkKey);
        int centerZ = ChunkPos.getZ(this.chunkKey);
        if (!ChunkPlannerService.isWithinRange(chunkX - centerX, chunkZ - centerZ, this.distance)) {
            if (this.canStore(chunkX, chunkZ, centerX, centerZ)) {
                this.getState(chunkX, chunkZ).reset();
            }
            return false;
        }
        ChunkState state = this.getState(chunkX, chunkZ);
        if (state.lifecycle() == ChunkLifecycle.SERVER_LOADED) {
            state.set(chunkX, chunkZ, ChunkLifecycle.BV_LOADED);
        }
        return true;
    }

    public Long pollNextChunkKey() {
        int centerX = ChunkPos.getX(this.chunkKey);
        int centerZ = ChunkPos.getZ(this.chunkKey);
        long[] offsets = this.chunksInDistance;
        while (this.iterationIndex < offsets.length) {
            long off = offsets[this.iterationIndex++];
            int chunkX = ChunkPos.getX(off) + centerX;
            int chunkZ = ChunkPos.getZ(off) + centerZ;

            if (!this.canStore(chunkX, chunkZ, centerX, centerZ)) {
                continue;
            }

            ChunkState state = this.getState(chunkX, chunkZ);
            if (state.lifecycle() != ChunkLifecycle.UNLOADED) {
                continue;
            }

            state.set(chunkX, chunkZ, ChunkLifecycle.BV_QUEUED);
            return ChunkPos.asLong(chunkX, chunkZ);
        }
        this.iterationIndex = 0;
        return null;
    }

    public void onChunkQueued(long chunkKey) {
        // state is already set to BV_QUEUED by pollNextChunkKey.
    }

    public void onChunkBuildFailed(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        if (state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
            state.reset();
            this.iterationIndex = 0;
        }
    }

    public void onChunkSent(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        if (state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
            state.set(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), ChunkLifecycle.BV_LOADED);
        }
    }

    public void onChunkUnloaded(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        if (state.lifecycle() == ChunkLifecycle.BV_LOADED || state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
            state.reset();
        }
    }

    public void invalidateChunk(long chunkKey) {
        ChunkState state = this.getStateByKey(chunkKey);
        if (state.lifecycle() == ChunkLifecycle.BV_LOADED || state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
            state.reset();
            this.iterationIndex = 0;
        }
    }

    public long[] loadedBvChunkKeys() {
        if (this.chunkStates.length == 0) {
            return EMPTY_LONG_ARRAY;
        }
        LongStream.Builder builder = LongStream.builder();
        for (ChunkState state : this.chunkStates) {
            if (state.lifecycle() == ChunkLifecycle.BV_LOADED) {
                builder.add(ChunkPos.asLong(state.chunkX(), state.chunkZ()));
            }
        }
        return builder.build().toArray();
    }

    public void unloadBvChunks() {
        for (ChunkState state : this.chunkStates) {
            if (state.lifecycle() == ChunkLifecycle.BV_LOADED || state.lifecycle() == ChunkLifecycle.BV_QUEUED) {
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
        this.enabled = false;
        this.iterationIndex = 0;
    }

    public void clearDispatchState() {
        this.clearChunkQueue();
        this.lastAdvertisedDistance = -1;
        this.enabled = false;
        this.initiated = false;
        this.iterationIndex = 0;
    }

    private void purgeQueuedChunk(int chunkX, int chunkZ) {
        this.chunkQueue.removeIf(entry -> {
            if (entry.chunkKey() == ChunkPos.asLong(chunkX, chunkZ)) {
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
        return this.getState(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private ChunkState getState(int chunkX, int chunkZ) {
        if (this.chunkStates.length == 0) {
            return new ChunkState();
        }
        return this.chunkStates[calcIndex(chunkX, chunkZ, this.storageDiameter)];
    }

    private boolean canStore(int chunkX, int chunkZ) {
        int centerX = ChunkPos.getX(this.chunkKey);
        int centerZ = ChunkPos.getZ(this.chunkKey);
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
        BV_QUEUED,
        BV_LOADED,
    }

    public static final class ChunkState {

        private int chunkX;
        private int chunkZ;
        private ChunkLifecycle lifecycle = ChunkLifecycle.UNLOADED;

        public int chunkX() {
            return this.chunkX;
        }

        public int chunkZ() {
            return this.chunkZ;
        }

        public ChunkLifecycle lifecycle() {
            return this.lifecycle;
        }

        public void set(int chunkX, int chunkZ, ChunkLifecycle lifecycle) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.lifecycle = lifecycle;
        }

        public void reset() {
            this.set(0, 0, ChunkLifecycle.UNLOADED);
        }

        public boolean hasCoords() {
            return this.lifecycle != ChunkLifecycle.UNLOADED || this.chunkX != 0 || this.chunkZ != 0;
        }
    }
}

