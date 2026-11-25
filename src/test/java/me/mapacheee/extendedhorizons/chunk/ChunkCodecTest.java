package me.mapacheee.extendedhorizons.chunk;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCodecTest {

  @Test
  void acceptsSavedStarlightFromBothCompatibleVersions() {
    CompoundTag tag = new CompoundTag();
    tag.putBoolean("isLightOn", false);
    for (int version : new int[]{10, 11}) {
      tag.putInt("starlight.light_version", version);
      assertTrue(ChunkCodec.hasCompatibleStarlight(tag));
    }
  }

  @Test
  void leavesUnmarkedAndUnknownLightingToTheExistingFallback() {
    CompoundTag tag = new CompoundTag();
    tag.putInt("starlight.light_version", 10);
    assertFalse(ChunkCodec.hasCompatibleStarlight(tag));
    tag.putString("isLightOn", "false");
    assertFalse(ChunkCodec.hasCompatibleStarlight(tag));

    tag.putBoolean("isLightOn", false);
    for (int version : new int[]{9, 12}) {
      tag.putInt("starlight.light_version", version);
      assertFalse(ChunkCodec.hasCompatibleStarlight(tag));
    }
    tag.remove("starlight.light_version");
    assertFalse(ChunkCodec.hasCompatibleStarlight(tag));
  }
}
