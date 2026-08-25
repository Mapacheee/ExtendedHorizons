package me.mapacheee.extendedhorizons.fakechunks.farplayers.backend;

import com.mojang.datafixers.util.Pair;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.util.NmsCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

@Service
public final class PaperFarPlayerBackend implements FarPlayerBackend {

    @Override
    public Object createPlayerInfoPacket(FarPlayerState state) {
        if (state.playerInfo() == null) {
            return null;
        }
        return new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER
            ),
            state.playerInfo()
        );
    }

    @Override
    public Object createSpawnPacket(FarPlayerState state) {
        return NmsCompat.createAddPlayerPacket(
            state.entityId(),
            state.uuid(),
            state.x(),
            state.y(),
            state.z(),
            state.pitch(),
            state.yaw(),
            state.headYaw()
        );
    }

    @Override
    public Object createMovePacket(FarPlayerState state) {
        return new ClientboundEntityPositionSyncPacket(
            state.entityId(),
            new PositionMoveRotation(
                new Vec3(state.x(), state.y(), state.z()),
                Vec3.ZERO,
                state.yaw(),
                state.pitch()
            ),
            true
        );
    }

    @Override
    public Object createDespawnPacket(int entityId) {
        return new ClientboundRemoveEntitiesPacket(entityId);
    }

    @Override
    public Object createEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        return new ClientboundSetEquipmentPacket(entityId, equipment);
    }

    @Override
    public Object createMetadataPacket(int entityId, List<SynchedEntityData.DataValue<?>> metadata) {
        return new ClientboundSetEntityDataPacket(entityId, metadata);
    }

    @Override
    public Object createRotateHeadPacket(int entityId, float headYaw) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte((byte) (headYaw * 256.0F / 360.0F));
        try {
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
