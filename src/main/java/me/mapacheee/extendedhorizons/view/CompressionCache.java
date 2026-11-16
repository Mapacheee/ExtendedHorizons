package me.mapacheee.extendedhorizons.view;

import io.netty.buffer.ByteBuf;
import com.google.common.collect.MapMaker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class CompressionCache {

  private static final long C1 = 0x87c37b91114253d5L;
  private static final long C2 = 0x4cf5ad432745937fL;

  private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
  private final Map<Object, Alias> packets = new MapMaker().weakKeys().makeMap();
  private final Map<Key, Entry> order = new LinkedHashMap<>();
  private final Object eviction = new Object();
  private final long maxBytes;
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder encodedHits = new LongAdder();
  private long bytes;

  CompressionCache(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  Lookup lookup(ByteBuf source, int threshold) {
    Key key = hashKey(source, threshold);
    Entry entry = this.entries.get(key);
    if (entry != null) {
      this.hits.increment();
      return new Lookup(key, entry.data());
    }
    this.misses.increment();
    return new Lookup(key, null);
  }

  Lookup lookup(Object packet, int threshold, int packetId) {
    Alias alias = this.packets.get(packet);
    if (alias == null || alias.packetId() != packetId || alias.key().threshold() != threshold) {
      return null;
    }
    Entry entry = this.entries.get(alias.key());
    if (entry == null) {
      return null;
    }
    this.hits.increment();
    this.encodedHits.increment();
    return new Lookup(alias.key(), entry.data());
  }

  void remember(Object packet, int packetId, Key key) {
    this.packets.put(packet, new Alias(packetId, key));
  }

  void forgetPacket(Object packet) {
    Alias alias = this.packets.get(packet);
    if (alias != null) {
      this.packets.remove(packet, alias);
    }
  }

  void store(Lookup lookup, ByteBuf compressed) {
    int weight = compressed.readableBytes() + 192;
    Key key = lookup.key();
    if (weight > this.maxBytes || this.entries.containsKey(key)) {
      return;
    }
    byte[] data = new byte[compressed.readableBytes()];
    compressed.getBytes(compressed.readerIndex(), data);
    Entry entry = new Entry(data);
    synchronized (this.eviction) {
      if (this.order.containsKey(key)) {
        return;
      }
      this.order.put(key, entry);
      this.entries.put(key, entry);
      this.bytes += weight;
      if (this.bytes > this.maxBytes) {
        var iterator = this.order.entrySet().iterator();
        while (this.bytes > this.maxBytes) {
          var removed = iterator.next();
          this.entries.remove(removed.getKey(), removed.getValue());
          this.bytes -= removed.getValue().weight();
          iterator.remove();
        }
      }
    }
  }

  Map<String, Long> stats() {
    long size;
    long count;
    synchronized (this.eviction) {
      size = this.bytes;
      count = this.order.size();
    }
    return Map.of("compressionHits", this.hits.sum(), "compressionMisses", this.misses.sum(),
      "compressionBytes", size, "compressionEntries", count, "encodedHits", this.encodedHits.sum());
  }

  private static Key hashKey(ByteBuf source, int threshold) {
    int length = source.readableBytes();
    int readerIndex = source.readerIndex();
    long h1 = 0;
    long h2 = 0;
    int nblocks = length / 16;
    for (int i = 0; i < nblocks; i++) {
      int idx = readerIndex + i * 16;
      long k1 = source.getLongLE(idx);
      long k2 = source.getLongLE(idx + 8);

      k1 *= C1;
      k1 = Long.rotateLeft(k1, 31);
      k1 *= C2;
      h1 ^= k1;

      h1 = Long.rotateLeft(h1, 27);
      h1 += h2;
      h1 = h1 * 5 + 0x52dce729L;

      k2 *= C2;
      k2 = Long.rotateLeft(k2, 33);
      k2 *= C1;
      h2 ^= k2;

      h2 = Long.rotateLeft(h2, 31);
      h2 += h1;
      h2 = h2 * 5 + 0x38495ab5L;
    }

    int tail = readerIndex + nblocks * 16;
    long k1 = 0;
    long k2 = 0;
    switch (length & 15) {
      case 15: k2 ^= ((long) source.getByte(tail + 14) & 0xffL) << 48;
      case 14: k2 ^= ((long) source.getByte(tail + 13) & 0xffL) << 40;
      case 13: k2 ^= ((long) source.getByte(tail + 12) & 0xffL) << 32;
      case 12: k2 ^= ((long) source.getByte(tail + 11) & 0xffL) << 24;
      case 11: k2 ^= ((long) source.getByte(tail + 10) & 0xffL) << 16;
      case 10: k2 ^= ((long) source.getByte(tail + 9) & 0xffL) << 8;
      case 9:  k2 ^= ((long) source.getByte(tail + 8) & 0xffL);
               k2 *= C2;
               k2 = Long.rotateLeft(k2, 33);
               k2 *= C1;
               h2 ^= k2;
      case 8:  k1 ^= ((long) source.getByte(tail + 7) & 0xffL) << 56;
      case 7:  k1 ^= ((long) source.getByte(tail + 6) & 0xffL) << 48;
      case 6:  k1 ^= ((long) source.getByte(tail + 5) & 0xffL) << 40;
      case 5:  k1 ^= ((long) source.getByte(tail + 4) & 0xffL) << 32;
      case 4:  k1 ^= ((long) source.getByte(tail + 3) & 0xffL) << 24;
      case 3:  k1 ^= ((long) source.getByte(tail + 2) & 0xffL) << 16;
      case 2:  k1 ^= ((long) source.getByte(tail + 1) & 0xffL) << 8;
      case 1:  k1 ^= ((long) source.getByte(tail) & 0xffL);
               k1 *= C1;
               k1 = Long.rotateLeft(k1, 31);
               k1 *= C2;
               h1 ^= k1;
    }

    h1 ^= length;
    h2 ^= length;

    h1 += h2;
    h2 += h1;

    h1 = fmix64(h1);
    h2 = fmix64(h2);

    h1 += h2;
    h2 += h1;

    return new Key(threshold, length, h1, h2);
  }

  private static long fmix64(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }

  record Key(int threshold, int size, long a, long b) {}

  record Lookup(Key key, byte[] data) {}

  private record Alias(int packetId, Key key) {}

  private record Entry(byte[] data) {
    int weight() {
      return this.data.length + 192;
    }
  }
}
