package me.mapacheee.extendedhorizons.fakechunks.netty;

import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public final class ChannelInjectionService {

    public static final String EH_HANDLER = "eh_packet_handler";
    public static final String EH_PACKET_ID_PROBE_HANDLER = "eh_packet_id_probe";
    public static final String EH_PACKET_SNIFFER = "eh_packet_sniffer";
    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    private volatile boolean stopping;

    public void inject(Player player) {
        this.inject(player, null);
    }

    public void inject(Player player, PlayerSession session) {
        if (this.stopping) {
            return;
        }
        Channel channel = this.resolveChannel(player);
        if (channel == null || !channel.isActive()) {
            return;
        }
        Runnable action = () -> {
            if (this.stopping) {
                removeHandlers(channel);
                return;
            }
            if (needsPacketIdProbe()
                && channel.pipeline().get(EH_PACKET_SNIFFER) == null) {
                channel.pipeline().addLast(EH_PACKET_SNIFFER, new PacketIdSnifferHandler());
            }
            if (channel.pipeline().get("encoder") != null
                && needsPacketIdProbe()
                && channel.pipeline().get(EH_PACKET_ID_PROBE_HANDLER) == null) {
                channel.pipeline().addBefore("encoder", EH_PACKET_ID_PROBE_HANDLER, new EhPacketIdProbeHandler());
            }
            if (channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler) {
                handler.setSession(session);
                removePacketIdProbeIfResolved(channel);
                return;
            }
            if (channel.pipeline().get("packet_handler") == null) {
                return;
            }
            EhPacketHandler handler = new EhPacketHandler();
            handler.setSession(session);
            channel.pipeline().addBefore("packet_handler", EH_HANDLER, handler);
            PacketIdRegistry.resolveFromEncoder(channel);
            removePacketIdProbeIfResolved(channel);
        };
        this.runOnEventLoop(channel, action);
    }

    public void uninject(Player player) {
        Channel channel = this.resolveChannel(player);
        if (channel == null || !channel.isActive()) {
            return;
        }
        this.runOnEventLoop(channel, () -> removeHandlers(channel));
    }

    @OnDisable
    public void onDisable() {
        this.stopping = true;
        List<Channel> channels = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = this.resolveChannel(player);
            if (channel != null && channel.isActive()) {
                channels.add(channel);
            }
        }
        CountDownLatch removed = new CountDownLatch(channels.size());
        for (Channel channel : channels) {
            try {
                this.runOnEventLoop(channel, () -> {
                    try {
                        restoreClientState(channel);
                    } finally {
                        try {
                            channel.flush();
                        } finally {
                            removeHandlers(channel);
                            removed.countDown();
                        }
                    }
                });
            } catch (RuntimeException exception) {
                removed.countDown();
            }
        }
        try {
            removed.await(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public void bindSession(Channel channel, PlayerSession session) {
        if (this.stopping || channel == null || !channel.isActive()) {
            return;
        }
        Runnable action = () -> {
            if (this.stopping) {
                removeHandlers(channel);
                return;
            }
            if (channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler) {
                handler.setSession(session);
            }
        };
        this.runOnEventLoop(channel, action);
    }

    public boolean writeBypass(Player player, Object payload) {
        Channel channel = this.resolveChannel(player);
        return this.writeBypass(channel, payload);
    }

    public boolean writeBypass(Channel channel, Object payload) {
        ChannelPromise promise = this.writeBypassFuture(channel, payload);
        return promise != null && (!promise.isDone() || promise.isSuccess());
    }

    /** Consumes reference-counted payloads on every rejected write path. */
    public ChannelPromise writeBypassFuture(Channel channel, Object payload) {
        if (payload == null) {
            return null;
        }
        if (channel == null) {
            ReferenceCountUtil.release(payload);
            return null;
        }
        ChannelPromise promise = channel.newPromise();
        if (this.stopping || !channel.isActive()) {
            ReferenceCountUtil.release(payload);
            promise.tryFailure(new IllegalStateException("Channel inactive"));
            return promise;
        }
        Runnable action = () -> {
            if (this.stopping || !channel.isActive()) {
                ReferenceCountUtil.release(payload);
                promise.tryFailure(new IllegalStateException("Channel inactive"));
                return;
            }
            ChannelHandlerContext context = channel.pipeline().context(EH_HANDLER);
            try {
                if (context == null) {
                    channel.write(payload, promise);
                } else {
                    context.write(payload, promise);
                }
            } catch (RuntimeException | Error throwable) {
                ReferenceCountUtil.release(payload);
                promise.tryFailure(throwable);
            }
        };
        try {
            this.runOnEventLoop(channel, action);
        } catch (RuntimeException exception) {
            ReferenceCountUtil.release(payload);
            promise.tryFailure(exception);
        }
        return promise;
    }

    /**
     * Consumes one owned encoded buffer reference on every return path.
     */
    public ChannelPromise writeEncodedFuture(Channel channel, ByteBuf payload) {
        if (payload == null) {
            return null;
        }
        if (channel == null) {
            ReferenceCountUtil.release(payload);
            return null;
        }
        ChannelPromise promise = channel.newPromise();
        if (this.stopping || !channel.isActive()) {
            ReferenceCountUtil.release(payload);
            promise.tryFailure(new IllegalStateException("Channel inactive"));
            return promise;
        }
        Runnable action = () -> {
            if (this.stopping || !channel.isActive()) {
                ReferenceCountUtil.release(payload);
                promise.tryFailure(new IllegalStateException("Channel inactive"));
                return;
            }
            try {
                channel.write(payload, promise);
            } catch (RuntimeException | Error throwable) {
                ReferenceCountUtil.release(payload);
                promise.tryFailure(throwable);
            }
        };
        try {
            this.runOnEventLoop(channel, action);
        } catch (RuntimeException exception) {
            ReferenceCountUtil.release(payload);
            promise.tryFailure(exception);
        }
        return promise;
    }

    public void flush(Player player) {
        Channel channel = this.resolveChannel(player);
        this.flush(channel);
    }

    public void flush(Channel channel) {
        if (this.stopping || channel == null || !channel.isActive()) {
            return;
        }
        this.runOnEventLoop(channel, channel::flush);
    }

    public void executeOnEventLoop(Channel channel, Runnable runnable) {
        if (this.stopping || channel == null || !channel.isActive() || runnable == null) {
            return;
        }
        this.runOnEventLoop(channel, runnable);
    }

    public Channel resolveChannel(Player player) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return null;
        }
        ServerPlayer serverPlayer = craftPlayer.getHandle();
        if (serverPlayer == null) {
            return null;
        }
        return serverPlayer.connection.connection.channel;
    }

    private void runOnEventLoop(Channel channel, Runnable action) {
        EventLoop eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            action.run();
            return;
        }
        eventLoop.execute(action);
    }

    private static void removeHandlers(Channel channel) {
        if (channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler) {
            handler.setSession(null);
            channel.pipeline().remove(EH_HANDLER);
        }
        if (channel.pipeline().get(EH_PACKET_ID_PROBE_HANDLER) != null) {
            channel.pipeline().remove(EH_PACKET_ID_PROBE_HANDLER);
        }
        if (channel.pipeline().get(EH_PACKET_SNIFFER) != null) {
            channel.pipeline().remove(EH_PACKET_SNIFFER);
        }
    }

    private static void restoreClientState(Channel channel) {
        if (!(channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler)) {
            return;
        }
        PlayerSession session = handler.session();
        ChannelHandlerContext context = channel.pipeline().context(EH_HANDLER);
        if (session == null || context == null) {
            return;
        }
        for (int entityId : session.trackedFarPlayers().values()) {
            context.write(new ClientboundRemoveEntitiesPacket(entityId));
        }
        session.trackedFarPlayers().clear();
        for (long chunkKey : session.loadedBvChunkKeys()) {
            context.write(new ClientboundForgetLevelChunkPacket(new ChunkPos(
                ChunkKeyCodec.x(chunkKey),
                ChunkKeyCodec.z(chunkKey)
            )));
        }
        int serverViewDistance = session.serverViewDistance();
        if (serverViewDistance > 0) {
            context.write(new ClientboundSetChunkCacheRadiusPacket(serverViewDistance));
        }
        session.unloadEhChunks();
        session.clearDispatchState();
    }

    private static boolean needsPacketIdProbe() {
        return !PacketIdRegistry.hasLevelChunkWithLightId() || !PacketIdRegistry.hasChunkCacheRadiusId();
    }

    private static void removePacketIdProbeIfResolved(Channel channel) {
        if (needsPacketIdProbe()) {
            return;
        }
        if (channel.pipeline().get(EH_PACKET_ID_PROBE_HANDLER) != null) {
            channel.pipeline().remove(EH_PACKET_ID_PROBE_HANDLER);
        }
    }
}
