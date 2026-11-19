package me.mapacheee.extendedhorizons.view;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import me.mapacheee.extendedhorizons.compat.PacketAccess;
import net.minecraft.world.level.ChunkPos;

// Queue absolute positions so moving the center cannot restart or skip unfinished work.
final class ChunkPlan {
  private final LongLinkedOpenHashSet queued = new LongLinkedOpenHashSet();
  private long[] offsets;
  private int originX;
  private int originZ;
  private int cursor;
  private int consumed;

  void rebuild(ViewSnapshot view) {
    this.clear();
    this.originX = view.x();
    this.originZ = view.z();
    this.offsets = ChunkOrder.offsets(view.distance(), view.direction());
  }

  void move(ViewSnapshot old, ViewSnapshot next) {
    if (Math.abs(next.x() - old.x()) > 1 || Math.abs(next.z() - old.z()) > 1) {
      this.queued.removeIf((long key) -> !ChunkStream.retained(next, ChunkPos.getX(key), ChunkPos.getZ(key)));
    }
    this.addDifference(next, old, next.distance(), false);
    // Paper may never have delivered a chunk before it left the native radius.
    this.addDifference(old, next, next.nativeDistance(), true);
  }

  private void addDifference(ViewSnapshot from, ViewSnapshot subtract, int radius, boolean nativeDisk) {
    for (int dz = -radius; dz <= radius; dz++) {
      int z = from.z() + dz;
      int width = extent(radius, dz, nativeDisk);
      int other = extent(radius, z - subtract.z(), nativeDisk);
      int first = from.x() - width;
      int last = from.x() + width;
      if (other < 0) {
        this.addRow(first, last, z);
      } else {
        this.addRow(first, Math.min(last, subtract.x() - other - 1), z);
        this.addRow(Math.max(first, subtract.x() + other + 1), last, z);
      }
    }
  }

  private static int extent(int radius, int z, boolean nativeDisk) {
    if (Math.abs(z) > radius) {
      return -1;
    }
    if (nativeDisk) {
      return (int) Math.sqrt(radius * radius - z * z);
    }
    int inner = Math.max(0, Math.abs(z) - 1);
    return 1 + (int) Math.sqrt(radius * radius - 1 - inner * inner);
  }

  private void addRow(int first, int last, int z) {
    for (int x = first; x <= last; x++) {
      this.add(PacketAccess.chunkKey(x, z));
    }
  }

  void add(long key) {
    this.queued.add(key);
  }

  void remove(long key) {
    this.queued.remove(key);
  }

  long first() {
    if (this.useQueue()) {
      return this.queued.firstLong();
    }
    long offset = this.offsets[this.cursor];
    return PacketAccess.chunkKey(this.originX + ChunkPos.getX(offset), this.originZ + ChunkPos.getZ(offset));
  }

  void advance() {
    if (this.useQueue()) {
      this.queued.removeFirstLong();
    } else {
      this.cursor++;
    }
    this.consumed++;
  }

  private boolean useQueue() {
    // Service both newly exposed chunks and the unfinished initial sweep while flying.
    return !this.queued.isEmpty() && (this.offsets == null || this.cursor == this.offsets.length || (this.consumed & 3) == 0);
  }

  boolean isEmpty() {
    return this.queued.isEmpty() && (this.offsets == null || this.cursor == this.offsets.length);
  }

  void clear() {
    this.queued.clear();
    this.offsets = null;
    this.cursor = 0;
    this.consumed = 0;
  }
}
