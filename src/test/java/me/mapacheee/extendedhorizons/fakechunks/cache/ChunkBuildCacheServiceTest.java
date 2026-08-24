package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBuildCacheServiceTest {

    @Test
    void deduplicatesBuildAndGivesEachSubscriberOwnership() {
        ChunkBuildCacheService service = new ChunkBuildCacheService(TestContainers.containing(EhConfig.empty()));
        UUID worldId = UUID.randomUUID();
        CompletableFuture<ByteBuf> backend = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();

        CompletableFuture<ByteBuf> first = service.getOrStartBuildFuture(worldId, 10L, () -> {
            starts.incrementAndGet();
            return backend;
        });
        CompletableFuture<ByteBuf> second = service.getOrStartBuildFuture(worldId, 10L, () -> {
            starts.incrementAndGet();
            return backend;
        });

        ByteBuf built = Unpooled.buffer().writeInt(123);
        backend.complete(built);
        ByteBuf firstPayload = first.join();
        ByteBuf secondPayload = second.join();

        assertEquals(1, starts.get());
        assertNotNull(firstPayload);
        assertNotNull(secondPayload);
        assertEquals(123, firstPayload.getInt(firstPayload.readerIndex()));
        assertEquals(123, secondPayload.getInt(secondPayload.readerIndex()));
        firstPayload.release();
        secondPayload.release();

        ByteBuf cached = service.getSerialized(worldId, 10L);
        assertNotNull(cached);
        service.invalidate(worldId, 10L);
        assertEquals(123, cached.readInt());
        cached.release();
        assertEquals(0, built.refCnt());
        service.onDisable();
    }

    @Test
    void invalidatedBuildCannotRepublishStalePayload() {
        ChunkBuildCacheService service = new ChunkBuildCacheService(TestContainers.containing(EhConfig.empty()));
        UUID worldId = UUID.randomUUID();
        CompletableFuture<ByteBuf> backend = new CompletableFuture<>();
        CompletableFuture<ByteBuf> result = service.getOrStartBuildFuture(worldId, 20L, () -> backend);

        service.invalidate(worldId, 20L);
        assertTrue(result.isCancelled());
        assertTrue(backend.isCancelled());

        ByteBuf stale = Unpooled.buffer().writeByte(1);
        assertFalse(backend.complete(stale));

        assertEquals(1, stale.refCnt());
        stale.release();
        assertNull(service.getSerialized(worldId, 20L));
        service.onDisable();
    }

    @Test
    void cancelledSubscriberAllowsFreshBuild() {
        ChunkBuildCacheService service = new ChunkBuildCacheService(TestContainers.containing(EhConfig.empty()));
        UUID worldId = UUID.randomUUID();
        CompletableFuture<ByteBuf> firstBackend = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<ByteBuf> first = service.getOrStartBuildFuture(worldId, 30L, () -> {
            starts.incrementAndGet();
            return firstBackend;
        });

        assertTrue(first.cancel(false));
        assertTrue(firstBackend.isCancelled());
        CompletableFuture<ByteBuf> second = service.getOrStartBuildFuture(worldId, 30L, () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(Unpooled.buffer().writeByte(2));
        });
        ByteBuf secondPayload = second.join();

        assertEquals(2, starts.get());
        assertEquals(2, secondPayload.readUnsignedByte());
        secondPayload.release();

        ByteBuf stale = Unpooled.buffer().writeByte(1);
        assertFalse(firstBackend.complete(stale));
        assertEquals(1, stale.refCnt());
        stale.release();
        service.onDisable();
    }

    @Test
    void rebuildChangesGenerationAndDrainsCachedPayload() {
        ChunkBuildCacheService service = new ChunkBuildCacheService(TestContainers.containing(EhConfig.empty()));
        UUID worldId = UUID.randomUUID();
        long initialGeneration = service.generation();
        ByteBuf built = Unpooled.buffer().writeByte(4);
        ByteBuf result = service.getOrStartBuildFuture(
            worldId,
            40L,
            () -> CompletableFuture.completedFuture(built)
        ).join();
        result.release();

        service.rebuildCaches();

        assertTrue(service.generation() > initialGeneration);
        assertEquals(0, built.refCnt());
        assertNull(service.getSerialized(worldId, 40L));
        service.onDisable();
    }
}
