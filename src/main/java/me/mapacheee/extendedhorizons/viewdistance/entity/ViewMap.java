package me.mapacheee.extendedhorizons.viewdistance.entity;

import org.bukkit.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/* View map entity that handles chunk visibility calculations and management
 * Supports different shapes (circle, square) and optimized chunk tracking
 */

public class ViewMap {

    private final ViewShape shape;
    private final int maxDistance;
    private final Map<ChunkCoordinate, ChunkState> chunkStates;
    private final Set<ChunkCoordinate> visibleChunks;
    private final Set<ChunkCoordinate> fakeChunks;
    private volatile Location centerLocation;

    public ViewMap(ViewShape shape, int maxDistance) {
        this.shape = shape;
        this.maxDistance = maxDistance;
        this.chunkStates = new ConcurrentHashMap<>();
        this.visibleChunks = ConcurrentHashMap.newKeySet();
        this.fakeChunks = ConcurrentHashMap.newKeySet();
    }

    public void updateCenter(Location newCenter) {
        this.centerLocation = newCenter.clone();
        recalculateVisibleChunks();
    }

    public Set<ChunkCoordinate> getVisibleChunks() {
        return new HashSet<>(visibleChunks);
    }

    public Set<ChunkCoordinate> getFakeChunks() {
        return new HashSet<>(fakeChunks);
    }

    public Set<ChunkCoordinate> getNewChunks(Set<ChunkCoordinate> previousChunks) {
        Set<ChunkCoordinate> newChunks = new HashSet<>(visibleChunks);
        newChunks.removeAll(previousChunks);
        return newChunks;
    }

    public Set<ChunkCoordinate> getRemovedChunks(Set<ChunkCoordinate> previousChunks) {
        Set<ChunkCoordinate> removedChunks = new HashSet<>(previousChunks);
        removedChunks.removeAll(visibleChunks);
        return removedChunks;
    }

    public boolean isChunkVisible(int chunkX, int chunkZ) {
        return visibleChunks.contains(new ChunkCoordinate(chunkX, chunkZ));
    }

    public boolean isChunkFake(int chunkX, int chunkZ) {
        return fakeChunks.contains(new ChunkCoordinate(chunkX, chunkZ));
    }

    private void recalculateVisibleChunks() {
        if (centerLocation == null) return;

        visibleChunks.clear();
        fakeChunks.clear();

        int centerChunkX = centerLocation.getBlockX() >> 4;
        int centerChunkZ = centerLocation.getBlockZ() >> 4;

        for (int distance = 1; distance <= maxDistance; distance++) {
            Set<ChunkCoordinate> chunksAtDistance = getChunksAtDistance(centerChunkX, centerChunkZ, distance);

            for (ChunkCoordinate coord : chunksAtDistance) {
                if (shape.isWithinShape(centerChunkX, centerChunkZ, coord.x(), coord.z(), distance)) {
                    visibleChunks.add(coord);

                    if (distance > 32) {
                        fakeChunks.add(coord);
                    }
                }
            }
        }
    }

    private Set<ChunkCoordinate> getChunksAtDistance(int centerX, int centerZ, int distance) {
        Set<ChunkCoordinate> chunks = new HashSet<>();

        if (shape == ViewShape.CIRCLE) {
            int distanceSquared = distance * distance;
            for (int x = centerX - distance; x <= centerX + distance; x++) {
                for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                    int dx = x - centerX;
                    int dz = z - centerZ;
                    if (dx * dx + dz * dz <= distanceSquared) {
                        chunks.add(new ChunkCoordinate(x, z));
                    }
                }
            }
        } else {
            for (int x = centerX - distance; x <= centerX + distance; x++) {
                for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                    chunks.add(new ChunkCoordinate(x, z));
                }
            }
        }

        return chunks;
    }

    public void markChunkState(ChunkCoordinate coord, ChunkState state) {
        chunkStates.put(coord, state);
    }

    public ChunkState getChunkState(ChunkCoordinate coord) {
        return chunkStates.getOrDefault(coord, ChunkState.UNKNOWN);
    }

    public void clearCache() {
        chunkStates.clear();
        visibleChunks.clear();
        fakeChunks.clear();
    }

    public int getTotalVisibleChunks() {
        return visibleChunks.size();
    }

    public int getTotalFakeChunks() {
        return fakeChunks.size();
    }

    public enum ViewShape {
        CIRCLE {
            @Override
            public boolean isWithinShape(int centerX, int centerZ, int chunkX, int chunkZ, int maxDistance) {
                int dx = chunkX - centerX;
                int dz = chunkZ - centerZ;
                return dx * dx + dz * dz <= maxDistance * maxDistance;
            }
        },
        SQUARE {
            @Override
            public boolean isWithinShape(int centerX, int centerZ, int chunkX, int chunkZ, int maxDistance) {
                int dx = Math.abs(chunkX - centerX);
                int dz = Math.abs(chunkZ - centerZ);
                return dx <= maxDistance && dz <= maxDistance;
            }
        };

        public abstract boolean isWithinShape(int centerX, int centerZ, int chunkX, int chunkZ, int maxDistance);
    }

    public enum ChunkState {
        UNKNOWN,
        LOADING,
        LOADED,
        FAKE,
        ERROR
    }

    public record ChunkCoordinate(int x, int z) {
        public double distanceTo(ChunkCoordinate other) {
            int dx = this.x - other.x;
            int dz = this.z - other.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public ChunkCoordinate add(int deltaX, int deltaZ) {
            return new ChunkCoordinate(x + deltaX, z + deltaZ);
        }

        @Override
        public String toString() {
            return "(" + x + ", " + z + ")";
        }
    }
}
