package me.mapacheee.extendedhorizons.fakechunks.farplayers.cache;

import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FarPlayerCacheServiceTest {
    @Test
    void leavingAndRejoiningDoesNotRetainThePreviousEquipmentSnapshot() {
        var cache = new FarPlayerCacheService(TestContainers.containing(EhConfig.empty()));
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        FarPlayerState first = state(player, world, 1);
        cache.updateState(player, first);
        cache.updateEquipment(player, first.equipment());
        cache.removePlayer(player);
        assertNull(cache.getState(player));
        assertNull(cache.getEquipment(player));
        assertTrue(cache.getNearbyPlayers(world, 0, 0, 32).isEmpty());
        FarPlayerState rejoined = state(player, world, 2);
        cache.updateState(player, rejoined);
        cache.updateEquipment(player, rejoined.equipment());
        assertSame(rejoined, cache.getState(player));
        assertEquals(1, cache.getNearbyPlayers(world, 0, 0, 32).size());
        cache.onDisable();
        assertNull(cache.getState(player));
        assertNull(cache.getEquipment(player));
        assertTrue(cache.getNearbyPlayers(world, 0, 0, 32).isEmpty());
    }

    private static FarPlayerState state(UUID player, UUID world, int entityId) {
        return new FarPlayerState(entityId, player, world, null, 0, 64, 0, 0, 0, 0, List.of(), List.of());
    }
}
