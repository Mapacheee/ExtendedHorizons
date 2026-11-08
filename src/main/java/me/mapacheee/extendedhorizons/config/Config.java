package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.configurate.config.Configurate;
import com.thewinterframework.configurate.config.OptionalNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Set;
import java.util.stream.Collectors;

@ConfigSerializable
@Configurate(value = "config", fillMissing = true)
public record Config(
  @Setting("view-distance") Integer distance,
  @Setting("send-per-tick") Integer sendsPerTick,
  @Setting("inflight-per-player") Integer inFlightPerPlayer,
  Integer workers,
  @Setting("global-inflight") Integer globalInFlight,
  @Setting("cache-mib") Integer cacheMiB,
  @Setting("cache-seconds") Integer cacheSeconds,
  @Setting("player-mib-per-second") Integer playerMiBPerSecond,
  @Setting("global-mib-per-second") Integer globalMiBPerSecond,
  @Setting("disabled-worlds") Set<String> disabledWorlds,
  @Setting("far-players") @OptionalNode FarPlayers farPlayers,
  @Setting("chunk-updates") ChunkUpdates chunkUpdates,
  @Setting("anti-xray") AntiXray antiXray
) {

  public Config {
    distance = bounded("view-distance", distance, 32, 2, 128);
    sendsPerTick = bounded("send-per-tick", sendsPerTick, 8, 1, 64);
    inFlightPerPlayer = bounded("inflight-per-player", inFlightPerPlayer, 8, 1, 32);
    workers = bounded("workers", workers, 2, 1, 16);
    globalInFlight = bounded("global-inflight", globalInFlight, 64, 1, 512);
    cacheMiB = bounded("cache-mib", cacheMiB, 128, 1, 2048);
    cacheSeconds = bounded("cache-seconds", cacheSeconds, 10, 1, 300);
    playerMiBPerSecond = bounded("player-mib-per-second", playerMiBPerSecond, 8, 1, 256);
    globalMiBPerSecond = bounded("global-mib-per-second", globalMiBPerSecond, 192, 1, 4096);
    disabledWorlds = disabledWorlds == null ? Set.of() : Set.copyOf(disabledWorlds);
    farPlayers = farPlayers == null ? new FarPlayers(null, null, null, null, null) : farPlayers;
    chunkUpdates = chunkUpdates == null ? new ChunkUpdates(null, null) : chunkUpdates;
    antiXray = antiXray == null ? new AntiXray(null, null) : antiXray;
  }

  @ConfigSerializable
  public record AntiXray(Boolean enabled, @Setting("hidden-blocks") @OptionalNode Set<String> hiddenBlocks) {

    public AntiXray {
      enabled = Boolean.TRUE.equals(enabled);
      hiddenBlocks = hiddenBlocks == null ? Set.of() : Set.copyOf(hiddenBlocks);
      hiddenBlocks.forEach(AntiXray::block);
    }

    public Set<Block> blocks() {
      return this.hiddenBlocks.stream().map(AntiXray::block).collect(Collectors.toUnmodifiableSet());
    }

    private static Block block(String value) {
      Identifier id = Identifier.tryParse(value);
      Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
      if (block == null || block.defaultBlockState().isAir()) {
        throw new IllegalArgumentException("Invalid block in anti-xray.hidden-blocks: " + value);
      }
      return block;
    }
  }

  @ConfigSerializable
  public record ChunkUpdates(Boolean enabled, Boolean worldedit) {

    public ChunkUpdates {
      enabled = Boolean.TRUE.equals(enabled);
      worldedit = Boolean.TRUE.equals(worldedit);
    }
  }

  @ConfigSerializable
  public record FarPlayers(Boolean enabled, @Setting("max-per-viewer") Integer maxPerViewer,
    @Setting("update-ticks") Integer updateTicks, Boolean equipment, Boolean actions) {

    public FarPlayers {
      enabled = Boolean.TRUE.equals(enabled);
      maxPerViewer = bounded("far-players.max-per-viewer", maxPerViewer, 12, 1, 64);
      updateTicks = bounded("far-players.update-ticks", updateTicks, 3, 1, 20);
      equipment = equipment == null || equipment;
      actions = actions == null || actions;
    }
  }

  private static int bounded(String path, Integer value, int fallback, int min, int max) {
    if (value == null) {
      return fallback;
    }
    if (value < min || value > max) {
      throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
    }
    return value;
  }

  public long cacheBytes() {
    return this.cacheMiB * 1024L * 1024L;
  }

  public long cacheTtlMillis() {
    return this.cacheSeconds * 1000L;
  }

  public long playerBytesPerSecond() {
    return this.playerMiBPerSecond * 1024L * 1024L;
  }

  public long globalBytesPerSecond() {
    return this.globalMiBPerSecond * 1024L * 1024L;
  }
}
