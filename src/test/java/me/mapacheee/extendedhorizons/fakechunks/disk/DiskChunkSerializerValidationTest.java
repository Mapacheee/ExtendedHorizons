package me.mapacheee.extendedhorizons.fakechunks.disk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskChunkSerializerValidationTest {

    private static final int DATA_VERSION = 5000;

    @Test
    void acceptsCurrentFullChunkAtRequestedCoordinates() {
        CompoundTag tag = chunkTag(4, -7, DATA_VERSION, "minecraft:full");

        assertTrue(DiskChunkSerializer.isCompatibleChunkTag(tag, 4, -7, DATA_VERSION));
    }

    @Test
    void rejectsChunksThatRequirePaperUpgrade() {
        CompoundTag oldTag = chunkTag(4, -7, DATA_VERSION - 1, "minecraft:full");
        CompoundTag wrongCoordinates = chunkTag(5, -7, DATA_VERSION, "minecraft:full");
        CompoundTag protoChunk = chunkTag(4, -7, DATA_VERSION, "minecraft:light");

        assertFalse(DiskChunkSerializer.isCompatibleChunkTag(oldTag, 4, -7, DATA_VERSION));
        assertFalse(DiskChunkSerializer.isCompatibleChunkTag(wrongCoordinates, 4, -7, DATA_VERSION));
        assertFalse(DiskChunkSerializer.isCompatibleChunkTag(protoChunk, 4, -7, DATA_VERSION));
    }

    private static CompoundTag chunkTag(int x, int z, int dataVersion, String status) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", dataVersion);
        tag.putInt(SerializableChunkData.X_POS_TAG, x);
        tag.putInt(SerializableChunkData.Z_POS_TAG, z);
        tag.putString("Status", status);
        return tag;
    }
}
