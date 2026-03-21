package me.mapacheee.extendedhorizons.chunk.tracker;

import net.minecraft.world.level.ChunkPos;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Tracks which fake chunks have been sent to a specific player.
 * Handles calculation of chunks to load/unload based on view distance.
 */
public class PlayerChunkTracker {

    private final Set<Long> sentChunks = ConcurrentHashMap.newKeySet();
    private int lastChunkX = Integer.MAX_VALUE;
    private int lastChunkZ = Integer.MAX_VALUE;

    public boolean hasMovedChunk(int chunkX, int chunkZ) {
        return chunkX != lastChunkX || chunkZ != lastChunkZ;
    }

    public void updatePosition(int chunkX, int chunkZ) {
        this.lastChunkX = chunkX;
        this.lastChunkZ = chunkZ;
    }

    public void markChunkSent(int chunkX, int chunkZ) {
        sentChunks.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void markChunkUnloaded(int chunkX, int chunkZ) {
        sentChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
    }

    public Set<Long> getSentChunks() {
        return Collections.unmodifiableSet(sentChunks);
    }
}
