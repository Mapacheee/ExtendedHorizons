package me.mapacheee.extendedhorizons.hooks.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewDistance;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.viewdistance.ClientViewDistanceService;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ViewDistancePacketInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ViewDistancePacketInterceptor.class);
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastLogMs = new java.util.concurrent.ConcurrentHashMap<>();

    private PacketListenerAbstract listener;
    private final Provider<ClientViewDistanceService> clientViewDistanceService;
    private final Provider<FakeChunkService> fakeChunkServiceProvider;
    private final Container<Config> configContainer;

    @Inject
    public ViewDistancePacketInterceptor(
            Provider<ClientViewDistanceService> clientViewDistanceService,
            Provider<FakeChunkService> fakeChunkServiceProvider,
            Container<Config> configContainer
    ) {
        this.clientViewDistanceService = clientViewDistanceService;
        this.fakeChunkServiceProvider = fakeChunkServiceProvider;
        this.configContainer = configContainer;
    }

    @OnEnable
    public void onEnable() {
        listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Player player = event.getPlayer();
                if (player == null) return;
                if (!player.isOnline()) return;
                if (player.getTicksLived() < config().interceptorMinPlayerTicksLived()) return;

                int targetDistance;
                try {
                    int client = clientViewDistanceService.get().getOrDefault(player.getUniqueId(), config().interceptorMaxTargetDistance());
                    targetDistance = Math.min(config().interceptorMaxTargetDistance(), client);
                } catch (Throwable ignored) {
                    targetDistance = config().interceptorMaxTargetDistance();
                }
                if (targetDistance < 2) targetDistance = 2;
                int advertisedDistance = targetDistance;
                try {
                    advertisedDistance = fakeChunkServiceProvider.get().getAdvertisedDistance(player.getUniqueId());
                } catch (Throwable ignored) {
                }
                if (advertisedDistance < 2) advertisedDistance = 2;
                if (advertisedDistance > targetDistance) advertisedDistance = targetDistance;
                final int effectiveTargetDistance = advertisedDistance;

                if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
                    WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
                    int chunkX = wrapper.getChunkX();
                    int chunkZ = wrapper.getChunkZ();

                    int playerChunkX = player.getLocation().getBlockX() >> 4;
                    int playerChunkZ = player.getLocation().getBlockZ() >> 4;
                    int dx = chunkX - playerChunkX;
                    int dz = chunkZ - playerChunkZ;
                    int distanceSq = dx * dx + dz * dz;
                    int margin = config().interceptorUnloadMarginChunks();
                    int radius = effectiveTargetDistance + margin;
                    if (distanceSq <= radius * radius) {
                        event.setCancelled(true);
                        debug(player, "unload_cancel", "[EH] cancel UNLOAD_CHUNK " + chunkX + "," + chunkZ + " distSq=" + distanceSq + " target=" + effectiveTargetDistance);
                    } else {
                        debug(player, "unload_allow", "[EH] allow UNLOAD_CHUNK " + chunkX + "," + chunkZ + " distSq=" + distanceSq + " target=" + effectiveTargetDistance);
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
        if (!config().debugEnabled()) return;
        if (player == null || tag == null || msg == null) return;
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + ":" + tag;
        Long last = lastLogMs.get(key);
        if (last != null && now - last < 2000) return;
        lastLogMs.put(key, now);
        logger.info(msg);
    }

    private Config config() {
        Config cfg = configContainer.get();
        return cfg == null ? new Config(null, null, null) : cfg;
    }
}
