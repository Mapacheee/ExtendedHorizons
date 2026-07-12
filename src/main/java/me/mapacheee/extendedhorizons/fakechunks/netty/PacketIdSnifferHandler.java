package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

import java.lang.reflect.Method;

final class PacketIdSnifferHandler extends ChannelOutboundHandlerAdapter {

    private static final String[] PACKET_ID_METHODS = {"packetId", "id", "getId"};

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        resolveFromPacket(msg);
        super.write(ctx, msg, promise);
    }

    static void resolveFromPacket(Object msg) {
        if (!PacketIdRegistry.hasLevelChunkWithLightId()) {
            if (msg instanceof ClientboundLevelChunkWithLightPacket) {
                int id = extractPacketId(msg);
                if (id >= 0) {
                    PacketIdRegistry.resolveLevelChunkWithLightId(id);
                }
            }
        }
        if (!PacketIdRegistry.hasChunkCacheRadiusId()) {
            if (msg instanceof ClientboundSetChunkCacheRadiusPacket) {
                int id = extractPacketId(msg);
                if (id >= 0) {
                    PacketIdRegistry.resolveChunkCacheRadiusId(id);
                }
            }
        }
    }

    private static int extractPacketId(Object packet) {
        Class<?> clazz = packet.getClass();
        for (String methodName : PACKET_ID_METHODS) {
            try {
                Method method = clazz.getMethod(methodName);
                if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) {
                    Object result = method.invoke(packet);
                    if (result instanceof Number num) {
                        return num.intValue();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }
}
