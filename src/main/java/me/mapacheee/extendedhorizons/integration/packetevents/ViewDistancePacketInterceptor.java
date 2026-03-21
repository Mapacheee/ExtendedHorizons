package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewDistance;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.viewdistance.ClientViewDistanceService;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ViewDistancePacketInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ViewDistancePacketInterceptor.class);
    private static final int MAX_TARGET_DISTANCE = 32;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("extendedhorizons.debug", "true"));
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastLogMs = new java.util.concurrent.ConcurrentHashMap<>();

    private PacketListenerAbstract listener;
    private final Provider<ClientViewDistanceService> clientViewDistanceService;
    private final Provider<FakeChunkService> fakeChunkServiceProvider;

    @Inject
    public ViewDistancePacketInterceptor(Provider<ClientViewDistanceService> clientViewDistanceService, Provider<FakeChunkService> fakeChunkServiceProvider) {
        this.clientViewDistanceService = clientViewDistanceService;
        this.fakeChunkServiceProvider = fakeChunkServiceProvider;
    }

    @OnEnable
    public void onEnable() {
        listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Player player = event.getPlayer();
                if (player == null) return;
                if (!player.isOnline()) return;
                if (player.getTicksLived() < 40) return;

                int targetDistance;
                try {
                    int client = clientViewDistanceService.get().getOrDefault(player.getUniqueId(), MAX_TARGET_DISTANCE);
                    targetDistance = Math.min(MAX_TARGET_DISTANCE, client);
                } catch (Throwable ignored) {
                    targetDistance = MAX_TARGET_DISTANCE;
                }
                if (targetDistance < 2) targetDistance = 2;
                final int effectiveTargetDistance = targetDistance;

                if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
                    WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
                    int chunkX = wrapper.getChunkX();
                    int chunkZ = wrapper.getChunkZ();

                    int playerChunkX = player.getLocation().getBlockX() >> 4;
                    int playerChunkZ = player.getLocation().getBlockZ() >> 4;
                    int dx = Math.abs(chunkX - playerChunkX);
                    int dz = Math.abs(chunkZ - playerChunkZ);
                    int chebyshev = Math.max(dx, dz);

                    int margin = 1;
                    if (chebyshev > effectiveTargetDistance + margin) return;
                    try {
                        if (fakeChunkServiceProvider.get().shouldCancelUnload(player.getUniqueId(), chunkX, chunkZ)) {
                            event.setCancelled(true);
                            debug(player, "unload_cancel", "[EH] cancel UNLOAD_CHUNK " + chunkX + "," + chunkZ + " dist=" + chebyshev + " target=" + effectiveTargetDistance);
                        } else {
                            debug(player, "unload_allow", "[EH] allow UNLOAD_CHUNK " + chunkX + "," + chunkZ + " dist=" + chebyshev + " target=" + effectiveTargetDistance);
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }

                if (event.getPacketType() == PacketType.Play.Server.UPDATE_VIEW_DISTANCE) {
                    WrapperPlayServerUpdateViewDistance wrapper = new WrapperPlayServerUpdateViewDistance(event);
                    int serverRadius = wrapper.getViewDistance();
                    if (serverRadius < effectiveTargetDistance) {
                        event.setCancelled(true);
                        debug(player, "vd_override", "[EH] override viewDistance server=" + serverRadius + " target=" + effectiveTargetDistance);
                        runForPlayer(player, () -> {
                            if (!player.isOnline()) return;
                            try {
                                ServerPlayer sp = ((CraftPlayer) player).getHandle();
                                sp.connection.send(new ClientboundSetChunkCacheRadiusPacket(effectiveTargetDistance));
                            } catch (Throwable t) {
                                logger.error("Failed to resend view distance packet", t);
                            }
                        });
                    }
                    return;
                }

                if (event.getPacketType() == PacketType.Play.Server.UPDATE_SIMULATION_DISTANCE) {
                    event.setCancelled(true);
                    debug(player, "sd_override", "[EH] override simulationDistance target=" + effectiveTargetDistance);
                    runForPlayer(player, () -> {
                        if (!player.isOnline()) return;
                        try {
                            ServerPlayer sp = ((CraftPlayer) player).getHandle();
                            sp.connection.send(new ClientboundSetSimulationDistancePacket(effectiveTargetDistance));
                        } catch (Throwable t) {
                            logger.error("Failed to resend simulation distance packet", t);
                        }
                    });
                }
            }
        };

        try {
            PacketEvents.getAPI().getEventManager().registerListener(listener);
        } catch (Throwable t) {
            logger.error("Failed to register PacketEvents listener", t);
        }
    }

    @OnDisable
    public void onDisable() {
        if (listener == null) return;
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        } catch (Throwable t) {
            logger.error("Failed to unregister PacketEvents listener", t);
        } finally {
            listener = null;
        }
    }

    private void runForPlayer(Player player, Runnable runnable) {
        if (player == null || runnable == null) return;
        var plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            player.getScheduler().run(plugin, task -> runnable.run(), null);
        } catch (Throwable ignored) {
            debug(player, "sched_fail", "[EH] runForPlayer failed (ignored)");
        }
    }


    private void debug(Player player, String tag, String msg) {
        if (!DEBUG) return;
        if (player == null || tag == null || msg == null) return;
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + ":" + tag;
        Long last = lastLogMs.get(key);
        if (last != null && now - last < 2000) return;
        lastLogMs.put(key, now);
        logger.info(msg);
    }
}
