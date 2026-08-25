package me.mapacheee.extendedhorizons.fakechunks.farplayers;

import com.mojang.datafixers.util.Pair;
import io.netty.channel.embedded.EmbeddedChannel;
import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarPlayerTrackingServiceTest {

    private RecordingBackend backend;
    private FarPlayerTrackingService trackingService;
    private FarPlayerCacheService cacheService;

    @BeforeEach
    void setUp() {
        EhConfig config = new EhConfig(
            new EhConfig.DebugConfig(false),
            new EhConfig.FakeChunksConfig(
                32,
                EhConfig.SerializerMode.FAST,
                1,
                new EhConfig.BandwidthConfig(false, 384000L, 768000L),
                6,
                12,
                9,
                24,
                false,
                150L,
                new EhConfig.AntiXrayConfig(false, List.of()),
                true,
                new EhConfig.CacheConfig(10, 400, 3000L, 1024, 5, 150),
                new EhConfig.RuntimeConfig(1),
                new EhConfig.FarPlayersConfig(true, 6, 30),
                new EhConfig.WorldEditConfig(true)
            ),
            Map.of(),
            true
        );

        this.backend = new RecordingBackend();
        this.cacheService = new FarPlayerCacheService(TestContainers.containing(config));
        ChannelInjectionService injectionService = new ChannelInjectionService();
        this.trackingService = new FarPlayerTrackingService(
            TestContainers.containing(config),
            this.cacheService,
            this.backend,
            injectionService
        );
    }

    @Test
    void playerSpawnsAndTracksSynchronously() {
        UUID viewerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        PlayerSession session = new PlayerSession(viewerId, worldId);
        session.setChunkPos(0, 0);
        session.updateDistance(16);
        session.enabled(true);
        session.serverViewDistance(4);

        EmbeddedChannel channel = new EmbeddedChannel();

        UUID targetId = UUID.randomUUID();
        // Place target in chunk (10, 10) which is within target distance 16
        FarPlayerState state = new FarPlayerState(
            50,
            targetId,
            worldId,
            null, // RecordingBackend handles null or mock
            160.5,
            64.0,
            160.5,
            0.0f,
            0.0f,
            0.0f,
            Collections.emptyList(),
            Collections.emptyList()
        );

        // Chunk (10, 10) is not loaded yet -> should not spawn
        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            List.of(state)
        );
        channel.runPendingTasks();
        assertFalse(session.trackedFarPlayers().containsKey(targetId));
        assertEquals(0, this.backend.spawnPackets.size());

        // Mark chunk (10, 10) as EH_LOADED by draining queue to it
        long chunkKey = ChunkKeyCodec.pack(10, 10);
        Long polled;
        while ((polled = session.pollNextChunkKey()) != null) {
            long attempt = session.beginChunkSend(polled);
            session.onChunkSent(polled, attempt);
            if (polled == chunkKey) {
                break;
            }
        }
        assertTrue(session.isChunkReadyForEntities(chunkKey));

        // Now track again -> should spawn and register synchronously
        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            List.of(state)
        );
        channel.runPendingTasks();

        assertTrue(session.trackedFarPlayers().containsKey(targetId));
        int assignedId = session.trackedFarPlayers().get(targetId);
        assertTrue(assignedId >= 1_000_000_000);
        assertEquals(1, this.backend.spawnPackets.size());
        assertEquals(1, this.backend.playerInfoPackets.size());
        assertEquals(1, this.backend.rotateHeadPackets.size());

        // Subsequent track call should move instead of respawning with new ID
        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            List.of(state)
        );
        channel.runPendingTasks();

        assertEquals(1, this.backend.spawnPackets.size(), "Should not re-spawn already tracked player");
        assertEquals(1, this.backend.movePackets.size(), "Should sync move on next tracking tick");
        assertEquals(assignedId, session.trackedFarPlayers().get(targetId));
    }

    @Test
    void despawnsWhenCandidateListBecomesEmpty() {
        UUID viewerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        PlayerSession session = new PlayerSession(viewerId, worldId);
        session.setChunkPos(0, 0);
        session.updateDistance(16);
        session.enabled(true);
        session.serverViewDistance(4);

        EmbeddedChannel channel = new EmbeddedChannel();

        UUID targetId = UUID.randomUUID();
        FarPlayerState state = new FarPlayerState(
            50,
            targetId,
            worldId,
            null,
            16.5,
            64.0,
            16.5,
            0.0f,
            0.0f,
            0.0f,
            Collections.emptyList(),
            Collections.emptyList()
        );

        // Chunk (1, 1) is within serverViewDistance (4) -> isChunkReadyForEntities is true
        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            List.of(state)
        );
        channel.runPendingTasks();

        assertTrue(session.trackedFarPlayers().containsKey(targetId));
        int farId = session.trackedFarPlayers().get(targetId);

        // Candidates list becomes empty (target left range or disconnected)
        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            Collections.emptyList()
        );
        channel.runPendingTasks();

        assertFalse(session.trackedFarPlayers().containsKey(targetId));
        assertTrue(this.backend.despawnPackets.contains(farId));
    }

    @Test
    void despawnsWhenServerBeginsTracking() {
        UUID viewerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        PlayerSession session = new PlayerSession(viewerId, worldId);
        session.setChunkPos(0, 0);
        session.updateDistance(16);
        session.enabled(true);
        session.serverViewDistance(4);

        EmbeddedChannel channel = new EmbeddedChannel();

        UUID targetId = UUID.randomUUID();
        int realEntityId = 50;
        FarPlayerState state = new FarPlayerState(
            realEntityId,
            targetId,
            worldId,
            null,
            16.5,
            64.0,
            16.5,
            0.0f,
            0.0f,
            0.0f,
            Collections.emptyList(),
            Collections.emptyList()
        );

        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            List.of(state)
        );
        channel.runPendingTasks();
        assertTrue(session.trackedFarPlayers().containsKey(targetId));

        // Server starts tracking the real entity ID
        session.addServerTrackedEntity(realEntityId);

        this.trackingService.track(
            viewerId,
            ChunkKeyCodec.pack(0, 0),
            session,
            channel,
            16,
            List.of(state)
        );
        channel.runPendingTasks();

        assertFalse(session.trackedFarPlayers().containsKey(targetId));
        assertEquals(1, this.backend.despawnPackets.size());
    }

    @Test
    void chunkReadyForEntitiesChecksServerAndFakeChunks() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
        session.setChunkPos(0, 0);
        session.updateDistance(16);
        session.serverViewDistance(5);
        session.enabled(true);

        // Chunks inside serverViewDistance (5) are ready by default
        assertTrue(session.isChunkReadyForEntities(ChunkKeyCodec.pack(0, 0)));
        assertTrue(session.isChunkReadyForEntities(ChunkKeyCodec.pack(3, 3)));
        assertTrue(session.isChunkReadyForEntities(ChunkKeyCodec.pack(-4, 0)));

        // Chunks beyond serverViewDistance (e.g. chunk 10, 10) are not ready until EH_LOADED
        long farChunk = ChunkKeyCodec.pack(10, 10);
        assertFalse(session.isChunkReadyForEntities(farChunk));

        Long polled;
        while ((polled = session.pollNextChunkKey()) != null) {
            long attempt = session.beginChunkSend(polled);
            session.onChunkSent(polled, attempt);
            if (polled == farChunk) {
                break;
            }
        }
        assertTrue(session.isChunkReadyForEntities(farChunk));

        // Also verify serverChunkAdd marks a chunk ready
        long serverChunk = ChunkKeyCodec.pack(12, 12);
        assertFalse(session.isChunkReadyForEntities(serverChunk));
        session.serverChunkAdd(12, 12);
        assertTrue(session.isChunkReadyForEntities(serverChunk));
    }

    private static final class RecordingBackend implements FarPlayerBackend {
        final List<Object> playerInfoPackets = new ArrayList<>();
        final List<Object> spawnPackets = new ArrayList<>();
        final List<Object> movePackets = new ArrayList<>();
        final List<Integer> despawnPackets = new ArrayList<>();
        final List<Object> equipmentPackets = new ArrayList<>();
        final List<Object> metadataPackets = new ArrayList<>();
        final List<Object> rotateHeadPackets = new ArrayList<>();

        @Override
        public Object createPlayerInfoPacket(FarPlayerState state) {
            Object obj = "PLAYER_INFO_" + state.uuid();
            this.playerInfoPackets.add(obj);
            return obj;
        }

        @Override
        public Object createSpawnPacket(FarPlayerState state) {
            Object obj = "SPAWN_" + state.entityId();
            this.spawnPackets.add(obj);
            return obj;
        }

        @Override
        public Object createMovePacket(FarPlayerState state) {
            Object obj = "MOVE_" + state.entityId();
            this.movePackets.add(obj);
            return obj;
        }

        @Override
        public Object createDespawnPacket(int entityId) {
            this.despawnPackets.add(entityId);
            return "DESPAWN_" + entityId;
        }

        @Override
        public Object createEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> equipment) {
            Object obj = "EQUIP_" + entityId;
            this.equipmentPackets.add(obj);
            return obj;
        }

        @Override
        public Object createMetadataPacket(int entityId, List<SynchedEntityData.DataValue<?>> metadata) {
            Object obj = "METADATA_" + entityId;
            this.metadataPackets.add(obj);
            return obj;
        }

        @Override
        public Object createRotateHeadPacket(int entityId, float headYaw) {
            Object obj = "ROTATE_HEAD_" + entityId;
            this.rotateHeadPackets.add(obj);
            return obj;
        }
    }
}
