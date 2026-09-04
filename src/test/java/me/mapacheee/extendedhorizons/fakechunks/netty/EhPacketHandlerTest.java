package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EhPacketHandlerTest {

    @Test
    void bundledForgetUsesServerChunkStateMachine() {
        PlayerSession session = readySession(5, 6);
        long chunkKey = ChunkKeyCodec.pack(5, 6);
        session.serverChunkAdd(5, 6);
        EhPacketHandler handler = new EhPacketHandler();
        handler.setSession(session);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6));
        ClientboundBlockChangedAckPacket survivor = new ClientboundBlockChangedAckPacket(7);
        List<Packet<? super ClientGamePacketListener>> packets = List.of(forget, survivor);

        assertTrue(channel.writeOutbound(new ClientboundBundlePacket(packets)));

        assertInstanceOf(ClientboundBlockChangedAckPacket.class, channel.readOutbound());
        assertNull(channel.readOutbound());
        assertEquals(chunkKey, session.pollNextChunkKey());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void bundledVanillaCenterInvalidatesEhCenter() {
        PlayerSession session = readySession(2, 3);
        long advertised = ChunkKeyCodec.pack(2, 3);
        session.lastAdvertisedChunkKey(advertised);
        EhPacketHandler handler = new EhPacketHandler();
        handler.setSession(session);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ClientboundSetChunkCacheCenterPacket center = new ClientboundSetChunkCacheCenterPacket(2, 3);
        List<Packet<? super ClientGamePacketListener>> packets = List.of(center);

        assertTrue(channel.writeOutbound(new ClientboundBundlePacket(packets)));

        assertInstanceOf(ClientboundBundlePacket.class, channel.readOutbound());
        assertNotEquals(advertised, session.lastAdvertisedChunkKey());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void bundledForgetUpdatesTrackingWhileFakeChunksAreDisabled() {
        PlayerSession session = readySession(5, 6);
        long chunkKey = ChunkKeyCodec.pack(5, 6);
        session.serverChunkAdd(5, 6);
        session.enabled(false);
        EhPacketHandler handler = new EhPacketHandler();
        handler.setSession(session);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6));
        ClientboundBlockChangedAckPacket survivor = new ClientboundBlockChangedAckPacket(7);
        List<Packet<? super ClientGamePacketListener>> packets = List.of(forget, survivor);

        assertTrue(channel.writeOutbound(new ClientboundBundlePacket(packets)));

        assertInstanceOf(ClientboundBundlePacket.class, channel.readOutbound());
        assertNull(channel.readOutbound());
        session.enabled(true);
        assertEquals(chunkKey, session.pollNextChunkKey());
        assertFalse(channel.finishAndReleaseAll());
    }

    private static PlayerSession readySession(int chunkX, int chunkZ) {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
        session.setChunkPos(chunkX, chunkZ);
        session.updateDistance(3);
        session.enabled(true);
        return session;
    }
}
