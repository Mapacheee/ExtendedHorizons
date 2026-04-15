package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkPosAccess;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.level.ChunkPos;

public final class EhPacketHandler extends ChannelOutboundHandlerAdapter {

    private volatile PlayerSession session;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ClientboundLevelChunkWithLightPacket) {
            PacketIdRegistry.markPendingLevelChunkProbe(ctx.channel());
        }
        if (this.handle(msg)) {
            return;
        }
        if (msg instanceof EhBypassPacket(Object payload)) {
            msg = payload;
        }
        super.write(ctx, msg, promise);
    }

    private boolean handle(Object input) {
        PlayerSession session = this.session;
        if (session == null || !session.enabled()) {
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
            case ClientboundLoginPacket __ -> {
                session.handleDimensionReset();
                yield false;
            }
            case ClientboundStartConfigurationPacket __ -> {
                session.handleDimensionReset();
                yield false;
            }
            case ClientboundRespawnPacket __ -> {
                session.handleDimensionReset();
                yield false;
            }
            case ClientboundSetChunkCacheRadiusPacket __ -> true;
            default -> false;
        };
    }

    public void setSession(PlayerSession session) {
        this.session = session;
    }
}
