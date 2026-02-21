package me.mapacheee.extendedhorizons.viewdistance.service.player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;

/**
 * Encapsulates all chunk-related state for a single player.
 * This class consolidates what were previously 15+ separate ConcurrentHashMaps
 * into a single, cohesive state object per player.
 * 
 * Thread-safe: All collections use concurrent implementations.
 * 
 * @since 2.3.0
 */
public class PlayerChunkState {

    private final UUID playerId;

    // === Chunk Tracking ===

    /**
     * Set of fake chunk keys currently sent to this player.
     * Key format: packed long from ChunkUtils.packChunkKey(x, z)
     */
    private final Set<Long> fakeChunks = ConcurrentHashMap.newKeySet();

    /**
     * Queue of chunks pending to be loaded progressively.
     * Chunks are added sorted by distance from player.
     */
    /**
     * Queue of chunks pending to be loaded progressively.
     * Chunks are added sorted by distance from the player.
     */
    private final Deque<Long> chunkQueue = new ConcurrentLinkedDeque<>();

    /**
     * Set of chunks currently in the queue (for O(1) lookup).
     * Must be kept in sync with chunkQueue.
     */
    private final Set<Long> queuedChunksSet = ConcurrentHashMap.newKeySet();

    /**
     * Last known chunk position for teleport detection.
     * Format: packed long from ChunkUtils.packChunkKey(x, z)
     */
    private volatile long lastChunkPosition;

    // === Packet Management ===

    /**
     * Queue of packets waiting to be sent to this player (batched).
     */
    private final Deque<Object> pendingPackets = new ConcurrentLinkedDeque<>();

    // === Bandwidth Tracking ===

    /**
     * Bytes sent this tick (for per-tick rate limiting).
     */
    private volatile long bytesThisTick;

    /**
     * Bytes sent in the current second (for per-second bandwidth limiting).
     */
    private volatile long bytesThisSecond;

    /**
     * Timestamp when the byte counter was last reset (milliseconds).
     */
    private volatile long byteResetTime;

    /**
     * Actual bytes sent (measured from real packets).
     */
    private volatile long actualBytesSent;

    // === Performance Metrics ===

    /**
     * Player's average ping (for adaptive rate limiting).
     */
    private volatile int avgPing;

    /**
     * Rolling average of packet sizes for this player (in bytes).
     */
    private volatile double avgPacketSize;

    /**
     * Number of chunks processed this tick (for throttling).
     */
    private volatile int chunksProcessedThisTick;

    // === Warmup & Teleport ===

    /**
     * Timestamp when the player joined or last teleported (milliseconds).
     * Used to implement warmup period before loading fake chunks.
     */
    private volatile long warmupStartTime;

    /**
     * Whether the player is currently in a warmup period.
     */
    private volatile boolean inWarmup;

    // === World Border ===

    /**
     * World border center X coordinate (for filtering chunks)
     */
    private volatile double borderCenterX;

    /**
     * World border center Z coordinate (for filtering chunks)
     */
    private volatile double borderCenterZ;

    /**
     * World border size (for filtering chunks)
     */
    private volatile double borderSize;

    // === Constructor ===

    public PlayerChunkState(UUID playerId) {
        this.playerId = playerId;
        this.lastChunkPosition = 0;
        this.bytesThisTick = 0;
        this.bytesThisSecond = 0;
        this.byteResetTime = System.currentTimeMillis();
        this.actualBytesSent = 0;
        this.avgPing = 0;
        this.avgPacketSize = 50000.0;
        this.chunksProcessedThisTick = 0;
        this.warmupStartTime = System.currentTimeMillis();
        this.inWarmup = true;
    }

    // === Getters & Setters ===

    public UUID getPlayerId() {
        return playerId;
    }

    // --- Chunk Tracking ---

    public Set<Long> getFakeChunks() {
        return fakeChunks;
    }

    public Deque<Long> getChunkQueue() {
        return chunkQueue;
    }

    public Set<Long> getQueuedChunksSet() {
        return queuedChunksSet;
    }

    public boolean canAddMoreFakeChunks() {
        return true;
    }

    public void addFakeChunk(long key) {
        fakeChunks.add(key);
    }

    public long getLastChunkPosition() {
        return lastChunkPosition;
    }

    public void setLastChunkPosition(long position) {
        this.lastChunkPosition = position;
    }

    // --- Packet Management ---

    public Deque<Object> getPendingPackets() {
        return pendingPackets;
    }

    // --- Bandwidth Tracking ---

    public long getBytesThisTick() {
        return bytesThisTick;
    }

    public void setBytesThisTick(long bytes) {
        this.bytesThisTick = bytes;
    }

    public void addBytesThisTick(long bytes) {
        this.bytesThisTick += bytes;
    }

    public long getBytesThisSecond() {
        return bytesThisSecond;
    }

    public void setBytesThisSecond(long bytes) {
        this.bytesThisSecond = bytes;
    }

    public void addBytesThisSecond(long bytes) {
        this.bytesThisSecond += bytes;
    }

    public long getByteResetTime() {
        return byteResetTime;
    }

    public void setByteResetTime(long time) {
        this.byteResetTime = time;
    }

    public long getActualBytesSent() {
        return actualBytesSent;
    }

    public void setActualBytesSent(long bytes) {
        this.actualBytesSent = bytes;
    }

    public void addActualBytesSent(long bytes) {
        this.actualBytesSent += bytes;
    }

    // --- Performance Metrics ---

    public int getAvgPing() {
        return avgPing;
    }

    public void setAvgPing(int ping) {
        this.avgPing = ping;
    }

    public double getAvgPacketSize() {
        return avgPacketSize;
    }

    public void setAvgPacketSize(double size) {
        this.avgPacketSize = size;
    }

    public void updateAvgPacketSize(long newSize) {
        this.avgPacketSize = (this.avgPacketSize * 0.9) + (newSize * 0.1);
    }

    public int getChunksProcessedThisTick() {
        return chunksProcessedThisTick;
    }

    public void setChunksProcessedThisTick(int count) {
        this.chunksProcessedThisTick = count;
    }

    public void incrementChunksProcessedThisTick() {
        this.chunksProcessedThisTick++;
    }

    // --- Warmup & Teleport ---

    public long getWarmupStartTime() {
        return warmupStartTime;
    }

    public void setWarmupStartTime(long time) {
        this.warmupStartTime = time;
        this.inWarmup = true;
    }

    public boolean isInWarmup() {
        return inWarmup;
    }

    public void setInWarmup(boolean inWarmup) {
        this.inWarmup = inWarmup;
    }

    /**
     * Starts a new warmup period (e.g., after teleport).
     */
    public void startWarmup() {
        this.warmupStartTime = System.currentTimeMillis();
        this.inWarmup = true;
        this.chunkQueue.clear();
    }

    /**
     * Ends the warmup period.
     */
    public void endWarmup() {
        this.inWarmup = false;
    }

    /**
     * Checks if warmup period has elapsed.
     * 
     * @param warmupDelayMs Warmup delay in milliseconds
     * @return true if warmup is complete
     */
    public boolean isWarmupComplete(long warmupDelayMs) {
        return System.currentTimeMillis() - warmupStartTime >= warmupDelayMs;
    }

    // --- World Border ---

    public double getBorderCenterX() {
        return borderCenterX;
    }

    public void setBorderCenterX(double borderCenterX) {
        this.borderCenterX = borderCenterX;
    }

    public double getBorderCenterZ() {
        return borderCenterZ;
    }

    public void setBorderCenterZ(double borderCenterZ) {
        this.borderCenterZ = borderCenterZ;
    }

    public double getBorderSize() {
        return borderSize;
    }

    public void setBorderSize(double borderSize) {
        this.borderSize = borderSize;
    }

    public void updateWorldBorder(double centerX, double centerZ, double size) {
        this.borderCenterX = centerX;
        this.borderCenterZ = centerZ;
        this.borderSize = size;
    }

    // === State Management ===

    /**
     * Resets per-tick counters.
     * Should be called at the start of each tick.
     */
    public void resetTickCounters() {
        this.bytesThisTick = 0;
        this.chunksProcessedThisTick = 0;
    }

    /**
     * Resets per-second bandwidth counters.
     * Should be called every second.
     */
    public void resetSecondCounters() {
        this.bytesThisSecond = 0;
        this.actualBytesSent = 0;
        this.byteResetTime = System.currentTimeMillis();
    }

    /**
     * Checks if the second counter needs to be reset.
     * 
     * @return true if more than 1 second has passed since last reset
     */
    public boolean shouldResetSecondCounters() {
        return System.currentTimeMillis() - byteResetTime >= 1000;
    }

    /**
     * Clears all state (for cleanup on player quit).
     */
    public void clear() {
        fakeChunks.clear();
        chunkQueue.clear();
        queuedChunksSet.clear();
        pendingPackets.clear();
        lastChunkPosition = 0;
        bytesThisTick = 0;
        bytesThisSecond = 0;
        actualBytesSent = 0;
        chunksProcessedThisTick = 0;
    }

    // === Statistics ===

    /**
     * Gets the number of fake chunks currently loaded for this player.
     */
    public int getFakeChunkCount() {
        return fakeChunks.size();
    }

    /**
     * Gets the number of chunks waiting in queue.
     */
    public int getQueuedChunkCount() {
        return chunkQueue.size();
    }

    /**
     * Gets the number of packets waiting to be sent.
     */
    public int getPendingPacketCount() {
        return pendingPackets.size();
    }

    @Override
    public String toString() {
        return "PlayerChunkState{" +
                "playerId=" + playerId +
                ", fakeChunks=" + fakeChunks.size() +
                ", queuedChunks=" + chunkQueue.size() +
                ", pendingPackets=" + pendingPackets.size() +
                ", bytesThisSecond=" + bytesThisSecond +
                ", avgPing=" + avgPing +
                ", inWarmup=" + inWarmup +
                '}';
    }
}
