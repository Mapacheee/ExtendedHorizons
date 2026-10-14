package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.thewinterframework.service.annotation.Service;

import com.thewinterframework.service.annotation.lifecycle.OnDisable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

@Service
public final class ChunkWorkSchedulerService {

  private static final int MAX_PENDING = 256;
  private static final int MAX_SNAPSHOTS_PER_CYCLE = 64;
  private static final int MAX_ACTIVE_BUILDS = 64;
  private static final int BUILDS_PER_WORKER = 32;
  private static final long SNAPSHOT_BUDGET_NANOS = 2_000_000L;

  private final ArrayDeque<PendingWork> generations = new ArrayDeque<>();
  private final ArrayDeque<PendingWork> snapshots = new ArrayDeque<>();
  private final ArrayDeque<PendingWork> preparations = new ArrayDeque<>();
  private int builds;
  private int generating;
  private int maxGenerations;
  private long snapshotNanos;
  private boolean stopping;

  public void prepare(CompletableFuture<?> future, int workers, Runnable start) {
    this.enqueue(this.preparations, future, () -> {
      future.whenComplete((payload, throwable) -> this.finishBuild(workers));
      start.run();
    });
    this.startPreparations(workers);
  }

  private void finishBuild(int workers) {
    synchronized (this) {
      this.builds--;
    }
    this.startPreparations(workers);
  }

  private void startPreparations(int workers) {
    List<PendingWork> ready = new ArrayList<>();
    synchronized (this) {
      if (this.stopping) {
        return;
      }
      this.drain(this.preparations, Math.clamp(workers * BUILDS_PER_WORKER, 16, MAX_ACTIVE_BUILDS) - this.builds, ready);
      this.builds += ready.size();
    }
    for (PendingWork work : ready) {
      try {
        work.start().run();
      } catch (RuntimeException | Error exception) {
        work.future().completeExceptionally(exception);
      }
    }
  }

  public synchronized boolean tryStartGeneration() {
    if (this.stopping || this.generating >= this.maxGenerations) {
      return false;
    }
    this.generating++;
    return true;
  }

  public synchronized void finishGeneration() {
    this.generating--;
  }

  public void beginCycle(int maxPerTick) {
    List<PendingWork> ready = new ArrayList<>();
    synchronized (this) {
      this.snapshotNanos = 0L;
      this.maxGenerations = Math.max(1, maxPerTick);
      this.drain(this.generations, Math.max(0, maxPerTick), ready);
      this.drain(this.snapshots, MAX_SNAPSHOTS_PER_CYCLE, ready);
    }
    for (PendingWork work : ready) {
      if (!work.future().isDone()) {
        try {
          work.start().run();
        } catch (RuntimeException | Error exception) {
          work.future().completeExceptionally(exception);
        }
      }
    }
  }

  public void generate(CompletableFuture<?> future, Runnable start) {
    this.enqueue(this.generations, future, start);
  }

  public void snapshot(CompletableFuture<?> future, Runnable start) {
    this.enqueue(this.snapshots, future, start);
  }

  public synchronized boolean canCapture() {
    return !this.stopping && this.snapshotNanos < SNAPSHOT_BUDGET_NANOS;
  }

  public synchronized void recordCapture(long elapsedNanos) {
    this.snapshotNanos += elapsedNanos;
  }

  private void enqueue(ArrayDeque<PendingWork> queue, CompletableFuture<?> future, Runnable start) {
    synchronized (this) {
      if (future.isDone()) {
        return;
      }
      queue.removeIf(work -> work.future().isDone());
      if (!this.stopping && queue.size() < MAX_PENDING) {
        queue.addLast(new PendingWork(future, start));
        return;
      }
    }
    future.completeExceptionally(new RejectedExecutionException("Chunk work queue unavailable"));
  }

  private void drain(ArrayDeque<PendingWork> queue, int limit, List<PendingWork> ready) {
    while (!queue.isEmpty() && limit > 0) {
      PendingWork work = queue.removeFirst();
      if (!work.future().isDone()) {
        ready.add(work);
        limit--;
      }
    }
  }

  @OnDisable
  public void onDisable() {
    List<PendingWork> cancelled = new ArrayList<>();
    synchronized (this) {
      this.stopping = true;
      cancelled.addAll(this.generations);
      cancelled.addAll(this.snapshots);
      cancelled.addAll(this.preparations);
      this.generations.clear();
      this.snapshots.clear();
      this.preparations.clear();
    }
    cancelled.forEach(work -> work.future().cancel(false));
  }

  private record PendingWork(CompletableFuture<?> future, Runnable start) {
  }
}


