package me.mapacheee.extendedhorizons.viewdistance.entity;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.bukkit.block.Biome.*;

/* Chunk region entity that represents a group of chunks for optimized processing
 * Handles fake chunk generation and caching for performance
 */

public class ChunkRegion {

    private final int regionX;
    private final int regionZ;
    private final World world;
    private final Map<ViewMap.ChunkCoordinate, FakeChunkData> fakeChunks;
    private final long creationTime;
    private volatile long lastAccessTime;
    private volatile boolean cached;

    public ChunkRegion(int regionX, int regionZ, World world) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.world = world;
        this.fakeChunks = new ConcurrentHashMap<>();
        this.creationTime = System.currentTimeMillis();
        this.lastAccessTime = creationTime;
        this.cached = false;
    }

    public FakeChunkData getFakeChunk(int chunkX, int chunkZ) {
        ViewMap.ChunkCoordinate coord = new ViewMap.ChunkCoordinate(chunkX, chunkZ);
        this.lastAccessTime = System.currentTimeMillis();

        return fakeChunks.computeIfAbsent(coord, k -> generateFakeChunk(chunkX, chunkZ));
    }

    private FakeChunkData generateFakeChunk(int chunkX, int chunkZ) {
        long seed = world.getSeed();

        int surfaceHeight = generateSurfaceHeight(chunkX, chunkZ, seed);
        Biome biome = generateBiome(chunkX, chunkZ, seed);
        Material surfaceMaterial = getSurfaceMaterial(biome, surfaceHeight);

        return new FakeChunkData(chunkX, chunkZ, surfaceHeight, biome, surfaceMaterial);
    }

    private int generateSurfaceHeight(int chunkX, int chunkZ, long seed) {
        long hash = seed;
        hash = hash * 31 + chunkX;
        hash = hash * 31 + chunkZ;

        double noise = Math.sin(hash * 0.01) * Math.cos(hash * 0.013);
        noise += Math.sin(hash * 0.007) * Math.cos(hash * 0.011) * 0.5;
        noise += Math.sin(hash * 0.003) * Math.cos(hash * 0.009) * 0.25;

        return (int) (64 + noise * 32);
    }

    private Biome generateBiome(int chunkX, int chunkZ, long seed) {
        long hash = seed;
        hash = hash * 31 + chunkX;
        hash = hash * 31 + chunkZ;

        double temperature = Math.sin(hash * 0.005) * 0.5 + 0.5;
        double humidity = Math.cos(hash * 0.007) * 0.5 + 0.5;

        if (temperature > 0.8) {
            return humidity > 0.5 ? Biome.JUNGLE : Biome.DESERT;
        } else if (temperature < 0.2) {
            return humidity > 0.5 ? Biome.SNOWY_TAIGA : Biome.SNOWY_PLAINS;
        } else {
            return humidity > 0.5 ? Biome.FOREST : Biome.PLAINS;
        }
    }

    private Material getSurfaceMaterial(Biome biome, int height) {
        String key = biome.key().asString();

        return switch (key) {
            case "minecraft:desert" -> Material.SAND;
            case "minecraft:snowy_plains", "minecraft:snowy_taiga" -> Material.SNOW_BLOCK;
            case "minecraft:jungle", "minecraft:forest" -> Material.GRASS_BLOCK;
            case "minecraft:ocean", "minecraft:river" -> height < 63 ? Material.WATER : Material.GRASS_BLOCK;
            default -> Material.GRASS_BLOCK;
        };
    }


    public void clearCache() {
        fakeChunks.clear();
        cached = false;
    }

    public boolean shouldEvict(long maxAge) {
        return System.currentTimeMillis() - lastAccessTime > maxAge;
    }

    public int getRegionX() {
        return regionX;
    }

    public int getRegionZ() {
        return regionZ;
    }

    public World getWorld() {
        return world;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

    public int getCachedChunkCount() {
        return fakeChunks.size();
    }

    public record FakeChunkData(
        int chunkX,
        int chunkZ,
        int surfaceHeight,
        Biome biome,
        Material surfaceMaterial
    ) {
        public boolean hasWater() {
            return surfaceHeight < 63;
        }

        public boolean isFlat() {
            return Math.abs(surfaceHeight - 64) < 5;
        }

        public boolean isMountainous() {
            return surfaceHeight > 80;
        }
    }
}
