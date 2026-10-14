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
    ByteBuf root = source;
    while (root.unwrap() != null) {
      root = root.unwrap();
    }
    if (root.readerIndex() != source.readerIndex() || root.readableBytes() != source.readableBytes()) {
      return new Request(null, false, null);
    }
    Entry entry = current.lookup(new Key(root, threshold, source.readerIndex(), source.readableBytes()));
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

  private record Key(ByteBuf root, int threshold, int offset, int length) {
    @Override
    public boolean equals(Object other) {
      return other instanceof Key key && this.root == key.root && this.threshold == key.threshold
        && this.offset == key.offset && this.length == key.length;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this.root) * 31 + this.threshold * 17 + this.offset * 7 + this.length;
    }
  }

  private static final class Entry implements AutoCloseable {
    private final ByteBuf root;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private CachedPayload encoded;
    private volatile byte[] fingerprint;
    private boolean closed;

    private Entry(ByteBuf root) {
      this.root = root.retain();
    }

    private synchronized void complete(ByteBuf payload, byte[] fingerprint) {
      if (this.closed) {
        CachedPayload.release(payload);
      } else {
        this.encoded = CachedPayload.takeOwnership(payload);
        this.fingerprint = fingerprint;
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
      this.root.release();
      if (this.encoded != null) {
        this.encoded.close();
        this.encoded = null;
      }
    }
  }

  private static final class Generation implements AutoCloseable {
    private final Cache<Key, Entry> cache;
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
        .weigher((Key key, Entry entry) -> (int) Math.min(Integer.MAX_VALUE,
          Math.max(minimumWeight, (long) key.root().capacity() * 2L + 8192L)))
        .expireAfterAccess(Duration.ofSeconds(Math.max(1, config.cacheTtlSeconds())))
        .scheduler(Scheduler.systemScheduler())
        .executor(Runnable::run)
        .removalListener((Key key, Entry entry, me.mapacheee.lib.caffeine.cache.RemovalCause cause) -> entry.close())
        .build();
    }

    private Entry start(Key key) {
      Entry entry = new Entry(key.root());
      Task task = new Task(entry, key.root().retainedDuplicate(), key.threshold());
      try {
        this.executor.execute(task);
      } catch (RuntimeException exception) {
        task.discard();
      }
      return entry;
    }

    private synchronized Entry lookup(Key key) {
      return this.active ? this.cache.get(key, this::start) : null;
    }

    @Override
    public synchronized void close() {
      this.active = false;
      this.cache.invalidateAll();
      this.cache.cleanUp();
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
        this.entry.complete(null, null);
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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(this.source.nioBuffer());
        byte[] fingerprint = digest.digest();
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
        this.entry.complete(output, fingerprint);
        output = null;
      } catch (RuntimeException | Error | NoSuchAlgorithmException exception) {
        this.entry.complete(null, null);
      } finally {
        CachedPayload.release(output);
        this.source.release();
      }
    }
  }
}
