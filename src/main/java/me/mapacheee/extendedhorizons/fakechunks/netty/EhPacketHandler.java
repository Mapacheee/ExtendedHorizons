package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

public final class EhPacketHandler extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ClientboundSetChunkCacheRadiusPacket) {
            return;
        }
        if (msg instanceof EhBypassPacket(Object payload)) {
            msg = payload;
        }
        super.write(ctx, msg, promise);
    }
}
