package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LightPayloadCacheServiceTest {

    @Test
    void acquiredPayloadSurvivesCacheRebuild() {
        LightPayloadCacheService service = new LightPayloadCacheService(TestContainers.containing(EhConfig.empty()));
        ByteBuf source = Unpooled.buffer().writeInt(88);
        UUID worldId = UUID.randomUUID();
        service.put(worldId, 1L, source);

        ByteBuf acquired = service.get(worldId, 1L);
        assertNotNull(acquired);
        service.rebuild();
        source.release();

        assertEquals(88, acquired.readInt());
        acquired.release();
        assertEquals(0, source.refCnt());
        service.onDisable();
    }

    @Test
    void invalidationRejectsLateStalePublication() {
        LightPayloadCacheService service = new LightPayloadCacheService(TestContainers.containing(EhConfig.empty()));
        UUID worldId = UUID.randomUUID();
        ByteBuf initial = Unpooled.buffer().writeByte(1);
        service.put(worldId, 2L, initial);
        service.invalidate(worldId, 2L);
        initial.release();

        ByteBuf stale = Unpooled.buffer().writeByte(2);
        service.put(worldId, 2L, stale);

        assertEquals(0, initial.refCnt());
        assertEquals(1, stale.refCnt());
        assertNull(service.get(worldId, 2L));
        stale.release();
        service.onDisable();
    }

    @Test
    void oldGenerationCannotPublishIntoRebuiltCache() {
        LightPayloadCacheService service = new LightPayloadCacheService(TestContainers.containing(EhConfig.empty()));
        UUID worldId = UUID.randomUUID();
        long generation = service.generation();
        service.rebuild();
        ByteBuf stale = Unpooled.buffer().writeByte(3);

        service.put(worldId, 3L, generation, stale);

        assertNull(service.get(worldId, 3L));
        assertEquals(1, stale.refCnt());
        stale.release();
        service.onDisable();
    }
}
