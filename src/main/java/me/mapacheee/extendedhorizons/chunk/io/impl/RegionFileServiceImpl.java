package me.mapacheee.extendedhorizons.chunk.io.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.chunk.io.RegionFileService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class RegionFileServiceImpl implements RegionFileService {

    private static final Logger logger = LoggerFactory.getLogger(RegionFileServiceImpl.class);
    private final ExecutorService ioExecutor;
    private final Cache<String, RegionFile> regionFileCache;

    @Inject
    public RegionFileServiceImpl() {
        this.ioExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "EH-RegionIO");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        );

        this.regionFileCache = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.MINUTES)
                .maximumSize(50)
                .removalListener((key, value, cause) -> {
                    if (value instanceof RegionFile file) {
                        try {
                            file.close();
                        } catch (IOException e) {
                            logger.error("Failed to close region file: {}", key, e);
                        }
                    }
                })
                .build();
    }

    @Override
    public CompletableFuture<CompoundTag> readChunkData(World world, int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RegionFile regionFile = getRegionFile(world, chunkX, chunkZ);
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                if (regionFile == null || !regionFile.hasChunk(pos)) {
                    return null;
                }

                try (DataInputStream inputStream = regionFile.getChunkDataInputStream(pos)) {
                    if (inputStream == null) return null;
                    return NbtIo.read(inputStream);
                }
            } catch (Exception e) {
                logger.error("Error reading chunk data for {},{}", chunkX, chunkZ, e);
                return null;
            }
        }, ioExecutor);
    }

    @Override
    public boolean hasChunk(World world, int chunkX, int chunkZ) {
        try {
            RegionFile regionFile = getRegionFile(world, chunkX, chunkZ);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            return regionFile != null && regionFile.hasChunk(pos);
        } catch (Exception e) {
            return false;
        }
    }

    @OnDisable
    public void shutdown() {
        regionFileCache.invalidateAll();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
        }
    }

    private RegionFile getRegionFile(World world, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        String key = world.getName() + "_" + regionX + "_" + regionZ;

        return regionFileCache.get(key, k -> {
            try {
                File regionDir = new File(world.getWorldFolder(), "region");

                if (!regionDir.exists()) return null;

                File file = new File(regionDir, "r." + regionX + "." + regionZ + ".mca");
                if (!file.exists()) return null;

                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey;
                switch (world.getEnvironment()) {
                    case NETHER: dimensionKey = net.minecraft.world.level.Level.NETHER; break;
                    case THE_END: dimensionKey = net.minecraft.world.level.Level.END; break;
                    default: dimensionKey = net.minecraft.world.level.Level.OVERWORLD; break;
                }
                
                String folderName = world.getEnvironment() == World.Environment.NORMAL ? "overworld" : world.getEnvironment().name().toLowerCase();
                RegionStorageInfo info = new RegionStorageInfo(folderName, dimensionKey, "chunk");
                
                return new RegionFile(info, file.toPath(), regionDir.toPath(), true);
            } catch (IOException e) {
                logger.error("Failed to open region file for {},{}", chunkX, chunkZ, e);
                return null;
            }
        });
    }
}
