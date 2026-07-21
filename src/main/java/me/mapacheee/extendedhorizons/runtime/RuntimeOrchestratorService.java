package me.mapacheee.extendedhorizons.runtime;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.LightPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.GlobalGenerationLimiterService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.cache.FarPlayerCacheService;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public final class RuntimeOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeOrchestratorService.class);
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();
    private static final int EQUIPMENT_SLOT_COUNT = EQUIPMENT_SLOTS.length;
    private static final int CACHE_CLEANUP_INTERVAL = 200;
    private static final int DEBUG_METRICS_LOG_INTERVAL = 200;
    private static final double CACHE_HIT_RATE_SCALE = 10_000.0d;
    private static final double PERCENTAGE_DIVISOR = 100.0d;

    private final Container<EhConfig> configContainer;
    private final SessionRegistry sessionRegistry;
    private final FakeChunkOrchestratorService fakeChunkOrchestratorService;
    private final GlobalGenerationLimiterService generationLimiterService;
    private final FarPlayerCacheService farPlayerCacheService;
    private final ChunkBuildMetricsService chunkBuildMetricsService;
    private final ChunkBuildCacheService chunkBuildCacheService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;

    private final Map<UUID, List<Pair<EquipmentSlot, ItemStack>>> lastEquipment = new HashMap<>();

    private volatile ScheduledTask runtimeTask;
    private int orchestratorTick;

    @Inject
    public RuntimeOrchestratorService(
        Container<EhConfig> configContainer,
        SessionRegistry sessionRegistry,
        FakeChunkOrchestratorService fakeChunkOrchestratorService,
        GlobalGenerationLimiterService generationLimiterService,
        FarPlayerCacheService farPlayerCacheService,
        ChunkBuildMetricsService chunkBuildMetricsService,
        ChunkBuildCacheService chunkBuildCacheService,
        LightPayloadCacheService lightPayloadCacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService
    ) {
        this.configContainer = configContainer;
        this.sessionRegistry = sessionRegistry;
        this.fakeChunkOrchestratorService = fakeChunkOrchestratorService;
        this.generationLimiterService = generationLimiterService;
        this.farPlayerCacheService = farPlayerCacheService;
        this.chunkBuildMetricsService = chunkBuildMetricsService;
        this.chunkBuildCacheService = chunkBuildCacheService;
        this.lightPayloadCacheService = lightPayloadCacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
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
        List<Player> playerList = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (playerList.isEmpty()) {
            return;
        }
        Collections.shuffle(playerList);

        EhConfig config = this.configContainer.get();
        this.generationLimiterService.reset(config.maxGlobalGenerationsPerTick());

        this.orchestratorTick = (this.orchestratorTick + 1) & Integer.MAX_VALUE;
        boolean farPlayersEnabled = config.farPlayersEnabled();
        boolean pollEquipment = farPlayersEnabled && Math.floorMod(this.orchestratorTick, config.farPlayerEquipTicks()) == 0;

        for (Player player : playerList) {
            FoliaTaskUtil.runForPlayer(player, plugin, () -> {
                try {
                    Location loc = player.getLocation();
                    ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();

                    if (player.getGameMode() == GameMode.SPECTATOR || player.hasMetadata("vanished")) {
                        this.farPlayerCacheService.removePlayer(player.getUniqueId());
                        return;
                    }

                    if (farPlayersEnabled) {
                        List<SynchedEntityData.DataValue<?>> metadata;
                        boolean pollMetadata = Math.floorMod(this.orchestratorTick, config.farPlayerMoveTicks()) == 0;
                        if (pollMetadata) {
                            metadata = nmsPlayer.getEntityData().packAll();
                        } else {
                            FarPlayerState oldState = this.farPlayerCacheService.getState(player.getUniqueId());
                            if (oldState != null && oldState.metadata() != null) {
                                metadata = oldState.metadata();
                            } else {
                                metadata = nmsPlayer.getEntityData().packAll();
                            }
                        }
                        List<Pair<EquipmentSlot, ItemStack>> equipment;

                        if (pollEquipment) {
                            List<Pair<EquipmentSlot, ItemStack>> prevEquipment = this.lastEquipment.get(player.getUniqueId());
                            boolean changed = prevEquipment == null || prevEquipment.size() != EQUIPMENT_SLOT_COUNT;
                            if (!changed) {
                                for (int i = 0; i < EQUIPMENT_SLOT_COUNT; i++) {
                                    Pair<EquipmentSlot, ItemStack> prev = prevEquipment.get(i);
                                    ItemStack current = nmsPlayer.getItemBySlot(prev.getFirst());
                                    if (!ItemStack.isSameItemSameComponents(prev.getSecond(), current)) {
                                        changed = true;
                                        break;
                                    }
                                }
                            }
                            if (changed) {
                                equipment = new ArrayList<>(EQUIPMENT_SLOT_COUNT);
                                for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
                                    ItemStack item = nmsPlayer.getItemBySlot(slot);
                                    equipment.add(Pair.of(slot, item.copy()));
                                }
                                this.lastEquipment.put(player.getUniqueId(), new ArrayList<>(equipment));
                                this.farPlayerCacheService.updateEquipment(player.getUniqueId(), equipment);
                            } else {
                                equipment = prevEquipment;
                            }
                        } else {
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
                            nmsPlayer.yHeadRot,
                            equipment,
                            metadata
                        ));
                    }

                    this.fakeChunkOrchestratorService.tickPlayer(player);
                } catch (Throwable throwable) {
                    LOGGER.error("Error while ticking fake chunks for {}", player.getName(), throwable);
                }
            });
        }

        if (Math.floorMod(this.orchestratorTick, CACHE_CLEANUP_INTERVAL) == 0) {
            this.chunkBuildCacheService.cleanUp();
            this.lightPayloadCacheService.cleanUp();
            this.antiXrayPayloadCacheService.cleanUp();
        }

        if (config.debugEnabled() && Math.floorMod(this.orchestratorTick, DEBUG_METRICS_LOG_INTERVAL) == 0) {
            ChunkBuildMetricsService.Snapshot metrics = this.chunkBuildMetricsService.snapshotAndReset();
            if (metrics.hasData()) {
                LOGGER.info(
                    "EH metrics: antiXraySnapshot avg={}us (n={}), antiXrayAsync avg={}us (n={}), antiXrayCacheHitRate={}%, fallback={}",
                    metrics.antiXraySnapshotAvgMicros(),
                    metrics.antiXraySnapshotCount(),
                    metrics.antiXrayAsyncAvgMicros(),
                    metrics.antiXrayAsyncCount(),
                    Math.round(metrics.antiXrayCacheHitRate() * CACHE_HIT_RATE_SCALE) / PERCENTAGE_DIVISOR,
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
