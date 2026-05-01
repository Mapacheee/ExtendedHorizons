package me.mapacheee.extendedhorizons.fakechunks.planner;

import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

@Service
public final class ChunkPlannerService {

    public static final int MAX_CHUNK_DISTANCE = 128;
    private static final int MAX_RADIUS_INDEX = MAX_CHUNK_DISTANCE + 2;
    private static final int EDGE_BUFFER = 2;
    private static final long[][] RADIUS_ITERATION_LIST = new long[MAX_RADIUS_INDEX + 1][];

    static {
        for (int radius = 0; radius < RADIUS_ITERATION_LIST.length; radius++) {
            int current = radius;
            List<Integer> range = IntStream.rangeClosed(-radius, radius).boxed().toList();
            RADIUS_ITERATION_LIST[radius] = range.stream()
                .flatMap(x -> range.stream().map(z -> ChunkKeyCodec.pack(x, z)))
                .filter(key -> isWithinRange(ChunkKeyCodec.x(key), ChunkKeyCodec.z(key), current))
                .sorted(Comparator.comparingInt(key -> {
                    int x = ChunkKeyCodec.x(key);
                    int z = ChunkKeyCodec.z(key);
                    return x * x + z * z;
                }))
                .mapToLong(Long::longValue)
                .toArray();
        }
    }

    public static long[] radiusIterationList(int radius) {
        int index = Math.clamp(radius, 0, MAX_RADIUS_INDEX);
        return RADIUS_ITERATION_LIST[index];
    }

    public static boolean isWithinRange(int posX, int posZ, int viewDistance) {
        int absX = Math.abs(posX);
        int absZ = Math.abs(posZ);
        int outerBound = Math.max(absX, absZ);
        if (outerBound > viewDistance + 1) {
            return false;
        }
        long effectiveX = Math.max(0, absX - EDGE_BUFFER);
        long effectiveZ = Math.max(0, absZ - EDGE_BUFFER);
        long distSq = effectiveX * effectiveX + effectiveZ * effectiveZ;
        return distSq < (long) viewDistance * viewDistance;
    }
}

