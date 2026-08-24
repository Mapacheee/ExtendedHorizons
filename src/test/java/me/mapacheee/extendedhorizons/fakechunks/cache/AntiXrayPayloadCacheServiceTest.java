package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AntiXrayPayloadCacheServiceTest {

    @Test
    void invalidationRejectsLateStalePublication() {
        AntiXrayPayloadCacheService service = new AntiXrayPayloadCacheService(
            TestContainers.containing(EhConfig.empty())
        );
        UUID worldId = UUID.randomUUID();
        ByteBuf initial = Unpooled.buffer().writeByte(1);
        service.put(worldId, 1L, "profile", EhConfig.SerializerMode.FAST, initial);
        service.invalidateChunk(worldId, 1L);
        initial.release();

        ByteBuf stale = Unpooled.buffer().writeByte(2);
        service.put(worldId, 1L, "profile", EhConfig.SerializerMode.FAST, stale);

        assertNull(service.get(worldId, 1L, "profile", EhConfig.SerializerMode.FAST));
        assertEquals(0, initial.refCnt());
        assertEquals(1, stale.refCnt());
        stale.release();
        service.onDisable();
    }

    @Test
    void oldGenerationCannotPublishIntoRebuiltCache() {
        AntiXrayPayloadCacheService service = new AntiXrayPayloadCacheService(
            TestContainers.containing(EhConfig.empty())
        );
        UUID worldId = UUID.randomUUID();
        long generation = service.generation();
        service.rebuild();
        ByteBuf stale = Unpooled.buffer().writeByte(3);

        service.put(worldId, 2L, "profile", EhConfig.SerializerMode.FAST, generation, stale);

        assertNull(service.get(worldId, 2L, "profile", EhConfig.SerializerMode.FAST));
        assertEquals(1, stale.refCnt());
        stale.release();
        service.onDisable();
    }
}
