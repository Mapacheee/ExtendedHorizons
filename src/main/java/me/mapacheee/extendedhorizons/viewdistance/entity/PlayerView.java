package me.mapacheee.extendedhorizons.viewdistance.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/* Player view entity that manages individual player's view distance settings
 * Contains all view-related data and state for a specific player
 */

public class PlayerView {

    private final UUID playerId;
    private final String playerName;
    private final AtomicInteger currentDistance;
    private final AtomicInteger targetDistance;
    private final AtomicInteger maxAllowedDistance;
    private final AtomicBoolean fakeChunksEnabled;
    private final AtomicBoolean adaptiveMode;
    private final AtomicLong lastMoveTime;
    private final AtomicLong networkBytesUsed;
    private final AtomicInteger chunksSent;
    private final AtomicInteger fakeChunksSent;
    private final AtomicBoolean movingTooFast;
    private final AtomicBoolean waitingForChunks;

    private volatile Location lastLocation;
    private volatile World currentWorld;
    private volatile String currentPermissionGroup;
    private volatile long lastPermissionCheck;

    public PlayerView(Player player) {
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.currentDistance = new AtomicInteger(16);
        this.targetDistance = new AtomicInteger(16);
        this.maxAllowedDistance = new AtomicInteger(32);
        this.fakeChunksEnabled = new AtomicBoolean(false);
        this.adaptiveMode = new AtomicBoolean(true);
        this.lastMoveTime = new AtomicLong(System.currentTimeMillis());
        this.networkBytesUsed = new AtomicLong(0);
        this.chunksSent = new AtomicInteger(0);
        this.fakeChunksSent = new AtomicInteger(0);
        this.movingTooFast = new AtomicBoolean(false);
        this.waitingForChunks = new AtomicBoolean(false);
        this.lastLocation = player.getLocation().clone();
        this.currentWorld = player.getWorld();
        this.lastPermissionCheck = 0;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getCurrentDistance() {
        return currentDistance.get();
    }

    public void setCurrentDistance(int distance) {
        this.currentDistance.set(distance);
    }

    public int getTargetDistance() {
        return targetDistance.get();
    }

    public void setTargetDistance(int distance) {
        this.targetDistance.set(distance);
    }

    public int getMaxAllowedDistance() {
        return maxAllowedDistance.get();
    }

    public void setMaxAllowedDistance(int distance) {
        this.maxAllowedDistance.set(distance);
    }

    public boolean areFakeChunksEnabled() {
        return fakeChunksEnabled.get();
    }

    public void setFakeChunksEnabled(boolean enabled) {
        this.fakeChunksEnabled.set(enabled);
    }

    public boolean isAdaptiveModeEnabled() {
        return adaptiveMode.get();
    }

    public void setAdaptiveModeEnabled(boolean enabled) {
        this.adaptiveMode.set(enabled);
    }

    public long getLastMoveTime() {
        return lastMoveTime.get();
    }

    public void updateLastMoveTime() {
        this.lastMoveTime.set(System.currentTimeMillis());
    }

    public long getNetworkBytesUsed() {
        return networkBytesUsed.get();
    }

    public void addNetworkBytesUsed(long bytes) {
        this.networkBytesUsed.addAndGet(bytes);
    }

    public void resetNetworkBytesUsed() {
        this.networkBytesUsed.set(0);
    }

    public int getChunksSent() {
        return chunksSent.get();
    }

    public void incrementChunksSent() {
        this.chunksSent.incrementAndGet();
    }

    public int getFakeChunksSent() {
        return fakeChunksSent.get();
    }

    public void incrementFakeChunksSent() {
        this.fakeChunksSent.incrementAndGet();
    }

    public boolean isMovingTooFast() {
        return movingTooFast.get();
    }

    public void setMovingTooFast(boolean moving) {
        this.movingTooFast.set(moving);
    }

    public boolean isWaitingForChunks() {
        return waitingForChunks.get();
    }

    public void setWaitingForChunks(boolean waiting) {
        this.waitingForChunks.set(waiting);
    }

    public Location getLastLocation() {
        return lastLocation != null ? lastLocation.clone() : null;
    }

    public void setLastLocation(Location location) {
        this.lastLocation = location != null ? location.clone() : null;
    }

    public World getCurrentWorld() {
        return currentWorld;
    }

    public void setCurrentWorld(World world) {
        this.currentWorld = world;
    }

    public String getCurrentPermissionGroup() {
        return currentPermissionGroup;
    }

    public void setCurrentPermissionGroup(String group) {
        this.currentPermissionGroup = group;
    }

    public long getLastPermissionCheck() {
        return lastPermissionCheck;
    }

    public void setLastPermissionCheck(long time) {
        this.lastPermissionCheck = time;
    }

    public boolean needsPermissionCheck(long checkInterval) {
        return System.currentTimeMillis() - lastPermissionCheck > checkInterval;
    }

    public double getNetworkUsagePerSecond() {
        long timeDiff = System.currentTimeMillis() - lastMoveTime.get();
        if (timeDiff <= 0) return 0;

        return (double) networkBytesUsed.get() / (timeDiff / 1000.0);
    }

    public void resetStatistics() {
        networkBytesUsed.set(0);
        chunksSent.set(0);
        fakeChunksSent.set(0);
    }
}
