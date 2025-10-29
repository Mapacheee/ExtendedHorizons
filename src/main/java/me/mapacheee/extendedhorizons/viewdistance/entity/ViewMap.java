package me.mapacheee.extendedhorizons.viewdistance.entity;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* View map entity that handles chunk visibility calculations and management
 * Supports different shapes (circle, square) and optimized chunk tracking
 */

public class ViewMap {

    private final ViewShape shape;
    private final int fakeStartDistance;
    private final Map<ChunkCoordinate, ChunkState> chunkStates;
    private final Set<ChunkCoordinate> visibleChunks;
    private final Set<ChunkCoordinate> fakeChunks;
    private volatile Location centerLocation;
    private final Set<ChunkCoordinate> previousVisibleChunks;
    private final Set<ChunkCoordinate> previousFakeChunks;

    private int viewDistance;

    public ViewMap(ViewShape shape, int viewDistance, int fakeStartDistance) {
        this.shape = shape;
        this.viewDistance = viewDistance;
        this.fakeStartDistance = fakeStartDistance;
        this.chunkStates = new ConcurrentHashMap<>();
        this.visibleChunks = ConcurrentHashMap.newKeySet();
        this.fakeChunks = ConcurrentHashMap.newKeySet();
        this.previousVisibleChunks = ConcurrentHashMap.newKeySet();
        this.previousFakeChunks = ConcurrentHashMap.newKeySet();
    }

    public void updateCenter(Location newCenter) {
        Location oldCenter = this.centerLocation;
        this.centerLocation = newCenter.clone();

        if (oldCenter == null) {
            recalculateVisibleChunks();
            return;
        }

        int oldChunkX = oldCenter.getBlockX() >> 4;
        int oldChunkZ = oldCenter.getBlockZ() >> 4;
        int newChunkX = newCenter.getBlockX() >> 4;
        int newChunkZ = newCenter.getBlockZ() >> 4;

        int deltaX = newChunkX - oldChunkX;
        int deltaZ = newChunkZ - oldChunkZ;

        if (deltaX == 0 && deltaZ == 0) {
            return;
        }

        if (Math.abs(deltaX) <= 2 && Math.abs(deltaZ) <= 2) {
            updateChunksIncrementally(deltaX, deltaZ, oldChunkX, oldChunkZ);
        } else {
            recalculateVisibleChunks();
        }
    }

    public void updateViewDistance(int viewDistance) {
        this.viewDistance = viewDistance;
    }

    private void updateChunksIncrementally(int deltaX, int deltaZ, int oldChunkX, int oldChunkZ) {
        int newCenterX = oldChunkX + deltaX;
        int newCenterZ = oldChunkZ + deltaZ;

        Set<ChunkCoordinate> newVisibleChunks = calculateVisibleChunksForCenter(newCenterX, newCenterZ);
        Set<ChunkCoordinate> newFakeChunks = new HashSet<>();

        for (ChunkCoordinate coord : newVisibleChunks) {
            int dx = coord.x() - newCenterX;
            int dz = coord.z() - newCenterZ;
            int distanceSqr = dx * dx + dz * dz;
            if (distanceSqr > fakeStartDistance) {
                newFakeChunks.add(coord);
            }
        }

        visibleChunks.clear();
        visibleChunks.addAll(newVisibleChunks);
        fakeChunks.clear();
        fakeChunks.addAll(newFakeChunks);

        previousVisibleChunks.clear();
        previousVisibleChunks.addAll(newVisibleChunks);
        previousFakeChunks.clear();
        previousFakeChunks.addAll(newFakeChunks);
    }

    private Set<ChunkCoordinate> calculateVisibleChunksForCenter(int centerX, int centerZ) {
        Set<ChunkCoordinate> visible = new HashSet<>();

        visible.add(new ChunkCoordinate(centerX, centerZ));

        for (int distance = 1; distance <= viewDistance; distance++) {
            Set<ChunkCoordinate> chunksAtDistance = getChunksAtDistance(centerX, centerZ, distance);
            for (ChunkCoordinate coord : chunksAtDistance) {
                if (shape.isWithinShape(centerX, centerZ, coord.x(), coord.z(), distance)) {
                    visible.add(coord);
                }
            }
        }

        return visible;
    }

    private boolean isWithinDistance(int chunkX, int chunkZ, int centerX, int centerZ, int maxDist) {
        int dx = Math.abs(chunkX - centerX);
        int dz = Math.abs(chunkZ - centerZ);
        return shape.isWithinShape(centerX, centerZ, chunkX, chunkZ, maxDist);
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

        ViewMap.ChunkCoordinate centerCoord = new ViewMap.ChunkCoordinate(centerChunkX, centerChunkZ);
        visibleChunks.add(centerCoord);

        for (int distance = 1; distance <= viewDistance; distance++) {
            Set<ChunkCoordinate> chunksAtDistance = getChunksAtDistance(centerChunkX, centerChunkZ, distance);

            for (ChunkCoordinate coord : chunksAtDistance) {
                if (shape.isWithinShape(centerChunkX, centerChunkZ, coord.x(), coord.z(), distance)) {
                    visibleChunks.add(coord);

                    if (distance > fakeStartDistance) {
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
        public @NotNull String toString() {
            return "(" + x + ", " + z + ")";
        }
    }
}
