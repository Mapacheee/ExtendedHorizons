package me.mapacheee.extendedhorizons.shared.util;

import me.mapacheee.extendedhorizons.viewdistance.entity.ViewMap;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* Chunk Utilities - Helper methods for chunk calculations and operations
 * Provides optimized chunk coordinate operations and distance calculations
 */

public class ChunkUtil {

    public static ViewMap.ChunkCoordinate locationToChunk(Location location) {
        return new ViewMap.ChunkCoordinate(
            location.getBlockX() >> 4,
            location.getBlockZ() >> 4
        );
    }

    public static Location chunkToLocation(ViewMap.ChunkCoordinate coord, World world) {
        return new Location(world, coord.x() << 4, 64, coord.z() << 4);
    }

    public static double getChunkDistance(ViewMap.ChunkCoordinate chunk1, ViewMap.ChunkCoordinate chunk2) {
        int dx = chunk1.x() - chunk2.x();
        int dz = chunk1.z() - chunk2.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static boolean isChunkInRadius(ViewMap.ChunkCoordinate center, ViewMap.ChunkCoordinate target, int radius) {
        return getChunkDistance(center, target) <= radius;
    }

    public static Set<ViewMap.ChunkCoordinate> getChunksInRadius(ViewMap.ChunkCoordinate center, int radius) {
        Set<ViewMap.ChunkCoordinate> chunks = new HashSet<>();

        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                ViewMap.ChunkCoordinate coord = new ViewMap.ChunkCoordinate(x, z);
                if (isChunkInRadius(center, coord, radius)) {
                    chunks.add(coord);
                }
            }
        }

        return chunks;
    }

    public static List<ViewMap.ChunkCoordinate> getChunksBetween(ViewMap.ChunkCoordinate start, ViewMap.ChunkCoordinate end) {
        List<ViewMap.ChunkCoordinate> chunks = new ArrayList<>();

        int minX = Math.min(start.x(), end.x());
        int maxX = Math.max(start.x(), end.x());
        int minZ = Math.min(start.z(), end.z());
        int maxZ = Math.max(start.z(), end.z());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                chunks.add(new ViewMap.ChunkCoordinate(x, z));
            }
        }

        return chunks;
    }

    public static String chunkToString(ViewMap.ChunkCoordinate coord) {
        return coord.x() + "," + coord.z();
    }

    public static ViewMap.ChunkCoordinate stringToChunk(String str) {
        String[] parts = str.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid chunk string format: " + str);
        }

        try {
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[1]);
            return new ViewMap.ChunkCoordinate(x, z);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid chunk coordinates: " + str, e);
        }
    }

    public static long estimateChunkMemoryUsage(Chunk chunk) {
        long baseSize = 16 * 16 * 384; // blocks
        long tileEntities = chunk.getTileEntities().length * 64L;
        long entities = chunk.getEntities().length * 32L;

        return baseSize + tileEntities + entities;
    }

    public static boolean isChunkLoaded(World world, ViewMap.ChunkCoordinate coord) {
        return world.isChunkLoaded(coord.x(), coord.z());
    }

    public static int getRegionX(ViewMap.ChunkCoordinate coord) {
        return coord.x() >> 5; // Divide by 32
    }

    public static int getRegionZ(ViewMap.ChunkCoordinate coord) {
        return coord.z() >> 5; // Divide by 32
    }

    public static ViewMap.ChunkCoordinate getRegionCoordinate(ViewMap.ChunkCoordinate coord) {
        return new ViewMap.ChunkCoordinate(getRegionX(coord), getRegionZ(coord));
    }
}
