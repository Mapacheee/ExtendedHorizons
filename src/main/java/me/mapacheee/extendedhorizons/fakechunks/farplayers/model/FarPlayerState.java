package me.mapacheee.extendedhorizons.fakechunks.farplayers.model;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

public record FarPlayerState(
    int entityId,
    UUID uuid,
    UUID worldId,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    float headYaw,
    List<Pair<EquipmentSlot, ItemStack>> equipment,
    List<SynchedEntityData.DataValue<?>> metadata
) {}
