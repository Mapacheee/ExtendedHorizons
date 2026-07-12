package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandler.Sharable;

/**
 * Fallback unwrap handler placed just before Paper's "encoder" in the pipeline.
 *
 * When CraftEngine is absent, {@link EhBypassPacket} objects carrying a {@link ByteBuf}
 * payload reach this handler and are unwrapped so the raw bytes flow normally through
 * "compress" and "prepender".
 *
 * <When CraftEngine IS present, its {@code craftengine_encoder} sits between this handler
 * and {@link EhPacketHandler} in the outbound direction, so it intercepts
 * {@link EhBypassPacket} first via its class-name check and this handler never sees it.
 */
@Sharable
public final class EhBypassUnwrapHandler extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof EhBypassPacket bypass) {
            super.write(ctx, bypass.payload(), promise);
            return;
        }
        super.write(ctx, msg, promise);
    }
}
