package me.mapacheee.extendedhorizons.config;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Set;

@ConfigSerializable
@Configurate("config")
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
  @Setting("disabled-worlds") Set<String> disabledWorlds
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
