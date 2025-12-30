package me.mapacheee.extendedhorizons.shared.utils;

import org.bukkit.World;
import org.bukkit.WorldBorder;

public class ChunkUtils {

    private ChunkUtils() {
    }

    /**
     * Packs chunk coordinates into a long key.
     * Standard format: Z in high 32 bits, X in low 32 bits.
     */
    public static long packChunkKey(int x, int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    /**
     * Unpacks X coordinate from chunk key.
     */
    public static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    /**
     * Unpacks Z coordinate from chunk key.
     */
    public static int unpackZ(long key) {
        return (int) (key >> 32);
    }

    /**
     * Checks if a chunk is within the world border.
     * 
     * @param world  The world to check
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is within the world border
     */
    /**
     * Checks if a chunk is within the world border (Thread-safe version).
     * 
     * @param borderCenterX Border center X
     * @param borderCenterZ Border center Z
     * @param borderSize    Border size
     * @param chunkX        Chunk X coordinate
     * @param chunkZ        Chunk Z coordinate
     * @return true if the chunk is within the world border
     */
    public static boolean isChunkWithinWorldBorder(double borderCenterX, double borderCenterZ, double borderSize,
            int chunkX, int chunkZ) {
        if (borderSize >= 5.9999968E7) { // mc max world size
            return true;
        }

        double borderRadius = borderSize / 2.0;

        double chunkBlockX = (chunkX << 4) + 8;
        double chunkBlockZ = (chunkZ << 4) + 8;

        double maxAxisDistance = borderRadius + 8;
        return Math.abs(chunkBlockX - borderCenterX) <= maxAxisDistance
                && Math.abs(chunkBlockZ - borderCenterZ) <= maxAxisDistance;
    }

    /**
     * Checks if a chunk is within the world border.
     * 
     * @param world  The world to check
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is within the world border
     */
    public static boolean isChunkWithinWorldBorder(World world, int chunkX, int chunkZ) {
        if (world == null)
            return false;

        WorldBorder border = world.getWorldBorder();
        if (border == null) {
            return true;
        }

        return isChunkWithinWorldBorder(border.getCenter().getX(), border.getCenter().getZ(), border.getSize(), chunkX,
                chunkZ);
    }
}
