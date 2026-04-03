package me.mapacheee.extendedhorizons.hooks.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtendedHorizonsPlaceholderExpansion extends PlaceholderExpansion {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ExtendedHorizonsPlaceholderExpansion.class);
  private final FakeChunkService fakeChunkService;

  public ExtendedHorizonsPlaceholderExpansion(FakeChunkService fakeChunkService) {
    this.fakeChunkService = fakeChunkService;
  }

  public void registerExpansion() {
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
    try {
      if (!isRegistered()) {
        this.register();
      }
    } catch (Throwable t) {
      LOGGER.error("Failed to register PlaceholderAPI expansion", t);
    }
  }

  public void unregisterExpansion() {
    try {
      if (isRegistered()) {
        this.unregister();
      }
    } catch (Throwable t) {
      LOGGER.error("Failed to unregister PlaceholderAPI expansion", t);
    }
  }

  @Override
  public @NotNull String getIdentifier() {
    return "extendedhorizons";
  }

  @Override
  public @NotNull String getAuthor() {
    return "Mapacheee";
  }

  @Override
  public @NotNull String getVersion() {
    ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
    if (plugin == null) return "unknown";
    return plugin.getPluginMeta().getVersion();
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
    try {
      if (player == null) return null;
      if ("view_distance".equalsIgnoreCase(params)) {
        if (player.getUniqueId() == null) return "0";
        return String.valueOf(fakeChunkService.getAdvertisedDistance(player.getUniqueId()));
      }
    } catch (Throwable t) {
      LOGGER.error("Placeholder request failed for params={}", params, t);
      return "0";
    }
    return null;
  }
}
