package me.mapacheee.extendedhorizons.chunk;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public record ChunkData(ClientboundLevelChunkWithLightPacket packet, int bytes, Revision revision) {

  ChunkData(ClientboundLevelChunkWithLightPacket packet, int bytes) {
    this(packet, bytes, null);
  }

  public boolean current() {
    return this.revision == null || this.revision.current;
  }

  static final class Revision {
    volatile boolean current = true;
  }
}
