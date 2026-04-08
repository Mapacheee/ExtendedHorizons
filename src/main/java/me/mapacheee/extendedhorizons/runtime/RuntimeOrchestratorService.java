package me.mapacheee.extendedhorizons.runtime;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.netty.util.NettyRuntime;
import io.netty.util.internal.SystemPropertyUtil;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.ConfigFacade;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public final class RuntimeOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeOrchestratorService.class);
    private static final int TICK_LENGTH_DIVISOR = 2;

    private final ConfigFacade configFacade;
    private final SessionRegistry sessionRegistry;
    private final FakeChunkOrchestratorService fakeChunkOrchestratorService;

    private volatile ScheduledTask runtimeTask;

    @Inject
    public RuntimeOrchestratorService(
            ConfigFacade configFacade,
            SessionRegistry sessionRegistry,
            FakeChunkOrchestratorService fakeChunkOrchestratorService
    ) {
        this.configFacade = configFacade;
        this.sessionRegistry = sessionRegistry;
        this.fakeChunkOrchestratorService = fakeChunkOrchestratorService;
    }

    @OnEnable
    public void onEnable() {
        ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        this.cancelTask();
        long period = this.configFacade.get().runtimePeriodTicks();
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
        long periodTicks = Math.max(1L, this.configFacade.get().runtimePeriodTicks());
        long nanosPerServerTick = 50_000_000L * periodTicks;
        long tickLengthNanos = nanosPerServerTick / TICK_LENGTH_DIVISOR;
        long maxTimePerPlayerNanos = (nettyThreadCount * tickLengthNanos) / Math.max(nettyThreadCount, players.size());

        for (Player player : players) {
            this.sessionRegistry.ensureFor(player, false);
            FoliaTaskUtil.runForPlayer(player, plugin, () -> {
                try {
                    this.fakeChunkOrchestratorService.tickPlayer(player, maxTimePerPlayerNanos);
                } catch (Throwable throwable) {
                    LOGGER.error("Error while ticking fake chunks for {}", player.getName(), throwable);
                }
            });
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
