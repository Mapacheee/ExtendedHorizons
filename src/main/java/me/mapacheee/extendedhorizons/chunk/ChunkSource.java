package me.mapacheee.extendedhorizons.chunk;

import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

public final class ChunkSource implements AutoCloseable {

  private final Plugin plugin;
  private final ThreadPoolExecutor workers;
  private final Semaphore permits;
  private final long maxCacheBytes;
  private final long ttlNanos;
  private final Map<Key, Entry> cache = new LinkedHashMap<>(256, 0.75f, true);
  private final Map<Key, Job> pending = new HashMap<>();
  private final LongAdder hits = new LongAdder();
  private final LongAdder shared = new LongAdder();
  private final LongAdder reads = new LongAdder();
  private final LongAdder captures = new LongAdder();
  private final LongAdder unavailable = new LongAdder();
  private final LongAdder failures = new LongAdder();
  private final LongAdder throttled = new LongAdder();
  private final AtomicLong nextWarning = new AtomicLong();
  private final AtomicInteger active = new AtomicInteger();
  private long cacheBytes;
  private volatile boolean closed;

  public ChunkSource(Plugin plugin, int workerCount, long maxCacheBytes, long ttlMillis, int maxInFlight) {
    this.plugin = plugin;
    this.maxCacheBytes = maxCacheBytes;
    this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    this.permits = new Semaphore(maxInFlight);
    AtomicInteger sequence = new AtomicInteger();
    this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(maxInFlight), task -> {
        Thread thread = new Thread(task, "EH-chunk-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        thread.setContextClassLoader(ChunkSource.class.getClassLoader());
        return thread;
      });
  }

  public boolean protectedWorld(World world) {
    return ((CraftWorld) world).getHandle().paperConfig().anticheat.antiXray.enabled;
  }

  public CompletableFuture<ChunkData> tryRead(World world, int x, int z) {
    if (this.closed || this.protectedWorld(world)) {
      return CompletableFuture.completedFuture(null);
    }
    Key key = new Key(world.getUID(), x, z);
    Job job;
    synchronized (this) {
      if (this.closed) {
        return CompletableFuture.completedFuture(null);
      }
      Entry cached = this.cache.get(key);
      if (cached != null) {
        if (System.nanoTime() < cached.expires()) {
          this.hits.increment();
          return CompletableFuture.completedFuture(cached.data());
        }
        this.cache.remove(key);
        this.cacheBytes -= cached.weight();
      }
      Job existing = this.pending.get(key);
      if (existing != null) {
        this.shared.increment();
        return existing.result.copy();
      }
      if (!this.permits.tryAcquire()) {
        this.throttled.increment();
        return null;
      }
      job = new Job(key);
      this.pending.put(key, job);
      this.active.incrementAndGet();
    }
    try {
      ServerLevel level = ((CraftWorld) world).getHandle();
      if (level.getChunkSource().getChunkAtIfLoadedImmediately(x, z) == null) {
        this.readDisk(level, job);
      } else if (Bukkit.isOwnedByCurrentRegion(world, x, z)) {
        this.capture(world, level, job);
      } else {
        Bukkit.getRegionScheduler().execute(this.plugin, world, x, z, () -> this.capture(world, level, job));
      }
    } catch (RuntimeException | LinkageError exception) {
      this.finish(job, null, exception);
    }
    return job.result.copy();
  }

  private void capture(World world, ServerLevel level, Job job) {
    if (this.closed || job.invalidated) {
      this.finish(job, null, null);
      return;
    }
    try {
      LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(job.key.x(), job.key.z());
      if (chunk == null) {
        this.readDisk(level, job);
      } else {
        this.captures.increment();
        this.finish(job, this.protectedWorld(world) ? null : ChunkCodec.loaded(level, chunk), null);
      }
    } catch (RuntimeException | LinkageError exception) {
      this.finish(job, null, exception);
    }
  }

  private void readDisk(ServerLevel level, Job job) {
    if (this.closed || job.invalidated) {
      this.finish(job, null, null);
      return;
    }
    this.reads.increment();
    job.io = MoonriseRegionFileIO.loadDataAsync(level, job.key.x(), job.key.z(),
      MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, (tag, failure) -> {
        if (failure != null || tag == null || this.closed || job.invalidated) {
          this.finish(job, null, failure);
          return;
        }
        try {
          this.workers.execute(() -> {
            try {
              ChunkData data = this.closed || job.invalidated ? null
                : ChunkCodec.disk(level, job.key.x(), job.key.z(), tag);
              this.finish(job, data, null);
            } catch (RuntimeException | LinkageError exception) {
              this.finish(job, null, exception);
            }
          });
        } catch (RejectedExecutionException exception) {
          this.finish(job, null, this.closed ? null : exception);
        }
      }, false, Priority.LOW);
    if (this.closed && job.io != null) {
      job.io.cancel();
    }
  }

  private void finish(Job job, ChunkData data, Throwable failure) {
    if (!job.finished.compareAndSet(false, true)) {
      return;
    }
    synchronized (this) {
      this.pending.remove(job.key, job);
      if (!this.closed && !job.invalidated) {
        long duration = data == null ? TimeUnit.SECONDS.toNanos(1) : this.ttlNanos;
        Entry entry = new Entry(data, System.nanoTime() + duration);
        Entry previous = this.cache.put(job.key, entry);
        this.cacheBytes += entry.weight() - (previous == null ? 0 : previous.weight());
        var iterator = this.cache.entrySet().iterator();
        while (this.cacheBytes > this.maxCacheBytes && iterator.hasNext()) {
          this.cacheBytes -= iterator.next().getValue().weight();
          iterator.remove();
        }
      }
    }
    this.permits.release();
    this.active.decrementAndGet();
    if (failure != null) {
      this.failures.increment();
      long now = System.nanoTime();
      long previous = this.nextWarning.get();
      if (!this.closed && now >= previous && this.nextWarning.compareAndSet(previous, now + TimeUnit.SECONDS.toNanos(30))) {
        this.plugin.getLogger().log(Level.WARNING, "Cannot prepare extended chunk " + job.key, failure);
      }
      job.result.completeExceptionally(failure);
    } else {
      if (data == null) {
        this.unavailable.increment();
      }
      job.result.complete(this.closed || job.invalidated ? null : data);
    }
  }

  public void invalidate(World world, int x, int z) {
    this.invalidate(new Key(world.getUID(), x, z));
  }

  private synchronized void invalidate(Key key) {
    Entry entry = this.cache.remove(key);
    if (entry != null) {
      this.cacheBytes -= entry.weight();
    }
    Job job = this.pending.get(key);
    if (job != null) {
      job.invalidated = true;
    }
  }

  public synchronized void invalidate(World world) {
    UUID id = world.getUID();
    this.cache.entrySet().removeIf(entry -> {
      if (entry.getKey().world().equals(id)) {
        this.cacheBytes -= entry.getValue().weight();
        return true;
      }
      return false;
    });
    this.pending.forEach((key, job) -> {
      if (key.world().equals(id)) {
        job.invalidated = true;
      }
    });
  }

  public synchronized Map<String, Long> stats() {
    return Map.ofEntries(
      Map.entry("cacheBytes", this.cacheBytes),
      Map.entry("cacheEntries", (long) this.cache.size()),
      Map.entry("cacheHits", this.hits.sum()),
      Map.entry("sharedReads", this.shared.sum()),
      Map.entry("diskReads", this.reads.sum()),
      Map.entry("loadedCaptures", this.captures.sum()),
      Map.entry("unavailable", this.unavailable.sum()),
      Map.entry("failures", this.failures.sum()),
      Map.entry("throttled", this.throttled.sum()),
      Map.entry("inFlight", (long) this.active.get())
    );
  }

  @Override
  public void close() {
    Job[] jobs;
    synchronized (this) {
      this.closed = true;
      this.cache.clear();
      this.cacheBytes = 0;
      jobs = this.pending.values().toArray(Job[]::new);
    }
    for (Job job : jobs) {
      job.invalidated = true;
      Cancellable io = job.io;
      if (io != null) {
        io.cancel();
      }
      this.finish(job, null, null);
    }
    this.workers.shutdownNow();
  }

  private record Key(UUID world, int x, int z) {
  }

  private record Entry(ChunkData data, long expires) {
    private int weight() {
      return this.data == null ? 128 : this.data.bytes() + 1024;
    }
  }

  private static final class Job {
    private final Key key;
    private final CompletableFuture<ChunkData> result = new CompletableFuture<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile boolean invalidated;
    private volatile Cancellable io;

    private Job(Key key) {
      this.key = key;
    }
  }
}
