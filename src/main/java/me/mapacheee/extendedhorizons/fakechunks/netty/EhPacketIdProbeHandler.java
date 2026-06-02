package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

final class EhPacketIdProbeHandler extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean removeAfterWrite = false;
        if (msg instanceof ByteBuf buf) {
            if (!PacketIdRegistry.hasLevelChunkWithLightId()
                && PacketIdRegistry.consumePendingLevelChunkProbe(ctx.channel())) {
                PacketIdRegistry.resolveLevelChunkWithLightId(readVarInt(buf));
            }
            if (!PacketIdRegistry.hasChunkCacheRadiusId()
                && PacketIdRegistry.consumePendingRadiusProbe(ctx.channel())) {
                PacketIdRegistry.resolveChunkCacheRadiusId(readVarInt(buf));
            }
            if (PacketIdRegistry.hasLevelChunkWithLightId()
                && PacketIdRegistry.hasChunkCacheRadiusId()
                && ctx.pipeline().get(ChannelInjectionService.EH_PACKET_ID_PROBE_HANDLER) == this) {
                removeAfterWrite = true;
            }
        }
        try {
            super.write(ctx, msg, promise);
        } finally {
            if (removeAfterWrite && ctx.pipeline().get(ChannelInjectionService.EH_PACKET_ID_PROBE_HANDLER) == this) {
                ctx.pipeline().remove(this);
            }
        }
    }

    private static int readVarInt(ByteBuf input) {
        int value = 0;
        int position = 0;
        int index = input.readerIndex();
        int writerIndex = input.writerIndex();
        while (position < 5 && index < writerIndex) {
            int currentByte = input.getByte(index++) & 0xFF;
            value |= (currentByte & 0x7F) << (position * 7);
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position++;
        }
        return -1;
    }
}
