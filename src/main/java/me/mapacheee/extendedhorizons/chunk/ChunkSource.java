package me.mapacheee.extendedhorizons.chunk;

import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.google.common.collect.MapMaker;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.compat.LightPacketAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
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
  private final Semaphore lightLoads = new Semaphore(2);
  private final EnumMap<ChunkCodec.DiskIssue, LongAdder> diskIssues = new EnumMap<>(ChunkCodec.DiskIssue.class);
  private final LongAdder missingOnDisk = new LongAdder();
  private final LongAdder recoveredLight = new LongAdder();
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
    for (var issue : ChunkCodec.DiskIssue.values()) {
      this.diskIssues.put(issue, new LongAdder());
    }
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
    Entry cached;
    Job job = null;
    boolean start = false;
    synchronized (this) {
      if (this.closed) {
        return CompletableFuture.completedFuture(null);
      }
      cached = this.cache.get(key);
      if (cached != null && System.nanoTime() >= cached.expires()) {
        this.cache.remove(key);
        this.cacheBytes -= cached.weight();
        cached = null;
      }
      if (cached == null) {
        job = this.pending.get(key);
        if (job == null && this.permits.tryAcquire()) {
          job = new Job(key, this.revision(key), null);
          job.holdingPermit = true;
          this.pending.put(key, job);
          this.active.incrementAndGet();
          start = true;
        }
      }
    }
    if (cached != null) {
      this.hits.increment();
      return CompletableFuture.completedFuture(cached.data());
    }
    if (job == null) {
      this.throttled.increment();
      return null;
    }
    if (start) {
      this.startRead(world, job);
    } else {
      this.shared.increment();
    }
    return job.result.copy();
  }

  private void startRead(World world, Job job) {
    int x = job.key.x();
    int z = job.key.z();
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

  public void invalidateEdited(World world, int x, int z) {
    if (this.revisions == null) {
      return;
    }
    Key key = new Key(world.getUID(), x, z);
    synchronized (this) {
      if (!this.closed) {
        Job previous = this.pending.get(key);
        if (!waitingForEdit(previous)) {
          this.discardEdited(key, previous);
          this.pending.remove(key);
        }
      }
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
      if (chunk == null && job.recoveringLight) {
        this.finish(job, null, null);
      } else if (chunk == null) {
        this.readDisk(level, job);
      } else {
        this.captures.increment();
        ChunkData data = ChunkCodec.loaded(level, chunk, this.mask(level), this.revisions != null);
        if (job.recoveringLight && data != null) {
          this.recoveredLight.increment();
        }
        this.finish(job, data, null);
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
          if (failure == null && tag == null && !this.closed && !job.invalidated) {
            this.missingOnDisk.increment();
            job.diskUnavailable = true;
          }
          this.finish(job, null, failure);
          return;
        }
        try {
          this.workers.execute(() -> {
            try {
              if (this.closed || job.invalidated) {
                this.finish(job, null, null);
                return;
              }
              var result = ChunkCodec.disk(level, job.key.x(), job.key.z(), tag, this.mask(level), this.revisions != null);
              if (result.issue() != null) {
                this.diskIssues.get(result.issue()).increment();
                job.diskUnavailable = result.issue() != ChunkCodec.DiskIssue.LIGHT;
              }
              if (result.issue() == ChunkCodec.DiskIssue.LIGHT) {
                this.recoverLight(level, job);
              } else {
                this.finish(job, result.data(), null);
              }
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

  private void recoverLight(ServerLevel level, Job job) {
    synchronized (this) {
      if (!this.closed && !job.invalidated && !job.finished && this.lightLoads.tryAcquire()) {
        job.recoveringLight = true;
      }
    }
    if (!job.recoveringLight) {
      this.finish(job, null, null);
      return;
    }
    // Only a verified FULL chunk reaches this point. Let Paper supply its current
    // lighting without requesting generation or keeping a plugin chunk ticket.
    World world = level.getWorld();
    int x = job.key.x();
    int z = job.key.z();
    world.getChunkAtAsync(x, z, false, false).whenComplete((chunk, error) -> {
      if (chunk == null || error != null || this.closed || job.invalidated) {
        this.finish(job, null, error);
        return;
      }
      try {
        if (Bukkit.isOwnedByCurrentRegion(world, x, z)) {
          this.capture(world, level, job);
        } else {
          Bukkit.getRegionScheduler().execute(this.plugin, world, x, z, () -> this.capture(world, level, job));
        }
      } catch (RuntimeException | LinkageError exception) {
        this.finish(job, null, exception);
      }
    });
  }

  private void finish(Job job, ChunkData data, Throwable failure) {
    boolean retry;
    synchronized (this) {
      if (job.finished) {
        return;
      }
      if (job.recoveringLight) {
        job.recoveringLight = false;
        this.lightLoads.release();
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
            data = new ChunkData(data.packet(), data.bytes(), job.revision, data.signature(), data.lightPacket());
          }
          this.cache(job, data);
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

  private void cache(Job job, ChunkData data) {
    // Load/unload events invalidate these entries immediately. A bounded expiry
    // also picks up regions written externally without a Bukkit event.
    long duration = data != null ? this.ttlNanos : TimeUnit.SECONDS.toNanos(job.diskUnavailable ? 15 : 1);
    Entry entry = new Entry(data, System.nanoTime() + duration);
    Entry previous = this.cache.put(job.key, entry);
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
    if (job != null && job.refreshWorld == null && !job.recoveringLight) {
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

  public Map<String, Long> stats() {
    long bytes;
    long entries;
    synchronized (this) {
      bytes = this.cacheBytes;
      entries = this.cache.size();
    }
    var stats = new HashMap<>(Map.ofEntries(
      Map.entry("cacheBytes", bytes),
      Map.entry("cacheEntries", entries),
      Map.entry("cacheHits", this.hits.sum()),
      Map.entry("sharedReads", this.shared.sum()),
      Map.entry("diskReads", this.reads.sum()),
      Map.entry("loadedCaptures", this.captures.sum()),
      Map.entry("unavailable", this.unavailable.sum()),
      Map.entry("failures", this.failures.sum()),
      Map.entry("throttled", this.throttled.sum()),
      Map.entry("inFlight", (long) this.active.get()),
      Map.entry("missingOnDisk", this.missingOnDisk.sum()),
      Map.entry("recoveredLight", this.recoveredLight.sum())
    ));
    this.diskIssues.forEach((reason, count) -> stats.put("diskRejected." + reason.name(), count.sum()));
    return stats;
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
      return this.data == null ? 128 : this.data.bytes() + 1024
        + (this.data.signature() != null && LightPacketAccess.copiesData() ? this.data.signature().lightBytes() : 0);
    }
  }

  private static final class Job {
    private final Key key;
    private final ChunkData.Revision revision;
    private final World refreshWorld;
    private final CompletableFuture<ChunkData> result = new CompletableFuture<>();
    private boolean finished;
    private boolean holdingPermit;
    private volatile boolean recoveringLight;
    private boolean diskUnavailable;
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
