package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntList;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class EhPacketHandler extends ChannelOutboundHandlerAdapter {

    private static final int VARINT_MAX_BYTES = 5;
    private static final MethodHandle CHUNK_POS_X_GETTER;
    private static final MethodHandle CHUNK_POS_Z_GETTER;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ChunkPos.class, MethodHandles.lookup());
            CHUNK_POS_X_GETTER = lookup.findGetter(ChunkPos.class, "x", int.class);
            CHUNK_POS_Z_GETTER = lookup.findGetter(ChunkPos.class, "z", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile PlayerSession session;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ClientboundLevelChunkWithLightPacket) {
            PacketIdRegistry.markPendingLevelChunkProbe(ctx.channel());
        }
        if (msg instanceof net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket) {
            PacketIdRegistry.markPendingRadiusProbe(ctx.channel());
        }
        PlayerSession trackingSession = this.session;
        if (trackingSession != null) {
            this.captureEntityTracking(ctx, msg, trackingSession);
        }
        if (this.handle(msg)) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
            return;
        }

        if (msg instanceof ByteBuf buf && this.isPreEncodedRadiusPacket(buf)) {
            PlayerSession session = this.session;
            if (session != null && session.enabled()) {
                session.lastAdvertisedDistance(-1);
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
                return;
            }
        }
        if (msg instanceof BundlePacket<?> bundle) {
            PlayerSession session = this.session;
            if (session != null && session.enabled()) {
                if (hasRadiusPacket(bundle)) {
                    session.lastAdvertisedDistance(-1);
                    ReferenceCountUtil.release(msg);
                    writeBundleWithoutRadius(ctx, bundle);
                    promise.setSuccess();
                    return;
                }
            }
        }
        if (msg instanceof EhBypassPacket(Object payload)) {
            msg = payload;
        }
        super.write(ctx, msg, promise);
    }

    private boolean isPreEncodedRadiusPacket(ByteBuf buf) {
        if (!PacketIdRegistry.hasChunkCacheRadiusId() || !buf.isReadable()) {
            return false;
        }
        int readerIndex = buf.readerIndex();
        try {
            int firstVarInt = readVarInt(buf);
            int targetId = PacketIdRegistry.getChunkCacheRadiusId();

            if (firstVarInt == targetId) {
                return true;
            }

            if (firstVarInt == 2 && buf.isReadable()) {
                int secondVarInt = readVarInt(buf);
                if (secondVarInt == targetId && buf.readableBytes() == 1) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            buf.readerIndex(readerIndex);
        }
        return false;
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        while (position < VARINT_MAX_BYTES) {
            if (!buf.isReadable()) {
                throw new IndexOutOfBoundsException();
            }
            int currentByte = buf.readByte() & 0xFF;
            value |= (currentByte & 0x7F) << (position * 7);
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position++;
        }
        throw new IllegalArgumentException("VarInt too big");
    }

    private static boolean hasRadiusPacket(BundlePacket<?> bundle) {
        for (Packet<?> sub : bundle.subPackets()) {
            if (sub instanceof ClientboundSetChunkCacheRadiusPacket) {
                return true;
            }
        }
        return false;
    }

    private static void writeBundleWithoutRadius(ChannelHandlerContext ctx, BundlePacket<?> bundle) {
        for (Packet<?> sub : bundle.subPackets()) {
            if (!(sub instanceof ClientboundSetChunkCacheRadiusPacket)) {
                ctx.write(sub);
            }
        }
    }

    private boolean handle(Object input) {
        PlayerSession session = this.session;
        if (session == null) {
            return false;
        }
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
                    yield session.serverChunkRemove(
                        (int) CHUNK_POS_X_GETTER.invokeExact(pos),
                        (int) CHUNK_POS_Z_GETTER.invokeExact(pos));
                } catch (Throwable e) {
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

    private void captureEntityTracking(ChannelHandlerContext ctx, Object input, PlayerSession session) {
        if (input instanceof BundlePacket<?> bundle) {
            for (Packet<?> packet : bundle.subPackets()) {
                this.captureEntityTracking(ctx, packet, session);
            }
            return;
        }
        switch (input) {
            case ClientboundAddEntityPacket packet -> {
                if (packet.getType() == EntityType.PLAYER) {
                    session.addServerTrackedEntity(packet.getId());
                    UUID targetUuid = packet.getUUID();
                    Integer farEntityId = session.trackedFarPlayers().remove(targetUuid);
                    if (farEntityId != null) {
                        ctx.write(new ClientboundRemoveEntitiesPacket(farEntityId));
                    }
                }
            }
            case ClientboundRemoveEntitiesPacket packet -> {
                try {
                    IntList ids = packet.getEntityIds();
                    for (int i = 0, size = ids.size(); i < size; i++) {
                        session.removeServerTrackedEntity(ids.getInt(i));
                    }
                } catch (Throwable ignored) {
                }
            }
            default -> {}
        }
    }

    public void setSession(PlayerSession session) {
        this.session = session;
    }
}
