package me.mapacheee.extendedhorizons.chunk.tracker;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/*
 * Tracks which fake chunks have been sent to a specific player.
 * Handles calculation of chunks to load/unload based on view distance.
 */
public class PlayerChunkTracker {

  private final Set<Long> sentChunks = ConcurrentHashMap.newKeySet();
  private final PlayerChunkStateGrid sentGrid = new PlayerChunkStateGrid();
  private int lastChunkX = Integer.MAX_VALUE;
  private int lastChunkZ = Integer.MAX_VALUE;
  private int plannerCursor = 0;

  public boolean hasMovedChunk(int chunkX, int chunkZ) {
    return chunkX != lastChunkX || chunkZ != lastChunkZ;
  }

  public void updatePosition(int chunkX, int chunkZ) {
    this.lastChunkX = chunkX;
    this.lastChunkZ = chunkZ;
  }

  public int lastChunkX() {
    return lastChunkX;
  }

  public int lastChunkZ() {
    return lastChunkZ;
  }

  public void markChunkSent(int chunkX, int chunkZ) {
    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
    sentChunks.add(chunkKey);
    sentGrid.set(chunkKey, true);
  }

  public void markChunkUnloaded(int chunkX, int chunkZ) {
    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
    sentChunks.remove(chunkKey);
    sentGrid.set(chunkKey, false);
  }

  public Set<Long> getSentChunks() {
    return Collections.unmodifiableSet(sentChunks);
  }

  public int plannerCursor() {
    return plannerCursor;
  }

  public void plannerCursor(int plannerCursor) {
    this.plannerCursor = Math.max(0, plannerCursor);
  }

  public void prepareSentGrid(int centerChunkX, int centerChunkZ, int radius) {
    if (sentGrid.hasBounds(centerChunkX, centerChunkZ, radius)) return;
    sentGrid.rebuild(sentChunks, centerChunkX, centerChunkZ, radius);
  }

  public boolean containsSent(long chunkKey) {
    if (sentGrid.contains(chunkKey)) return true;
    return sentChunks.contains(chunkKey);
  }
}
