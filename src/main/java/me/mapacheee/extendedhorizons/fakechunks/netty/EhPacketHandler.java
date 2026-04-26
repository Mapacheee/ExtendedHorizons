package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.ints.IntList;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkPosAccess;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;

public final class EhPacketHandler extends ChannelOutboundHandlerAdapter {

    private volatile PlayerSession session;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ClientboundLevelChunkWithLightPacket) {
            PacketIdRegistry.markPendingLevelChunkProbe(ctx.channel());
        }
        if (this.handle(msg)) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
            return;
        }
        if (msg instanceof EhBypassPacket(Object payload)) {
            msg = payload;
        }
        super.write(ctx, msg, promise);
    }

    private boolean handle(Object input) {
        PlayerSession session = this.session;
        if (session == null) {
            return false;
        }
        this.captureEntityTracking(input, session);
        if (!session.enabled()) {
            return false;
        }
        return switch (input) {
            case ClientboundLevelChunkWithLightPacket packet -> {
                session.serverChunkAdd(packet.getX(), packet.getZ());
                yield false;
            }
            case ClientboundForgetLevelChunkPacket packet -> {
                ChunkPos pos = packet.pos();
                try {
                    yield session.serverChunkRemove(ChunkPosAccess.x(pos), ChunkPosAccess.z(pos));
                } catch (Throwable throwable) {
                    yield false;
                }
            }
            case ClientboundLoginPacket ignored -> {
                session.handleDimensionReset();
                yield false;
            }
            case ClientboundStartConfigurationPacket ignored -> {
                session.handleDimensionReset();
                yield false;
            }
            case ClientboundRespawnPacket ignored -> {
                session.handleDimensionReset();
                yield false;
            }
            case ClientboundSetChunkCacheRadiusPacket ignored -> {
                session.lastAdvertisedDistance(-1);
                yield true;
            }
            default -> false;
        };
    }

    private void captureEntityTracking(Object input, PlayerSession session) {
        switch (input) {
            case ClientboundAddEntityPacket packet -> {
                if (packet.getType() == EntityType.PLAYER) {
                    session.addServerTrackedEntity(packet.getId());
                }
            }
            case ClientboundRemoveEntitiesPacket packet -> {
                for (int entityId : extractRemovedEntityIds(packet)) {
                    session.removeServerTrackedEntity(entityId);
                }
            }
            default -> {}
        }
    }

    private static int[] extractRemovedEntityIds(ClientboundRemoveEntitiesPacket packet) {
        try {
            IntList ids = packet.getEntityIds();
            return ids.toIntArray();
        } catch (Throwable ignored) {
            return new int[0];
        }
    }

    public void setSession(PlayerSession session) {
        this.session = session;
    }
}
