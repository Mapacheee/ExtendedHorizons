package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
public final class ChunkSerializationExecutorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkSerializationExecutorService.class);
    private static final String THREAD_PREFIX = "EH-ChunkSerializer-";
    private static final int MAX_QUEUED_PER_WORKER = 4;
    private static final int SHUTDOWN_WAIT_SECONDS = 5;
    private static final long WARN_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Runnable NOOP = () -> {};

    private final AtomicLong lastWarnNanos = new AtomicLong(0L);
    private final AtomicInteger suppressedWarns = new AtomicInteger(0);

    private final Container<EhConfig> configContainer;
    private final Object lifecycleLock = new Object();
    private volatile ExecutorGeneration generation;

    @Inject
    public ChunkSerializationExecutorService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public CompletableFuture<ByteBuf> submit(Supplier<ByteBuf> supplier) {
        return this.submit(supplier, NOOP);
    }

    /**
     * The discard action releases resources captured by a task that never starts.
     */
    public CompletableFuture<ByteBuf> submit(Supplier<ByteBuf> supplier, Runnable discardAction) {
        if (supplier == null) {
            return CompletableFuture.completedFuture(null);
        }
        Runnable cleanup = discardAction == null ? NOOP : discardAction;
        ExecutorGeneration current;
        synchronized (this.lifecycleLock) {
            current = this.generation;
        }
        if (current == null) {
            return cancelledFuture(cleanup);
        }
        return current.submit(supplier, cleanup);
    }

    public void rebuild() {
        int workers = Math.max(0, this.configContainer.get().serializationWorkers());
        ExecutorGeneration next = new ExecutorGeneration(workers);
        ExecutorGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = next;
        }
        if (previous != null) {
            previous.shutdown();
        }
    }

    @OnDisable
    public void onDisable() {
        ExecutorGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = null;
        }
        if (previous != null) {
            previous.shutdown();
        }
    }

    private static CompletableFuture<ByteBuf> cancelledFuture(Runnable cleanup) {
        runCleanup(cleanup);
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }

    private static void runCleanup(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to discard chunk serialization resources", exception);
        }
    }
    private void logQueueFullWarning(int queueCapacity) {
        long now = System.nanoTime();
        long last = this.lastWarnNanos.get();
        if (now - last >= WARN_THROTTLE_NANOS && this.lastWarnNanos.compareAndSet(last, now)) {
            int suppressed = this.suppressedWarns.getAndSet(0);
            if (suppressed > 0) {
                LOGGER.warn(
                    "Chunk serialization queue full ({}), running on caller thread ({} similar warnings suppressed in the last 10s)",
                    queueCapacity, suppressed
                );
            } else {
                LOGGER.warn(
                    "Chunk serialization queue full ({}), running on caller thread",
                    queueCapacity
                );
            }
        } else {
            this.suppressedWarns.incrementAndGet();
        }
    }

    private final class ExecutorGeneration {

        private final ThreadPoolExecutor executor;
        private final int queueCapacity;
        private final Set<TrackedTask> tasks = new HashSet<>();
        private volatile boolean active = true;
        private boolean closed;

        private ExecutorGeneration(int workers) {
            if (workers <= 0) {
                this.executor = null;
                this.queueCapacity = 0;
                return;
            }
            this.queueCapacity = workers * MAX_QUEUED_PER_WORKER;
            this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(this.queueCapacity),
                new SerializerThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
            );
        }

        private CompletableFuture<ByteBuf> submit(Supplier<ByteBuf> supplier, Runnable cleanup) {
            TrackedTask task = new TrackedTask(this, supplier, cleanup);
            boolean runOnCaller = false;
            synchronized (this) {
                if (!this.active) {
                    task.cancel();
                    return task.future();
                }
                this.tasks.add(task);
                if (this.executor == null) {
                    runOnCaller = true;
                } else {
                    try {
                        this.executor.execute(task);
                    } catch (RejectedExecutionException exception) {
                        if (this.active && !this.executor.isShutdown()) {
                            ChunkSerializationExecutorService.this.logQueueFullWarning(this.queueCapacity);
                            runOnCaller = true;
                        } else {
                            task.cancel();
                        }
                    }
                }
            }
            if (runOnCaller) {
                task.runOnCaller();
            }
            return task.future();
        }

        private void retire() {
            this.active = false;
        }

        private void shutdown() {
            List<TrackedTask> accepted;
            synchronized (this) {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                this.active = false;
                if (this.executor != null) {
                    this.executor.shutdownNow();
                }
                accepted = new ArrayList<>(this.tasks);
            }
            for (TrackedTask task : accepted) {
                task.cancel();
            }
            if (this.executor != null) {
                try {
                    if (!this.executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                        LOGGER.warn("Chunk serialization workers did not stop within {} seconds", SHUTDOWN_WAIT_SECONDS);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private boolean isActive() {
            return this.active;
        }

        private synchronized void unregister(TrackedTask task) {
            this.tasks.remove(task);
        }

        private synchronized void cancelBeforeStart(TrackedTask task) {
            if (this.executor != null) {
                this.executor.remove(task);
            }
            this.tasks.remove(task);
        }
    }

    private static final class TrackedTask implements Runnable {

        private final ExecutorGeneration owner;
        private final Supplier<ByteBuf> supplier;
        private final Runnable cleanup;
        private final CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean discardResult = new AtomicBoolean();
        private volatile Thread runner;
        private volatile boolean interruptRunner;

        private TrackedTask(ExecutorGeneration owner, Supplier<ByteBuf> supplier, Runnable cleanup) {
            this.owner = owner;
            this.supplier = supplier;
            this.cleanup = cleanup;
            this.future.whenComplete((payload, throwable) -> {
                if (this.future.isCancelled()) {
                    this.cancel();
                }
            });
        }

        @Override
        public void run() {
            this.runTask(true);
        }

        private void runOnCaller() {
            this.runTask(false);
        }

        private void runTask(boolean canInterruptRunner) {
            if (!this.started.compareAndSet(false, true)) {
                return;
            }
            this.interruptRunner = canInterruptRunner;
            this.runner = Thread.currentThread();
            if (this.discardResult.get() || !this.owner.isActive()) {
                runCleanup(this.cleanup);
                this.future.cancel(false);
                this.runner = null;
                this.interruptRunner = false;
                this.owner.unregister(this);
                return;
            }

            ByteBuf payload = null;
            try {
                payload = this.supplier.get();
                if (!this.discardResult.get() && this.owner.isActive() && this.future.complete(payload)) {
                    payload = null;
                } else {
                    this.future.cancel(false);
                }
            } catch (Throwable throwable) {
                if (!this.discardResult.get() && this.owner.isActive()) {
                    this.future.completeExceptionally(throwable);
                } else {
                    this.future.cancel(false);
                }
            } finally {
                ReferenceCountUtil.release(payload);
                this.runner = null;
                this.interruptRunner = false;
                this.owner.unregister(this);
            }
        }

        private void cancel() {
            this.discardResult.set(true);
            if (!this.future.isCancelled()) {
                this.future.cancel(false);
            }
            if (this.started.compareAndSet(false, true)) {
                runCleanup(this.cleanup);
                this.owner.cancelBeforeStart(this);
                return;
            }
            Thread thread = this.runner;
            if (thread != null && this.interruptRunner) {
                thread.interrupt();
            }
        }

        private CompletableFuture<ByteBuf> future() {
            return this.future;
        }
    }

    private static final class SerializerThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_PREFIX + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
