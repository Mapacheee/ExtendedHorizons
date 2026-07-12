package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public final class ChunkSerializationExecutorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkSerializationExecutorService.class);
    private static final String THREAD_PREFIX = "EH-ChunkSerializer-";
    private static final int MAX_QUEUED_PER_WORKER = 4;

    private final Container<EhConfig> configContainer;
    private volatile ExecutorService executor;
    private volatile boolean shutdown = false;

    @Inject
    public ChunkSerializationExecutorService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuild();
    }

    public CompletableFuture<io.netty.buffer.ByteBuf> submit(Supplier<io.netty.buffer.ByteBuf> supplier) {
        ExecutorService current = this.executor;
        if (supplier == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (current == null || this.shutdown) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable throwable) {
                CompletableFuture<io.netty.buffer.ByteBuf> failed = new CompletableFuture<>();
                failed.completeExceptionally(throwable);
                return failed;
            }
        }
        return CompletableFuture.supplyAsync(supplier, current);
    }

    public synchronized void rebuild() {
        this.shutdownExecutor();
        int workers = this.configContainer.get().serializationWorkers();
        if (workers <= 0) {
            return;
        }
        this.executor = new ThreadPoolExecutor(
            workers, workers,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(workers * MAX_QUEUED_PER_WORKER),
            new SerializerThreadFactory(),
            (runnable, pool) -> {
                LOGGER.warn("Chunk serialization queue full ({}), running on caller thread", workers * MAX_QUEUED_PER_WORKER);
                if (!pool.isShutdown()) {
                    runnable.run();
                }
            }
        );
    }

    @OnDisable
    public synchronized void onDisable() {
        this.shutdownExecutor();
    }

    private synchronized void shutdownExecutor() {
        this.shutdown = true;
        ExecutorService current = this.executor;
        this.executor = null;
        if (current != null) {
            current.shutdownNow();
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


