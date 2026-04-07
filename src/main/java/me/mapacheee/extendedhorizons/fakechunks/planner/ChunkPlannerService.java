package me.mapacheee.extendedhorizons.fakechunks.planner;

import com.thewinterframework.service.annotation.Service;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

@Service
public final class ChunkPlannerService {

    private final ConcurrentHashMap<Long, long[]> sortedOffsetsCache = new ConcurrentHashMap<>();

    public record PlanInput(
            int centerChunkX,
            int centerChunkZ,
            int targetDistance,
            int serverDistance,
            double safeSquareFactor,
            int cursorStart,
            int maxAdds,
            int movementDx,
            int movementDz,
            double headingX,
            double headingZ,
            Set<Long> sentChunks,
            Deque<Long> currentQueue,
            Set<Long> queuedChunks,
            Set<Long> inflightChunks,
            LongPredicate temporarilyUnavailable
    ) {}

    public record PlanResult(
            List<Long> chunksToUnload,
            Deque<Long> rebuiltQueue,
            Set<Long> rebuiltQueued,
            List<Long> toAdd,
            int nextCursor
    ) {}

    public PlanResult build(PlanInput input) {
        int targetRadius = Math.max(2, input.targetDistance());
        int radiusSq = targetRadius * targetRadius;
        int factorSquare = Math.max(2, (int) Math.floor(input.serverDistance() * input.safeSquareFactor()));
        int safeSquare = Math.max(input.serverDistance(), factorSquare);

        long[] offsets = this.getSortedOffsets(targetRadius, safeSquare);
        int offsetCount = offsets.length;

        List<Long> chunksToUnload = new ArrayList<>();
        for (Long sentKey : input.sentChunks()) {
            if (!this.isNeeded(input.centerChunkX(), input.centerChunkZ(), radiusSq, safeSquare, sentKey)) {
                chunksToUnload.add(sentKey);
            }
        }

        Deque<Long> rebuiltQueue = new ArrayDeque<>(Math.max(16, input.currentQueue().size()));
        Set<Long> rebuiltQueued = new HashSet<>(Math.max(16, input.currentQueue().size()));
        for (Long key : input.currentQueue()) {
            if (!this.isNeeded(input.centerChunkX(), input.centerChunkZ(), radiusSq, safeSquare, key)) {
                continue;
            }
            if (input.sentChunks().contains(key) || input.inflightChunks().contains(key)) {
                continue;
            }
            rebuiltQueue.addLast(key);
            rebuiltQueued.add(key);
        }

        int maxAdds = Math.max(1, input.maxAdds());
        int cursor = Math.floorMod(input.cursorStart(), Math.max(1, offsetCount));
        int scanned = 0;
        int index = cursor;
        List<Long> toAdd = new ArrayList<>(maxAdds);
        boolean directionalPriority = input.movementDx() != 0
                || input.movementDz() != 0
                || input.headingX() != 0.0d
                || input.headingZ() != 0.0d;
        List<DirectionalCandidate> directional = directionalPriority ? new ArrayList<>(maxAdds * 2) : null;
        List<Long> neutral = directionalPriority ? new ArrayList<>(maxAdds * 2) : null;
        int scanLimit = Math.max(maxAdds * 4, 128);

        while (scanned < offsetCount && scanned < scanLimit && toAdd.size() < maxAdds) {
            long packedOffset = offsets[index];
            int dx = unpackX(packedOffset);
            int dz = unpackZ(packedOffset);
            long key = ChunkPos.asLong(input.centerChunkX() + dx, input.centerChunkZ() + dz);
            scanned++;
            index = (index + 1) % offsetCount;

            if (input.sentChunks().contains(key)
                    || input.queuedChunks().contains(key)
                    || input.inflightChunks().contains(key)
                    || rebuiltQueued.contains(key)) {
                continue;
            }
            if (input.temporarilyUnavailable() != null && input.temporarilyUnavailable().test(key)) {
                continue;
            }

            if (!directionalPriority) {
                toAdd.add(key);
                continue;
            }

            double score = directionalScore(
                    dx,
                    dz,
                    input.movementDx(),
                    input.movementDz(),
                    input.headingX(),
                    input.headingZ()
            );
            if (score >= 0.0d) {
                directional.add(new DirectionalCandidate(key, score));
            } else {
                neutral.add(key);
            }
        }

        if (directionalPriority) {
            directional.sort(Comparator.comparingDouble(DirectionalCandidate::score).reversed());
            for (int i = 0; i < directional.size() && toAdd.size() < maxAdds; i++) {
                toAdd.add(directional.get(i).chunkKey());
            }
            for (int i = 0; i < neutral.size() && toAdd.size() < maxAdds; i++) {
                toAdd.add(neutral.get(i));
            }
        }

        int nextCursor = offsetCount == 0 ? 0 : Math.floorMod(cursor + scanned, offsetCount);
        return new PlanResult(chunksToUnload, rebuiltQueue, rebuiltQueued, toAdd, nextCursor);
    }

    private boolean isNeeded(int centerX, int centerZ, int radiusSq, int safeSquare, long chunkKey) {
        int dx = ChunkPos.getX(chunkKey) - centerX;
        int dz = ChunkPos.getZ(chunkKey) - centerZ;
        int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
        // Keep vanilla-owned chunks untouched: inside server view radius we never plan fake chunks.
        if (chebyshev <= safeSquare) {
            return false;
        }
        return dx * dx + dz * dz <= radiusSq;
    }

    private long[] getSortedOffsets(int radius, int safeSquare) {
        long cacheKey = (((long) radius) << 32) | (safeSquare & 0xFFFFFFFFL);
        return this.sortedOffsetsCache.computeIfAbsent(cacheKey, ignored -> this.buildSortedOffsets(radius, safeSquare));
    }

    private long[] buildSortedOffsets(int radius, int safeSquare) {
        List<long[]> sortable = new ArrayList<>();
        int radiusSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
                if (chebyshev <= safeSquare) {
                    continue;
                }
                int distSq = dx * dx + dz * dz;
                if (distSq > radiusSq) {
                    continue;
                }
                sortable.add(new long[]{pack(dx, dz), distSq});
            }
        }
        sortable.sort(Comparator.comparingLong(v -> v[1]));
        long[] offsets = new long[sortable.size()];
        for (int i = 0; i < sortable.size(); i++) {
            offsets[i] = sortable.get(i)[0];
        }
        return offsets;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    private double directionalScore(
            int dx,
            int dz,
            int movementDx,
            int movementDz,
            double headingX,
            double headingZ
    ) {
        double score = 0.0d;
        if (movementDx != 0 || movementDz != 0) {
            score += (dx * movementDx + dz * movementDz) * 1.25d;
        }
        if (headingX != 0.0d || headingZ != 0.0d) {
            score += dx * headingX + dz * headingZ;
        }
        return score;
    }

    private record DirectionalCandidate(long chunkKey, double score) {
    }
}

