package me.mapacheee.extendedhorizons.chunk;

import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.google.common.collect.MapMaker;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
  private final Map<Key, ChunkData.Revision> revisions;
  private final Set<Block> hiddenBlocks;
  private final Map<ServerLevel, ChunkMask> masks = new ConcurrentHashMap<>();
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

  public ChunkSource(Plugin plugin, int workerCount, long maxCacheBytes, long ttlMillis, int maxInFlight,
    boolean updates, Config.AntiXray antiXray) {
    this.plugin = plugin;
    this.maxCacheBytes = maxCacheBytes;
    this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    this.hiddenBlocks = antiXray.enabled() ? antiXray.blocks() : Set.of();
    this.permits = new Semaphore(maxInFlight);
    this.revisions = updates ? new MapMaker().weakValues().makeMap() : null;
    AtomicInteger sequence = new AtomicInteger();
    this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(maxInFlight), task -> {
        Thread thread = new Thread(task, "EH-chunk-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        thread.setContextClassLoader(ChunkSource.class.getClassLoader());
        return thread;
      });
  }

  public boolean masking() {
    return !this.hiddenBlocks.isEmpty();
  }

  public boolean protectedWorld(World world) {
    return !this.masking() && ((CraftWorld) world).getHandle().paperConfig().anticheat.antiXray.enabled;
  }

  private ChunkMask mask(ServerLevel level) {
    return this.masking() ? this.masks.computeIfAbsent(level, key -> new ChunkMask(key, this.hiddenBlocks)) : null;
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
      job = new Job(key, this.revision(key), null);
      job.holdingPermit = true;
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

  public CompletableFuture<ChunkData> refresh(World world, int x, int z) {
    if (this.revisions == null || this.closed || this.protectedWorld(world)) {
      return null;
    }
    Key key = new Key(world.getUID(), x, z);
    Job job;
    synchronized (this) {
      if (this.closed) {
        return null;
      }
      Job previous = this.pending.get(key);
      if (waitingForEdit(previous)) {
        return null;
      }
      this.discardEdited(key, previous);
      job = new Job(key, this.revision(key), world);
      this.pending.put(key, job);
    }
    this.scheduleRefresh(job, 2L);
    return job.result.copy();
  }

  public synchronized void invalidateEdited(World world, int x, int z) {
    if (this.closed || this.revisions == null) {
      return;
    }
    Key key = new Key(world.getUID(), x, z);
    Job previous = this.pending.get(key);
    if (!waitingForEdit(previous)) {
      this.discardEdited(key, previous);
      this.pending.remove(key);
    }
  }

  private static boolean waitingForEdit(Job job) {
    return job != null && job.refreshWorld != null && job.attempts == 0;
  }

  private void discardEdited(Key key, Job previous) {
    var revision = this.revisions.remove(key);
    if (revision != null) {
      revision.current = false;
    }
    this.removeCached(key);
    if (previous != null) {
      previous.invalidated = true;
    }
  }

  private ChunkData.Revision revision(Key key) {
    return this.revisions == null ? null : this.revisions.computeIfAbsent(key, ignored -> new ChunkData.Revision());
  }

  private void scheduleRefresh(Job job, long delay) {
    try {
      Bukkit.getRegionScheduler().runDelayed(this.plugin, job.refreshWorld, job.key.x(), job.key.z(),
        task -> this.refresh(job), delay);
    } catch (RuntimeException | LinkageError exception) {
      job.invalidated = true;
      this.finish(job, null, exception);
    }
  }

  private void refresh(Job job) {
    boolean acquired;
    synchronized (this) {
      acquired = !this.closed && !job.invalidated && this.permits.tryAcquire();
      if (acquired) {
        job.holdingPermit = true;
        job.attempts++;
        this.active.incrementAndGet();
      }
    }
    if (this.closed || job.invalidated) {
      this.finish(job, null, null);
    } else if (acquired) {
      this.capture(job.refreshWorld, ((CraftWorld) job.refreshWorld).getHandle(), job);
    } else {
      this.scheduleRefresh(job, 2L);
    }
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
        this.finish(job, ChunkCodec.loaded(level, chunk, this.mask(level)), null);
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
                : ChunkCodec.disk(level, job.key.x(), job.key.z(), tag, this.mask(level));
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
    boolean retry;
    synchronized (this) {
      if (job.finished) {
        return;
      }
      if (job.holdingPermit) {
        job.holdingPermit = false;
        this.permits.release();
        this.active.decrementAndGet();
      }
      retry = data == null && !this.closed && !job.invalidated && job.refreshWorld != null && job.attempts < 4;
      if (!retry) {
        job.finished = true;
        this.pending.remove(job.key, job);
        if (!this.closed && !job.invalidated) {
          if (data != null && job.revision != null) {
            data = new ChunkData(data.packet(), data.bytes(), job.revision);
          }
          this.cache(job.key, data);
        }
      }
    }
    if (retry) {
      this.scheduleRefresh(job, 1L << job.attempts);
    } else if (job.revision != null && !job.revision.current) {
      job.result.complete(new ChunkData(null, 0, job.revision));
    } else if (failure != null) {
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

  private void cache(Key key, ChunkData data) {
    long duration = data == null ? TimeUnit.SECONDS.toNanos(1) : this.ttlNanos;
    Entry entry = new Entry(data, System.nanoTime() + duration);
    Entry previous = this.cache.put(key, entry);
    this.cacheBytes += entry.weight() - (previous == null ? 0 : previous.weight());
    var iterator = this.cache.entrySet().iterator();
    while (this.cacheBytes > this.maxCacheBytes && iterator.hasNext()) {
      this.cacheBytes -= iterator.next().getValue().weight();
      iterator.remove();
    }
  }

  public void invalidate(World world, int x, int z) {
    this.invalidate(new Key(world.getUID(), x, z));
  }

  private synchronized void invalidate(Key key) {
    this.removeCached(key);
    Job job = this.pending.get(key);
    if (job != null && job.refreshWorld == null) {
      job.invalidated = true;
    }
  }

  private void removeCached(Key key) {
    Entry entry = this.cache.remove(key);
    if (entry != null) {
      this.cacheBytes -= entry.weight();
    }
  }

  public void invalidate(World world) {
    this.masks.remove(((CraftWorld) world).getHandle());
    UUID id = world.getUID();
    var cancelled = new ArrayList<Job>();
    synchronized (this) {
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
          if (job.refreshWorld != null) {
            cancelled.add(job);
          }
        }
      });
    }
    cancelled.forEach(job -> this.finish(job, null, null));
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
    private final ChunkData.Revision revision;
    private final World refreshWorld;
    private final CompletableFuture<ChunkData> result = new CompletableFuture<>();
    private boolean finished;
    private boolean holdingPermit;
    private int attempts;
    private volatile boolean invalidated;
    private volatile Cancellable io;

    private Job(Key key, ChunkData.Revision revision, World refreshWorld) {
      this.key = key;
      this.revision = revision;
      this.refreshWorld = refreshWorld;
    }
  }
}
