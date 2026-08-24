package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSerializationExecutorServiceTest {

    @Test
    void rebuildLeavesWorkerGenerationEnabled() throws Exception {
        ChunkSerializationExecutorService service = new ChunkSerializationExecutorService(
            TestContainers.containing(configWithWorkers(1))
        );
        service.rebuild();
        AtomicReference<String> threadName = new AtomicReference<>();

        ByteBuf payload = service.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            return Unpooled.buffer().writeByte(7);
        }).get(5, TimeUnit.SECONDS);

        assertTrue(threadName.get().startsWith("EH-ChunkSerializer-"));
        assertEquals(7, payload.readUnsignedByte());
        payload.release();
        service.onDisable();
    }

    @Test
    void shutdownDiscardsQueuedTaskWithoutRunningSupplier() throws Exception {
        ChunkSerializationExecutorService service = new ChunkSerializationExecutorService(
            TestContainers.containing(configWithWorkers(1))
        );
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicReference<ByteBuf> runningResult = new AtomicReference<>();
        AtomicInteger queuedRuns = new AtomicInteger();
        AtomicInteger cleanupRuns = new AtomicInteger();

        CompletableFuture<ByteBuf> first = service.submit(() -> {
            running.countDown();
            awaitIgnoringInterrupts(finish);
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            runningResult.set(payload);
            return payload;
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        CompletableFuture<ByteBuf> queued = service.submit(() -> {
            queuedRuns.incrementAndGet();
            return Unpooled.buffer().writeByte(2);
        }, () -> {
            cleanupRuns.incrementAndGet();
            cleaned.countDown();
        });

        Thread shutdown = new Thread(service::onDisable);
        shutdown.start();
        try {
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        } finally {
            finish.countDown();
            shutdown.join(6_000L);
        }

        assertFalse(shutdown.isAlive());
        assertTrue(first.isCancelled());
        assertTrue(queued.isCancelled());
        assertEquals(0, queuedRuns.get());
        assertEquals(1, cleanupRuns.get());
        assertNotNull(runningResult.get());
        assertEquals(0, runningResult.get().refCnt());
    }

    @Test
    void shutdownDoesNotInterruptCallerThreadSerialization() throws Exception {
        ChunkSerializationExecutorService service = new ChunkSerializationExecutorService(
            TestContainers.containing(configWithWorkers(0))
        );
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<ByteBuf> produced = new AtomicReference<>();
        AtomicReference<CompletableFuture<ByteBuf>> submitted = new AtomicReference<>();

        Thread caller = new Thread(() -> submitted.set(service.submit(() -> {
            running.countDown();
            try {
                finish.await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
            }
            ByteBuf payload = Unpooled.buffer().writeByte(3);
            produced.set(payload);
            return payload;
        })));
        caller.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));

        service.onDisable();
        finish.countDown();
        caller.join(5_000L);

        assertFalse(caller.isAlive());
        assertFalse(interrupted.get());
        assertNotNull(submitted.get());
        assertTrue(submitted.get().isCancelled());
        assertNotNull(produced.get());
        assertEquals(0, produced.get().refCnt());
    }

    private static EhConfig configWithWorkers(int workers) {
        EhConfig.FakeChunksConfig fakeChunks = new EhConfig.FakeChunksConfig(
            0,
            null,
            workers,
            null,
            0,
            0,
            0,
            0,
            false,
            0L,
            null,
            null,
            null,
            null,
            null,
            null
        );
        return new EhConfig(null, fakeChunks, null, true);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
