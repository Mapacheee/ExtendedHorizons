package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.runtime.ChunkBuildMetricsService;
import me.mapacheee.extendedhorizons.util.FoliaTaskUtil;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public final class ChunkPayloadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ChunkPayloadService.class);

  private final ChunkBuildCacheService cacheService;
  private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
  private final ChunkBuildMetricsService metricsService;
  private final ChunkBackend chunkBackend;

  @Inject
  public ChunkPayloadService(
    ChunkBuildCacheService cacheService,
    AntiXrayPayloadCacheService antiXrayPayloadCacheService,
    ChunkBuildMetricsService metricsService,
    ChunkBackend chunkBackend
  ) {
    this.cacheService = cacheService;
    this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
    this.metricsService = metricsService;
    this.chunkBackend = chunkBackend;
  }

  public CompletableFuture<ByteBuf> prepare(
    World world,
    UUID expectedWorldId,
    int chunkX,
    int chunkZ,
    long chunkKey,
    long cacheGeneration,
    EhConfig config,
    boolean refresh
  ) {
    boolean preferFreshData = refresh || this.cacheService.shouldBypass(expectedWorldId, chunkKey);
    long antiXrayCacheGeneration = this.antiXrayPayloadCacheService.generation();
    boolean antiXrayEnabled = config.antiXrayEnabled(world.getName());
    String antiXrayProfileHash = antiXrayEnabled
      ? this.antiXrayPayloadCacheService.resolveProfileHash(world, config)
      : null;

    if (config.debugEnabled()) {
      LOGGER.info(
        "EH buildChunk: chunk=({}, {}) antiXrayEnabled={} bypassCache={}",
        chunkX, chunkZ, antiXrayEnabled,
        antiXrayEnabled || preferFreshData
      );
    }

    if (antiXrayProfileHash != null && !preferFreshData) {
      ByteBuf antiXrayCached = this.antiXrayPayloadCacheService.get(
        expectedWorldId,
        chunkKey,
        antiXrayProfileHash,
        config.serializerMode(),
        antiXrayCacheGeneration
      );
      if (antiXrayCached != null) {
        this.metricsService.recordAntiXrayFinalCacheHit();
        return CompletableFuture.completedFuture(antiXrayCached);
      }
      this.metricsService.recordAntiXrayFinalCacheMiss();
    }

    if (this.cacheService.isTemporarilyUnavailable(expectedWorldId, chunkKey)) {
      if (config.debugEnabled()) {
        LOGGER.info("EH buildChunk skip: cache temporarily unavailable for {}", chunkKey);
      }
      return CompletableFuture.completedFuture(null);
    }

    boolean bypass = antiXrayEnabled || preferFreshData;
    if (!bypass) {
      ByteBuf cached = this.cacheService.getSerialized(expectedWorldId, chunkKey);
      if (cached != null) {
        if (config.debugEnabled()) {
          LOGGER.info("EH buildChunk cache hit for {}", chunkKey);
        }
        return CompletableFuture.completedFuture(cached);
      }
    }

    CompletableFuture<ByteBuf> source = this.cacheService.getOrStartBuildFuture(
      expectedWorldId,
      chunkKey,
      cacheGeneration,
      () -> this.chunkBackend.buildChunkPayload(
        world,
        chunkX,
        chunkZ,
        config.generateMissingChunks(),
        preferFreshData,
        (worldRef, cx, cz, runnable) -> {
          ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
          if (plugin == null || !plugin.isEnabled()) {
            return false;
          }
          return FoliaTaskUtil.runAtChunk(worldRef, cx, cz, plugin, runnable);
        }
      )
    );
    CompletableFuture<ByteBuf> result = new CompletableFuture<>();
    source.whenComplete((payload, throwable) -> {
      if (throwable != null) {
        result.complete(null);
        return;
      } else if (payload == null) {
        this.cacheService.markUnavailable(expectedWorldId, chunkKey, cacheGeneration);
        if (config.debugEnabled()) {
          LOGGER.info("EH buildChunk failed: null payload for {}", chunkKey);
        }
      } else if (antiXrayProfileHash != null) {
        try {
          this.antiXrayPayloadCacheService.put(
            expectedWorldId,
            chunkKey,
            antiXrayProfileHash,
            config.serializerMode(),
            antiXrayCacheGeneration,
            payload
          );
        } catch (RuntimeException exception) {
          LOGGER.warn("Failed to cache anti-xray payload for chunk {}", chunkKey, exception);
        }
      }
      if (!result.complete(payload)) {
        ReferenceCountUtil.release(payload);
      }
    });
    result.whenComplete((payload, throwable) -> {
      if (result.isCancelled()) {
        source.cancel(false);
      }
    });
    return result;
  }

}
