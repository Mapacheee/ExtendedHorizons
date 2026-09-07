package me.mapacheee.extendedhorizons.fakechunks.listener;

import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.hooks.worldedit.BulkChunkInvalidationService;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChunkInvalidationListenerTest {
    @Test
    void breakRebuildsTheChunkAndItsLightNeighbors() throws Exception {
        UUID world = UUID.randomUUID();
        BulkChunkInvalidationService service = new BulkChunkInvalidationService(null, null, null, null, null, null);
        new ChunkInvalidationListener(service).onBreak(new BlockBreakEvent(block(world, -1, 32), null));
        Map<UUID, List<Long>> pending = drain(service);
        assertEquals(9, pending.get(world).size());
        assertTrue(pending.get(world).contains(ChunkKeyCodec.pack(-1, 2)));
        assertTrue(pending.get(world).contains(ChunkKeyCodec.pack(-2, 1)));
        assertTrue(pending.get(world).contains(ChunkKeyCodec.pack(0, 3)));
    }

    @Test
    void fluidCrossingAChunkBoundaryInvalidatesBothSides() throws Exception {
        UUID world = UUID.randomUUID();
        BulkChunkInvalidationService service = new BulkChunkInvalidationService(null, null, null, null, null, null);
        new ChunkInvalidationListener(service).onFlow(new BlockFromToEvent(block(world, 15, 0), block(world, 16, 0)));
        List<Long> keys = drain(service).get(world);
        assertEquals(12, keys.size());
        assertTrue(keys.contains(ChunkKeyCodec.pack(-1, 0)));
        assertTrue(keys.contains(ChunkKeyCodec.pack(2, 0)));
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, List<Long>> drain(BulkChunkInvalidationService service) throws Exception {
        var field = BulkChunkInvalidationService.class.getDeclaredField("pendingInvalidations");
        field.setAccessible(true);
        Object pending = field.get(service);
        var drain = pending.getClass().getDeclaredMethod("drain", long.class, int.class);
        drain.setAccessible(true);
        return (Map<UUID, List<Long>>) drain.invoke(pending, System.nanoTime() + 200_000_000L, 256);
    }

    private static Block block(UUID worldId, int x, int z) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> method.getName().equals("getUID") ? worldId : null);
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getX" -> x;
                case "getY" -> 64;
                case "getZ" -> z;
                case "getWorld" -> world;
                default -> null;
            });
    }
}
