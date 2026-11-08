package me.mapacheee.extendedhorizons.view;

import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class CompressionCache {

  private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  });

  private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
  private final Map<Key, Entry> order = new LinkedHashMap<>();
  private final Object eviction = new Object();
  private final long maxBytes;
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private long bytes;

  CompressionCache(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  Lookup lookup(ByteBuf source, int threshold) {
    MessageDigest digest = DIGEST.get();
    digest.reset();
    for (ByteBuffer part : source.nioBuffers()) {
      digest.update(part);
    }
    ByteBuffer hash = ByteBuffer.wrap(digest.digest());
    Key key = new Key(threshold, source.readableBytes(), hash.getLong(), hash.getLong(), hash.getLong(), hash.getLong());
    Entry entry = this.entries.get(key);
    if (entry != null) {
      this.hits.increment();
      return new Lookup(key, entry.data());
    }
    this.misses.increment();
    return new Lookup(key, null);
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
      "compressionBytes", size, "compressionEntries", count);
  }

  record Key(int threshold, int size, long a, long b, long c, long d) {}

  record Lookup(Key key, byte[] data) {}

  private record Entry(byte[] data) {
    int weight() {
      return this.data.length + 192;
    }
  }
}
