package me.mapacheee.extendedhorizons.fakechunks.planner;

import com.thewinterframework.service.annotation.Service;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

@Service
public final class ChunkPlannerService {

    public static final int MAX_CHUNK_DISTANCE = 128;
    private static final long[][] RADIUS_ITERATION_LIST = new long[MAX_CHUNK_DISTANCE + 3][];

    static {
        for (int radius = 0; radius < RADIUS_ITERATION_LIST.length; radius++) {
            int current = radius;
            List<Integer> range = IntStream.rangeClosed(-radius, radius).boxed().toList();
            RADIUS_ITERATION_LIST[radius] = range.stream()
                    .flatMap(x -> range.stream().map(z -> ChunkPos.asLong(x, z)))
                    .filter(key -> isWithinRange(ChunkPos.getX(key), ChunkPos.getZ(key), current))
                    .sorted(Comparator.comparingInt(key -> {
                        int x = ChunkPos.getX(key);
                        int z = ChunkPos.getZ(key);
                        return x * x + z * z;
                    }))
                    .mapToLong(Long::longValue)
                    .toArray();
        }
    }

    public static long[] radiusIterationList(int radius) {
        int index = Math.clamp(radius, 0, MAX_CHUNK_DISTANCE + 2);
        return RADIUS_ITERATION_LIST[index];
    }

    public static boolean isWithinRange(int posX, int posZ, int viewDistance) {
        int absX = Math.abs(posX);
        int absZ = Math.abs(posZ);
        int squareDistance = Math.max(absX, absZ);
        if (squareDistance > viewDistance + 1) {
            return false;
        }
        long distX = Math.max(0, absX - 2);
        long distZ = Math.max(0, absZ - 2);
        long distSq = distX * distX + distZ * distZ;
        int viewSq = viewDistance * viewDistance;
        return distSq < viewSq;
    }
}

