package me.mapacheee.extendedhorizons.fakechunks.session;

import me.mapacheee.extendedhorizons.fakechunks.dispatch.ChunkSendQueueEntry;
import net.minecraft.world.level.ChunkPos;

import java.util.Deque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerSession {

    private final UUID playerId;
    private volatile UUID worldId;
    private final AtomicLong epoch = new AtomicLong(0L);
    private volatile long lastChunkKey = ChunkPos.asLong(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private volatile int plannerCursor;
    private volatile long lastPlanAtMs;
    private volatile int lastAdvertisedDistance = -1;
    private volatile int serverViewDistance = 2;

    private final Deque<Long> pendingQueue = new ConcurrentLinkedDeque<>();
    private final Deque<ChunkSendQueueEntry> pendingSendQueue = new ConcurrentLinkedDeque<>();
    private final Set<Long> sentChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> inflightChunks = ConcurrentHashMap.newKeySet();

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

    public int plannerCursor() {
        return this.plannerCursor;
    }

    public void plannerCursor(int plannerCursor) {
        this.plannerCursor = plannerCursor;
    }

    public long lastPlanAtMs() {
        return this.lastPlanAtMs;
    }

    public void lastPlanAtMs(long lastPlanAtMs) {
        this.lastPlanAtMs = lastPlanAtMs;
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

    public long lastChunkKey() {
        return this.lastChunkKey;
    }

    public void setLastChunk(int chunkX, int chunkZ) {
        this.lastChunkKey = ChunkPos.asLong(chunkX, chunkZ);
    }

    public boolean hasChunkChanged(int chunkX, int chunkZ) {
        return this.lastChunkKey != ChunkPos.asLong(chunkX, chunkZ);
    }

    public Deque<Long> pendingQueue() {
        return this.pendingQueue;
    }

    public Deque<ChunkSendQueueEntry> pendingSendQueue() {
        return this.pendingSendQueue;
    }

    public Set<Long> sentChunks() {
        return this.sentChunks;
    }

    public Set<Long> queuedChunks() {
        return this.queuedChunks;
    }

    public Set<Long> inflightChunks() {
        return this.inflightChunks;
    }

    public void clearDispatchState() {
        this.pendingQueue.clear();
        this.pendingSendQueue.clear();
        this.sentChunks.clear();
        this.queuedChunks.clear();
        this.inflightChunks.clear();
        this.lastAdvertisedDistance = -1;
    }
}

