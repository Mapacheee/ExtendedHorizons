package me.mapacheee.extendedhorizons.chunk.tracker;

import java.util.Set;
import net.minecraft.world.level.ChunkPos;

final class PlayerChunkStateGrid {

  private int centerX = Integer.MIN_VALUE;
  private int centerZ = Integer.MIN_VALUE;
  private int radius = -1;
  private int diameter = 0;
  private byte[] states = new byte[0];

  boolean hasBounds(int centerX, int centerZ, int radius) {
    return this.centerX == centerX && this.centerZ == centerZ && this.radius == radius;
  }

  void rebuild(Set<Long> sentChunks, int centerX, int centerZ, int radius) {
    if (radius < 0) radius = 0;
    int diameter = (radius * 2) + 1;
    int size = diameter * diameter;
    if (states.length != size) {
      states = new byte[size];
    } else {
      java.util.Arrays.fill(states, (byte) 0);
    }
    this.centerX = centerX;
    this.centerZ = centerZ;
    this.radius = radius;
    this.diameter = diameter;
    if (sentChunks == null || sentChunks.isEmpty()) return;
    for (Long chunkKey : sentChunks) {
      if (chunkKey == null) continue;
      int idx = indexFor(chunkKey);
      if (idx >= 0) {
        states[idx] = 1;
      }
    }
  }

  boolean contains(long chunkKey) {
    int idx = indexFor(chunkKey);
    return idx >= 0 && states[idx] == 1;
  }

  void set(long chunkKey, boolean sent) {
    int idx = indexFor(chunkKey);
    if (idx < 0) return;
    states[idx] = sent ? (byte) 1 : (byte) 0;
  }

  private int indexFor(long chunkKey) {
    if (radius < 0 || diameter <= 0) return -1;
    int chunkX = ChunkPos.getX(chunkKey);
    int chunkZ = ChunkPos.getZ(chunkKey);
    int localX = chunkX - centerX + radius;
    int localZ = chunkZ - centerZ + radius;
    if (localX < 0 || localZ < 0 || localX >= diameter || localZ >= diameter) return -1;
    return (localZ * diameter) + localX;
  }
}
