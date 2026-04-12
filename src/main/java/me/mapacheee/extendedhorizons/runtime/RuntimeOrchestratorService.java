package me.mapacheee.extendedhorizons.runtime;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.netty.util.NettyRuntime;
import io.netty.util.internal.SystemPropertyUtil;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.GlobalGenerationLimiterService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import com.mojang.datafixers.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public final class RuntimeOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeOrchestratorService.class);
    private static final int TICK_LENGTH_DIVISOR = 2;

    private final Container<EhConfig> configContainer;
    private final SessionRegistry sessionRegistry;
    private final FakeChunkOrchestratorService fakeChunkOrchestratorService;
    private final GlobalGenerationLimiterService generationLimiterService;
    private final FarPlayerCacheService farPlayerCacheService;
    private final ChunkBuildMetricsService chunkBuildMetricsService;

    private volatile ScheduledTask runtimeTask;
    private int orchestratorTick;

    @Inject
    public RuntimeOrchestratorService(
        Container<EhConfig> configContainer,
        SessionRegistry sessionRegistry,
        FakeChunkOrchestratorService fakeChunkOrchestratorService,
        GlobalGenerationLimiterService generationLimiterService,
        FarPlayerCacheService farPlayerCacheService,
        ChunkBuildMetricsService chunkBuildMetricsService
    ) {
        this.configContainer = configContainer;
        this.sessionRegistry = sessionRegistry;
        this.fakeChunkOrchestratorService = fakeChunkOrchestratorService;
        this.generationLimiterService = generationLimiterService;
        this.farPlayerCacheService = farPlayerCacheService;
        this.chunkBuildMetricsService = chunkBuildMetricsService;
    }

    @OnEnable
    public void onEnable() {
        ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        this.cancelTask();
        long period = this.configContainer.get().runtimePeriodTicks();
        this.runtimeTask = FoliaTaskUtil.runGlobalTimer(plugin, this::runtimeTick, 1L, period);
    }

    @OnDisable
    public void onDisable() {
        this.cancelTask();
    }

    private void runtimeTick() {
        ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        Collections.shuffle(players);

        int nettyThreadCount = Math.max(1, SystemPropertyUtil.getInt(
            "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));
        EhConfig config = this.configContainer.get();
        this.generationLimiterService.reset(config.maxGlobalGenerationsPerTick());
        long periodTicks = Math.max(1L, config.runtimePeriodTicks());
        long nanosPerServerTick = 50_000_000L * periodTicks;
        long tickLengthNanos = nanosPerServerTick / TICK_LENGTH_DIVISOR;
        long maxTimePerPlayerNanos = (nettyThreadCount * tickLengthNanos) / Math.max(nettyThreadCount, players.size());

        this.orchestratorTick = (this.orchestratorTick + 1) & Integer.MAX_VALUE;
        boolean pollEquipment = Math.floorMod(this.orchestratorTick, config.farPlayerEquipTicks()) == 0;

        for (Player player : players) {
            this.sessionRegistry.ensureFor(player, false);
            FoliaTaskUtil.runForPlayer(player, plugin, () -> {
                try {
                    Location loc = player.getLocation();
                    ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();

                    List<SynchedEntityData.DataValue<?>> metadata;
                    List<Pair<EquipmentSlot, ItemStack>> equipment;

                    if (pollEquipment) {
                        metadata = nmsPlayer.getEntityData().getNonDefaultValues();
                        equipment = new ArrayList<>(6);
                        for (EquipmentSlot slot : EquipmentSlot.values()) {
                            ItemStack item = nmsPlayer.getItemBySlot(slot);
                            equipment.add(Pair.of(slot, item.copy()));
                        }
                        this.farPlayerCacheService.updateEquipment(player.getUniqueId(), equipment);
                    } else {
                        metadata = null;
                        equipment = this.farPlayerCacheService.getEquipment(player.getUniqueId());
                        if (equipment == null) {
                            equipment = Collections.emptyList();
                        }
                    }

                    this.farPlayerCacheService.updateState(player.getUniqueId(), new FarPlayerState(
                        player.getEntityId(),
                        player.getUniqueId(),
                        player.getWorld().getUID(),
                        loc.getX(),
                        loc.getY(),
                        loc.getZ(),
                        loc.getYaw(),
                        loc.getPitch(),
                        player.getEyeLocation().getYaw(),
                        equipment,
                        metadata
                    ));

                    this.fakeChunkOrchestratorService.tickPlayer(player, maxTimePerPlayerNanos);
                } catch (Throwable throwable) {
                    LOGGER.error("Error while ticking fake chunks for {}", player.getName(), throwable);
                }
            });
        }

        if (config.debugEnabled() && Math.floorMod(this.orchestratorTick, 200) == 0) {
            ChunkBuildMetricsService.Snapshot metrics = this.chunkBuildMetricsService.snapshotAndReset();
            if (metrics.hasData()) {
                LOGGER.info(
                    "EH metrics: antiXraySnapshot avg={}us (n={}), antiXrayAsync avg={}us (n={}), antiXrayCacheHitRate={}%, fallback={}",
                    metrics.antiXraySnapshotAvgMicros(),
                    metrics.antiXraySnapshotCount(),
                    metrics.antiXrayAsyncAvgMicros(),
                    metrics.antiXrayAsyncCount(),
                    Math.round(metrics.antiXrayCacheHitRate() * 10000.0d) / 100.0d,
                    metrics.antiXrayFallbackCount()
                );
            }
        }
    }

    private void cancelTask() {
        ScheduledTask current = this.runtimeTask;
        this.runtimeTask = null;
        if (current == null) {
            return;
        }
        try {
            current.cancel();
        } catch (Throwable throwable) {
            LOGGER.debug("Failed to cancel runtime task cleanly", throwable);
        }
    }
}
