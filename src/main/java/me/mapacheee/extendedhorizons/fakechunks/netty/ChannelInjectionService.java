package me.mapacheee.extendedhorizons.fakechunks.netty;

import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

@Service
public final class ChannelInjectionService {

    public static final String EH_HANDLER = "eh_packet_handler";

    public void inject(Player player) {
        Channel channel = this.resolveChannel(player);
        if (channel == null || !channel.isActive()) {
            return;
        }
        Runnable action = () -> {
            if (channel.pipeline().get(EH_HANDLER) != null) {
                return;
            }
            if (channel.pipeline().get("packet_handler") == null) {
                return;
            }
            channel.pipeline().addBefore("packet_handler", EH_HANDLER, new EhPacketHandler());
        };
        this.runOnEventLoop(channel, action);
    }

    public void uninject(Player player) {
        Channel channel = this.resolveChannel(player);
        if (channel == null || !channel.isActive()) {
            return;
        }
        Runnable action = () -> {
            if (channel.pipeline().get(EH_HANDLER) != null) {
                channel.pipeline().remove(EH_HANDLER);
            }
        };
        this.runOnEventLoop(channel, action);
    }

    public boolean writeBypass(Player player, Object payload) {
        Channel channel = this.resolveChannel(player);
        return this.writeBypass(channel, payload);
    }

    public boolean writeBypass(Channel channel, Object payload) {
        if (channel == null || !channel.isActive()) {
            return false;
        }
        Runnable action = () -> channel.write(new EhBypassPacket(payload));
        this.runOnEventLoop(channel, action);
        return true;
    }

    public void flush(Player player) {
        Channel channel = this.resolveChannel(player);
        this.flush(channel);
    }

    public void flush(Channel channel) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        this.runOnEventLoop(channel, channel::flush);
    }

    public boolean executeOnEventLoop(Channel channel, Runnable runnable) {
        if (channel == null || !channel.isActive() || runnable == null) {
            return false;
        }
        this.runOnEventLoop(channel, runnable);
        return true;
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
}
