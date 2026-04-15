package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

final class EhPacketIdProbeHandler extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!PacketIdRegistry.hasLevelChunkWithLightId()
            && msg instanceof ByteBuf buf
            && PacketIdRegistry.consumePendingLevelChunkProbe(ctx.channel())) {
            int packetId = readVarInt(buf);
            PacketIdRegistry.resolveLevelChunkWithLightId(packetId);
        }
        super.write(ctx, msg, promise);
    }

    private static int readVarInt(ByteBuf input) {
        ByteBuf buf = input.duplicate();
        int value = 0;
        int position = 0;
        while (position < 5 && buf.isReadable()) {
            int currentByte = buf.readByte() & 0xFF;
            value |= (currentByte & 0x7F) << (position * 7);
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position++;
        }
        return -1;
    }
}

