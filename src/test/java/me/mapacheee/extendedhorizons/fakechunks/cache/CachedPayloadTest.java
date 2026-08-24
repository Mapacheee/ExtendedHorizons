package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CachedPayloadTest {

    @Test
    void acquiredReferenceSurvivesCacheClose() {
        ByteBuf source = Unpooled.buffer().writeInt(0x12345678);
        CachedPayload cached = CachedPayload.retain(source);
        assertNotNull(cached);

        ByteBuf acquired = cached.acquire();
        assertNotNull(acquired);
        cached.close();
        source.release();

        assertEquals(0x12345678, acquired.readInt());
        acquired.release();
        assertEquals(0, source.refCnt());
    }

    @Test
    void acquireAndCloseAreAtomic() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                ByteBuf source = Unpooled.buffer().writeInt(iteration);
                CachedPayload cached = CachedPayload.retain(source);
                assertNotNull(cached);
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<ByteBuf> acquired = new AtomicReference<>();

                Future<?> acquireTask = executor.submit(() -> {
                    await(start);
                    acquired.set(cached.acquire());
                });
                Future<?> closeTask = executor.submit(() -> {
                    await(start);
                    cached.close();
                });

                start.countDown();
                acquireTask.get();
                closeTask.get();
                source.release();

                ByteBuf result = acquired.get();
                if (result != null) {
                    assertEquals(iteration, result.readInt());
                    result.release();
                }
                assertEquals(0, source.refCnt());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
