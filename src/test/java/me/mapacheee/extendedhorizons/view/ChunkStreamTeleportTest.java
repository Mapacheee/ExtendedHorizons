package me.mapacheee.extendedhorizons.view;

import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mapacheee.extendedhorizons.compat.PacketAccess;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStreamTeleportTest {
  @Test
  void recoversAfterAnInterruptedTeleportAndALateCenterWhileStandingStill() {
    Config settings = new Config(32, null, null, null, null, null, null, null, null, false, false,
      null, null, null, null, null);
    long destination = PacketAccess.chunkKey(120, -80);
    var stream = new ChunkStream(null, settings, new ViewMetrics(), new SendBudget(settings.globalBytesPerSecond()),
      new CompressionCache(1024), LongOpenHashSet.of(destination), false);
    var channel = new EmbeddedChannel(stream);
    try {
      // Exercise packet ordering independently of disk reads and bandwidth.
      channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
      stream.tick(new ViewSnapshot(null, 0, 0, 4, 32, 0, true), null);
      assertCenter(channel, 0, 0);
      channel.readOutbound(); // initial radius

      var next = new ViewSnapshot(null, 120, -80, 4, 32, 0, true);
      stream.teleport(null, 90, -55);
      assertCenter(channel, 90, -55);
      sendPosition(channel, 1, 90, -55);
      stream.teleport(null, next.x(), next.z());
      assertCenter(channel, 120, -80);
      sendPosition(channel, 2, 120, -80);
      channel.writeInbound(new ServerboundAcceptTeleportationPacket(1, 0, 0, 0, 0, 0));
      stream.tick(next, null);
      assertTrue(stream.sleeping());
      channel.writeInbound(new ServerboundAcceptTeleportationPacket(2, 0, 0, 0, 0, 0));
      stream.tick(next, null);
      assertFalse(stream.sleeping());
      assertTrue(stream.hasPlayerTerrain(null, destination));

      // A native movement center can precede the player scheduler's first moving sample.
      assertTrue(channel.writeOutbound(new ClientboundSetChunkCacheCenterPacket(121, -80)));
      assertCenter(channel, 121, -80);
      var afterMove = new ViewSnapshot(null, 121, -80, 4, 32, 0, true);
      stream.tick(afterMove, null);

      var stale = new ClientboundSetChunkCacheCenterPacket(90, -55);
      assertTrue(channel.writeOutbound(new ClientboundBundlePacket(List.of(stale))));
      assertInstanceOf(ClientboundBundlePacket.class, channel.readOutbound());
      long now = System.nanoTime();
      stream.tick(afterMove, null, now);
      assertFalse(stream.sleeping());
      stream.tick(afterMove, null, now + 150_000_000L);
      assertCenter(channel, 121, -80);

      // A server integration that omits the position packet must not stall an idle client.
      stream.teleport(null, 130, -80);
      assertCenter(channel, 130, -80);
      stream.tick(new ViewSnapshot(null, 130, -80, 4, 32, 0, true), null, System.nanoTime() + 2_000_000_000L);
      assertFalse(stream.sleeping());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private static void sendPosition(EmbeddedChannel channel, int id, int x, int z) {
    var position = new PositionMoveRotation(new Vec3(x * 16, 80, z * 16), Vec3.ZERO, 0, 0);
    channel.writeOutbound(new ClientboundPlayerPositionPacket(id, position, Set.of()));
    assertInstanceOf(ClientboundPlayerPositionPacket.class, channel.readOutbound());
  }

  private static void assertCenter(EmbeddedChannel channel, int x, int z) {
    var center = assertInstanceOf(ClientboundSetChunkCacheCenterPacket.class, channel.readOutbound());
    assertEquals(x, center.getX());
    assertEquals(z, center.getZ());
  }
}
