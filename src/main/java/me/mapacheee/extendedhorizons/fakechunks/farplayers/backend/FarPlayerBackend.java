package me.mapacheee.extendedhorizons.fakechunks.farplayers.backend;

import com.mojang.datafixers.util.Pair;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public interface FarPlayerBackend {
    Object createPlayerInfoPacket(FarPlayerState state);
    Object createSpawnPacket(FarPlayerState state);
    Object createMovePacket(FarPlayerState state);
    Object createDespawnPacket(int entityId);
    Object createEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> equipment);
    Object createMetadataPacket(int entityId, List<SynchedEntityData.DataValue<?>> metadata);
    Object createRotateHeadPacket(int entityId, float headYaw);
}
