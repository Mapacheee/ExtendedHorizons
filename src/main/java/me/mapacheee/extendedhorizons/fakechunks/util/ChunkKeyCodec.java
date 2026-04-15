package me.mapacheee.extendedhorizons.fakechunks.util;

public final class ChunkKeyCodec {

    private ChunkKeyCodec() {}

    public static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int x(long chunkKey) {
        return (int) chunkKey;
    }

    public static int z(long chunkKey) {
        return (int) (chunkKey >>> 32);
    }
}

