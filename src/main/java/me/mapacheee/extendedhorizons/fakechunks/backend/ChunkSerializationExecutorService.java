package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public final class ChunkSerializationExecutorService {

    private static final String THREAD_PREFIX = "EH-ChunkSerializer-";

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
        this.executor = Executors.newFixedThreadPool(workers, new SerializerThreadFactory());
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


