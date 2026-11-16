package me.mapacheee.extendedhorizons.chunk;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

public record ChunkData(ClientboundLevelChunkWithLightPacket packet, int bytes, Revision revision,
  Signature signature, ClientboundLightUpdatePacket lightPacket) {

  ChunkData(ClientboundLevelChunkWithLightPacket packet, int bytes) {
    this(packet, bytes, null, null, null);
  }

  public ChunkData(ClientboundLevelChunkWithLightPacket packet, int bytes, Revision revision) {
    this(packet, bytes, revision, null, null);
  }

  public boolean current() {
    return this.revision == null || this.revision.current;
  }

  static final class Revision {
    volatile boolean current = true;
  }

  public record Signature(long terrainA, long terrainB, long terrainC, long terrainD,
    long lightA, long lightB, long lightC, long lightD, int lightBytes) {

    public boolean sameTerrain(Signature other) {
      return this.terrainA == other.terrainA && this.terrainB == other.terrainB
        && this.terrainC == other.terrainC && this.terrainD == other.terrainD;
    }

    public boolean sameLight(Signature other) {
      return this.lightBytes >= 0 && this.lightBytes == other.lightBytes
        && this.lightA == other.lightA && this.lightB == other.lightB
        && this.lightC == other.lightC && this.lightD == other.lightD;
    }

    public Signature withoutLight() {
      return this.lightBytes < 0 ? this : new Signature(this.terrainA, this.terrainB, this.terrainC, this.terrainD,
        0, 0, 0, 0, -1);
    }
  }
}
