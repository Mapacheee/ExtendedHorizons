package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorldEditHookTest {
    private final UUID world = UUID.randomUUID();
    private final BulkChunkInvalidationService bulk = new BulkChunkInvalidationService(null, null, null, null, null);

    @Test
    void worldEditPublishesAfterAllCommitStepsIncludingChangesDuringCommit() throws Exception {
        AtomicReference<WorldEditInvalidationExtent> wrapper = new AtomicReference<>();
        Operation delayed = new Operation() {
            int step;
            public Operation resume(RunContext context) {
                if (++step == 1) return this;
                wrapper.get().setBlock(48, 64, 0, (BlockState) null);
                return null;
            }
            public void cancel() { }
        };
        Extent delegate = extent(delayed, true);
        wrapper.set(new WorldEditInvalidationExtent(delegate, world, bulk));
        wrapper.get().setBlock(BlockVector3.at(-1, 64, 32), (BlockState) null);
        assertTrue(drain().isEmpty());
        Operation commit = wrapper.get().commit();
        assertTrue(drain().isEmpty(), "Constructing a commit does not apply buffered changes");
        assertSame(commit, commit.resume(new RunContext()));
        assertTrue(drain().isEmpty(), "A yielded commit has not finished yet");
        assertNull(commit.resume(new RunContext()));
        List<Long> keys = drain().get(world);
        assertEquals(18, keys.size());
        assertTrue(keys.contains(ChunkKeyCodec.pack(-1, 2)));
        assertTrue(keys.contains(ChunkKeyCodec.pack(3, 0)));
    }

    @Test
    void biomeOnlyChangesAreInvalidatedAndUnchangedWritesAreIgnored() throws Exception {
        var unchanged = new WorldEditInvalidationExtent(extent(null, false), world, bulk);
        unchanged.setBlock(0, 64, 0, (BlockState) null);
        unchanged.commit().resume(new RunContext());
        assertTrue(drain().isEmpty());
        var changed = new WorldEditInvalidationExtent(extent(null, true), world, bulk);
        changed.setBiome(0, 64, 0, null);
        changed.commit().resume(new RunContext());
        assertEquals(9, drain().get(world).size());
    }

    @Test
    void cancelledCommitStillInvalidatesChangesAlreadyApplied() throws Exception {
        var changed = new WorldEditInvalidationExtent(extent(null, true), world, bulk);
        changed.setBlock(0, 64, 0, (BlockState) null);
        changed.commit().cancel();
        assertEquals(9, drain().get(world).size());
    }

    @Test
    void faweAttachesInPlaceAndPublishesOnlyAfterTheChunkWrite() throws Exception {
        AtomicReference<IBatchProcessor> registered = new AtomicReference<>();
        Extent queue = (Extent) Proxy.newProxyInstance(Extent.class.getClassLoader(), new Class<?>[]{Extent.class},
            (proxy, method, args) -> {
                if (method.getName().equals("addPostProcessor")) {
                    registered.set((IBatchProcessor) args[0]);
                    return proxy;
                }
                return null;
            });
        var event = new EditSessionEvent(null, null, -1, EditSession.Stage.BEFORE_HISTORY);
        event.setExtent(queue);
        FaweChunkInvalidationProcessor.attach(event, world, bulk);
        assertSame(queue, event.getExtent(), "FAWE must not see an unapproved custom extent replacement");
        assertNotNull(registered.get());
        IChunk chunk = (IChunk) Proxy.newProxyInstance(IChunk.class.getClassLoader(), new Class<?>[]{IChunk.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getX" -> -3;
                case "getZ" -> 7;
                default -> null;
            });
        registered.get().processSet(chunk, null, null);
        assertTrue(drain().isEmpty());
        registered.get().postProcess(chunk, null, null);
        List<Long> keys = drain().get(world);
        assertEquals(9, keys.size());
        assertTrue(keys.contains(ChunkKeyCodec.pack(-3, 7)));
        assertTrue(keys.contains(ChunkKeyCodec.pack(-4, 6)));
    }

    private static Extent extent(Operation commit, boolean changed) {
        return (Extent) Proxy.newProxyInstance(Extent.class.getClassLoader(), new Class<?>[]{Extent.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setBlock", "setBiome" -> changed;
                case "commit" -> commit;
                default -> null;
            });
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, List<Long>> drain() throws Exception {
        var field = BulkChunkInvalidationService.class.getDeclaredField("pendingInvalidations");
        field.setAccessible(true);
        return ((PendingChunkInvalidations) field.get(bulk)).drain(System.nanoTime() + 1_000_000_000L, 256);
    }
}
