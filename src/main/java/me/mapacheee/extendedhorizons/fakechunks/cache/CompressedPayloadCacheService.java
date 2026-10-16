package me.mapacheee.extendedhorizons.fakechunks.cache;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import me.mapacheee.lib.caffeine.cache.Scheduler;
import net.minecraft.network.VarInt;

import java.time.Duration;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

@Service
public final class CompressedPayloadCacheService {

  private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  });

  private final Container<EhConfig> configContainer;
  private volatile Generation generation;

  @Inject
  public CompressedPayloadCacheService(Container<EhConfig> configContainer) {
    this.configContainer = configContainer;
    this.rebuild();
  }

  public Request request(ByteBuf source, int threshold) {
    Generation current = this.generation;
    if (current == null || !current.active || threshold < 0) {
      return new Request(null, false, null);
    }
    byte[] fingerprint = fingerprint(source);
    Entry entry = current.lookup(contentKey(fingerprint, source.readableBytes(), threshold), source, fingerprint);
    if (entry == null) {
      return new Request(null, false, null);
    }
    return entry.done.isDone() ? new Request(entry.acquire(), false, entry.fingerprint) : new Request(null, true, null);
  }

  public synchronized void rebuild() {
    Generation previous = this.generation;
    this.generation = new Generation(this.configContainer.get());
    if (previous != null) {
      previous.close();
    }
  }

  public NativeLookup lookupNative(ByteBuf source, int threshold) {
    Generation current = this.generation;
    if (current == null || !current.active || threshold < 0) return null;
    ContentKey key = contentKey(fingerprint(source), source.readableBytes(), threshold);
    CachedPayload cached = current.nativeCache.getIfPresent(key);
    return new NativeLookup(current, key, cached == null ? null : cached.acquire());
  }

  public void storeNative(NativeLookup lookup, ByteBuf encoded) {
    lookup.generation.storeNative(lookup.key, encoded);
  }

  public static final class NativeLookup {
    private final Generation generation;
    private final ContentKey key;
    private final ByteBuf payload;

    private NativeLookup(Generation generation, ContentKey key, ByteBuf payload) {
      this.generation = generation;
      this.key = key;
      this.payload = payload;
    }

    public ByteBuf payload() {
      return this.payload;
    }
  }

  private static byte[] fingerprint(ByteBuf source) {
    MessageDigest digest = DIGEST.get();
    digest.reset();
    digest.update(source.nioBuffer());
    return digest.digest();
  }

  private static ContentKey contentKey(byte[] fingerprint, int length, int threshold) {
    ByteBuffer hash = ByteBuffer.wrap(fingerprint);
    return new ContentKey(hash.getLong(), hash.getLong(), hash.getLong(), hash.getLong(), length, threshold);
  }

  private record ContentKey(long first, long second, long third, long fourth, int length, int threshold) {
  }

  public long getEstimatedSize() {
    Generation current = this.generation;
    return current == null ? 0L : current.cache.estimatedSize();
  }

  @OnDisable
  public synchronized void onDisable() {
    Generation previous = this.generation;
    this.generation = null;
    if (previous != null) {
      previous.close();
    }
  }

  public record Request(ByteBuf payload, boolean pending, byte[] fingerprint) {
  }

  private static final class Entry implements AutoCloseable {
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final int maxEncodedCapacity;
    private final byte[] fingerprint;
    private CachedPayload encoded;
    private boolean closed;

    private Entry(int maxEncodedCapacity, byte[] fingerprint) {
      this.maxEncodedCapacity = maxEncodedCapacity;
      this.fingerprint = fingerprint;
    }

    private synchronized void complete(ByteBuf payload) {
      if (this.closed) {
        CachedPayload.release(payload);
      } else {
        this.encoded = CachedPayload.takeOwnership(payload);
      }
      this.done.complete(null);
    }

    private synchronized ByteBuf acquire() {
      return this.closed || this.encoded == null ? null : this.encoded.acquire();
    }

    @Override
    public synchronized void close() {
      if (this.closed) {
        return;
      }
      this.closed = true;
      if (this.encoded != null) {
        this.encoded.close();
        this.encoded = null;
      }
    }
  }

  private static final class Generation implements AutoCloseable {
    private final Cache<ContentKey, Entry> cache;
    private final Cache<ContentKey, CachedPayload> nativeCache;
    private final ThreadPoolExecutor executor;
    private volatile boolean active = true;

    private Generation(EhConfig config) {
      AtomicInteger threadId = new AtomicInteger();
      this.executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
        Thread thread = new Thread(task, "EH-ChunkCompression-" + threadId.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      });
      long maxBytes = config.cacheMaxBytes();
      int minimumWeight = (int) Math.max(1L, (maxBytes + config.cacheMaxEntries() - 1L) / config.cacheMaxEntries());
      this.cache = Caffeine.newBuilder().maximumWeight(maxBytes)
        .weigher((ContentKey key, Entry entry) -> Math.max(minimumWeight, entry.maxEncodedCapacity))
        .expireAfterAccess(Duration.ofSeconds(Math.max(1, config.cacheTtlSeconds())))
        .scheduler(Scheduler.systemScheduler())
        .executor(Runnable::run)
        .recordStats()
        .removalListener((ContentKey key, Entry entry, me.mapacheee.lib.caffeine.cache.RemovalCause cause) -> entry.close())
        .build();
      long nativeMaxBytes = Math.max(1L, maxBytes / 4L);
      int nativeMinimumWeight = (int) Math.max(1L,
        (nativeMaxBytes + config.cacheMaxEntries() - 1L) / config.cacheMaxEntries());
      this.nativeCache = Caffeine.newBuilder().maximumWeight(nativeMaxBytes)
        .weigher((ContentKey key, CachedPayload payload) -> Math.max(nativeMinimumWeight, payload.capacity()))
        .expireAfterAccess(Duration.ofSeconds(Math.max(1, config.cacheTtlSeconds())))
        .scheduler(Scheduler.systemScheduler())
        .executor(Runnable::run)
        .recordStats()
        .removalListener((ContentKey key, CachedPayload payload, me.mapacheee.lib.caffeine.cache.RemovalCause cause) ->
          payload.close())
        .build();
    }

    private synchronized void storeNative(ContentKey key, ByteBuf encoded) {
      if (!this.active) return;
      CachedPayload cached = CachedPayload.takeOwnership(encoded.copy());
      try {
        CachedPayload previous = this.nativeCache.asMap().put(key, cached);
        if (previous != null) previous.close();
      } catch (RuntimeException exception) {
        try {
          this.nativeCache.asMap().remove(key, cached);
        } finally {
          cached.close();
        }
      }
    }

    private Entry start(ContentKey key, ByteBuf source, byte[] fingerprint) {
      Entry entry = new Entry(source.readableBytes() + 8192, fingerprint);
      Task task = new Task(entry, source.retainedDuplicate(), key.threshold());
      try {
        this.executor.execute(task);
      } catch (RuntimeException exception) {
        task.discard();
      }
      return entry;
    }

    private synchronized Entry lookup(ContentKey key, ByteBuf source, byte[] fingerprint) {
      return this.active ? this.cache.get(key, ignored -> this.start(key, source, fingerprint)) : null;
    }

    @Override
    public synchronized void close() {
      this.active = false;
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.nativeCache.invalidateAll();
      this.nativeCache.cleanUp();
      for (Runnable task : this.executor.shutdownNow()) {
        ((Task) task).discard();
      }
    }
  }

  private static final class Task implements Runnable {
    private final Entry entry;
    private final ByteBuf source;
    private final int threshold;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private Task(Entry entry, ByteBuf source, int threshold) {
      this.entry = entry;
      this.source = source;
      this.threshold = threshold;
    }

    private void discard() {
      if (this.claimed.compareAndSet(false, true)) {
        this.source.release();
        this.entry.complete(null);
      }
    }

    @Override
    public void run() {
      if (!this.claimed.compareAndSet(false, true)) {
        return;
      }
      ByteBuf output = null;
      try {
        int length = this.source.readableBytes();
        output = PooledByteBufAllocator.DEFAULT.buffer(Math.min(4096, length + 8192), length + 8192);
        if (length < this.threshold) {
          VarInt.write(output, 0);
          output.writeBytes(this.source, this.source.readerIndex(), length);
        } else {
          VarInt.write(output, length);
          Deflater deflater = new Deflater();
          try {
            deflater.setInput(this.source.nioBuffer());
            deflater.finish();
            while (!deflater.finished()) {
              output.ensureWritable(Math.min(4096, output.maxCapacity() - output.writerIndex()));
              int written = deflater.deflate(output.nioBuffer(output.writerIndex(), output.writableBytes()));
              output.writerIndex(output.writerIndex() + written);
              if (written == 0 && !deflater.finished()) {
                throw new IllegalStateException("Chunk compression made no progress");
              }
            }
          } finally {
            deflater.end();
          }
        }
        this.entry.complete(output);
        output = null;
      } catch (RuntimeException | Error exception) {
        this.entry.complete(null);
      } finally {
        CachedPayload.release(output);
        this.source.release();
      }
    }
  }
}
