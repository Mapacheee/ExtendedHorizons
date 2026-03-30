package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.configurate.config.Configurate;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
@Configurate("config")
public record Config(
    DebugConfig debug,
    @Setting("fake-chunks") FakeChunksConfig fakeChunks,
    @Setting("packet-interceptor") PacketInterceptorConfig packetInterceptor,
    @Setting("world-settings") Map<String, WorldSettingsConfig> worldSettings,
    @Setting("far-players") FarPlayersConfig farPlayers
) {
  public static Config empty() {
    return new Config(null, null, null, null, null);
  }

  public boolean debugEnabled() {
    return debug == null || debug.enabled();
  }

  public int fakeTargetViewDistance() {
    return fakeChunks == null ? 32 : fakeChunks.targetViewDistance();
  }

  public int fakeTargetViewDistance(String worldName) {
    WorldSettingsConfig worldConfig = worldConfig(worldName);
    if (worldConfig != null && worldConfig.targetDistance() > 0) {
      return worldConfig.targetDistance();
    }
    return fakeTargetViewDistance();
  }

  public boolean fakeChunksEnabledForWorld(String worldName) {
    WorldSettingsConfig worldConfig = worldConfig(worldName);
    if (worldConfig == null) return true;
    return worldConfig.enableFakechunks();
  }

  public boolean farPlayersEnabledForWorld(String worldName) {
    WorldSettingsConfig worldConfig = worldConfig(worldName);
    if (worldConfig == null) return true;
    if (worldConfig.enableFarplayers() == null) return true;
    return worldConfig.enableFarplayers();
  }

  public int maxSendPerCycle() {
    return fakeChunks == null ? 25 : fakeChunks.maxSendPerCycle();
  }

  public int maxInflightPerPlayer() {
    return fakeChunks == null ? 16 : fakeChunks.maxInflightPerPlayer();
  }

  public long forcePlanIntervalMs() {
    return fakeChunks == null ? 3000L : fakeChunks.forcePlanIntervalMs();
  }

  public int keepAliveInitialDelayTicks() {
    if (fakeChunks == null || fakeChunks.keepalive() == null) return 20;
    return Math.max(1, fakeChunks.keepalive().initialDelayTicks());
  }

  public int keepAlivePeriodTicks() {
    if (fakeChunks == null || fakeChunks.keepalive() == null) return 10;
    return Math.max(1, fakeChunks.keepalive().periodTicks());
  }

  public int warmupDelayTicks() {
    if (fakeChunks == null || fakeChunks.warmup() == null) return 5;
    return Math.max(1, fakeChunks.warmup().delayTicks());
  }

  public double safeSquareFactor() {
    return fakeChunks == null ? 0.65d : fakeChunks.safeSquareFactor();
  }

  public int cacheTtlSeconds() {
    if (fakeChunks == null || fakeChunks.cache() == null) return 15;
    return fakeChunks.cache().ttlSeconds();
  }

  public int cacheMaxEntries() {
    if (fakeChunks == null || fakeChunks.cache() == null) return 1500;
    return fakeChunks.cache().maxEntries();
  }

  public long cacheBypassAfterRealInteractionMs() {
    if (fakeChunks == null || fakeChunks.cache() == null) return 3000L;
    return fakeChunks.cache().bypassAfterRealInteractionMs();
  }

  public boolean cacheBypassWhenRealPlayers() {
    if (fakeChunks == null || fakeChunks.cache() == null) return false;
    return fakeChunks.cache().bypassWhenRealPlayers();
  }

  public boolean autoRefreshEnabled() {
    if (fakeChunks == null || fakeChunks.liveRefresh() == null) return true;
    return fakeChunks.liveRefresh().enabled();
  }

  public long autoRefreshPeriodMs() {
    if (fakeChunks == null || fakeChunks.liveRefresh() == null) return 1000L;
    return Math.max(250L, fakeChunks.liveRefresh().periodMs());
  }

  public int autoRefreshChunksPerCycle() {
    if (fakeChunks == null || fakeChunks.liveRefresh() == null) return 4;
    return Math.max(1, fakeChunks.liveRefresh().chunksPerCycle());
  }

  public boolean autoRefreshInvalidateFallbackEnabled() {
    if (fakeChunks == null || fakeChunks.liveRefresh() == null) return false;
    return fakeChunks.liveRefresh().invalidateFallbackEnabled();
  }

  public int autoRefreshInvalidateFallbackMaxPerCycle() {
    if (fakeChunks == null || fakeChunks.liveRefresh() == null) return 1;
    return Math.max(0, fakeChunks.liveRefresh().invalidateFallbackMaxPerCycle());
  }

  public long autoRefreshMinInvalidateIntervalMs() {
    if (fakeChunks == null || fakeChunks.liveRefresh() == null) return 250L;
    return Math.max(0L, fakeChunks.liveRefresh().minInvalidateIntervalMs());
  }

  public int interceptorMaxTargetDistance() {
    return packetInterceptor == null ? 32 : packetInterceptor.maxTargetDistance();
  }

  public int interceptorMinPlayerTicksLived() {
    return packetInterceptor == null ? 40 : packetInterceptor.minPlayerTicksLived();
  }

  public int interceptorUnloadMarginChunks() {
    return packetInterceptor == null ? 1 : packetInterceptor.unloadMarginChunks();
  }

  public boolean farPlayersEnabled() {
    return farPlayers != null && farPlayers.enabled();
  }

  public int farPlayersMaxDistanceChunks() {
    if (farPlayers == null) return 32;
    return Math.max(2, farPlayers.maxDistanceChunks());
  }

  public int farPlayersUpdateIntervalTicks() {
    if (farPlayers == null) return 4;
    return Math.max(1, farPlayers.updateIntervalTicks());
  }

  public int farPlayersSpawnBurstPerTick() {
    if (farPlayers == null) return 10;
    return Math.max(1, farPlayers.spawnBurstPerTick());
  }

  public int farPlayersMaxSyncedPerViewer() {
    if (farPlayers == null) return 40;
    return Math.max(1, farPlayers.maxSyncedPerViewer());
  }

  public boolean farPlayersRespectVanish() {
    if (farPlayers == null) return true;
    return farPlayers.respectVanish();
  }

  public boolean farPlayersDisableInSpectator() {
    if (farPlayers == null) return true;
    return farPlayers.disableInSpectator();
  }

  public boolean farPlayersRequireViewerPermission() {
    if (farPlayers == null) return false;
    return farPlayers.requireViewerPermission();
  }

  public String farPlayersViewerPermissionNode() {
    if (farPlayers == null || farPlayers.viewerPermissionNode() == null || farPlayers.viewerPermissionNode().isBlank()) {
      return "extendedhorizons.farplayers.receive";
    }
    return farPlayers.viewerPermissionNode();
  }

  public boolean farPlayersAllowMountSync() {
    if (farPlayers == null) return true;
    return farPlayers.allowMountSync();
  }

  private WorldSettingsConfig worldConfig(String worldName) {
    if (worldSettings == null || worldSettings.isEmpty() || worldName == null || worldName.isBlank()) {
      return null;
    }
    WorldSettingsConfig direct = worldSettings.get(worldName);
    if (direct != null) return direct;
    for (Map.Entry<String, WorldSettingsConfig> entry : worldSettings.entrySet()) {
      String key = entry.getKey();
      if (key != null && key.equalsIgnoreCase(worldName)) {
        return entry.getValue();
      }
    }
    return null;
  }

  @ConfigSerializable
  public record DebugConfig(boolean enabled) {}

  @ConfigSerializable
  public record FakeChunksConfig(
      @Setting("target-view-distance") int targetViewDistance,
      @Setting("max-send-per-cycle") int maxSendPerCycle,
      @Setting("max-inflight-per-player") int maxInflightPerPlayer,
      @Setting("force-plan-interval-ms") long forcePlanIntervalMs,
      KeepAliveConfig keepalive,
      WarmupConfig warmup,
      CacheConfig cache,
      @Setting("live-refresh") LiveRefreshConfig liveRefresh,
      @Setting("safe-square-factor") double safeSquareFactor
  ) {
    @ConfigSerializable
    public record KeepAliveConfig(
        @Setting("initial-delay-ticks") int initialDelayTicks,
        @Setting("period-ticks") int periodTicks
    ) {}

    @ConfigSerializable
    public record WarmupConfig(
        @Setting("delay-ticks") int delayTicks
    ) {}

    @ConfigSerializable
    public record CacheConfig(
        @Setting("ttl-seconds") int ttlSeconds,
        @Setting("max-entries") int maxEntries,
        @Setting("bypass-after-real-interaction-ms") long bypassAfterRealInteractionMs,
        @Setting("bypass-when-real-players") boolean bypassWhenRealPlayers
    ) {}

    @ConfigSerializable
    public record LiveRefreshConfig(
        boolean enabled,
        @Setting("period-ms") long periodMs,
        @Setting("chunks-per-cycle") int chunksPerCycle,
        @Setting("invalidate-fallback-enabled") boolean invalidateFallbackEnabled,
        @Setting("invalidate-fallback-max-per-cycle") int invalidateFallbackMaxPerCycle,
        @Setting("min-invalidate-interval-ms") long minInvalidateIntervalMs
    ) {}
  }

  @ConfigSerializable
  public record PacketInterceptorConfig(
      @Setting("max-target-distance") int maxTargetDistance,
      @Setting("min-player-ticks-lived") int minPlayerTicksLived,
      @Setting("unload-margin-chunks") int unloadMarginChunks
  ) {}

  @ConfigSerializable
  public record WorldSettingsConfig(
      @Setting("enable-fakechunks") boolean enableFakechunks,
      @Setting("target-distance") int targetDistance,
      @Setting("enable-farplayers") Boolean enableFarplayers
  ) {}

  @ConfigSerializable
  public record FarPlayersConfig(
      boolean enabled,
      @Setting("max-distance-chunks") int maxDistanceChunks,
      @Setting("update-interval-ticks") int updateIntervalTicks,
      @Setting("spawn-burst-per-tick") int spawnBurstPerTick,
      @Setting("max-synced-per-viewer") int maxSyncedPerViewer,
      @Setting("respect-vanish") boolean respectVanish,
      @Setting("disable-in-spectator") boolean disableInSpectator,
      @Setting("require-viewer-permission") boolean requireViewerPermission,
      @Setting("viewer-permission-node") String viewerPermissionNode,
      @Setting("allow-mount-sync") boolean allowMountSync
  ) {}
}
