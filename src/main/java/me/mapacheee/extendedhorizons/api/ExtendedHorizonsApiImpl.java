package me.mapacheee.extendedhorizons.api;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.PlayerDistancePreferenceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * Default implementation of ExtendedHorizonsApi.
 * Bridges API calls to the internal services and config.
 */
@Service
public class ExtendedHorizonsApiImpl implements ExtendedHorizonsApi {

  private final ConfigService configService;
  private final PlayerDistancePreferenceService preferenceService;

  @Inject
  public ExtendedHorizonsApiImpl(
      ConfigService configService, PlayerDistancePreferenceService preferenceService) {
    this.configService = configService;
    this.preferenceService = preferenceService;
  }

  @OnEnable
  public void registerApi() {
    Plugin plugin = ExtendedHorizonsPlugin.getInstance();
    if (plugin != null && Bukkit.getServicesManager().getRegistration(ExtendedHorizonsApi.class) == null) {
      Bukkit.getServicesManager()
          .register(ExtendedHorizonsApi.class, this, plugin, ServicePriority.Normal);
    }
  }

  @OnDisable
  public void unregisterApi() {
    Bukkit.getServicesManager().unregister(ExtendedHorizonsApi.class, this);
  }

  @Override
  public int getPlayerViewDistance(Player player) {
    if (player == null) return -1;
    Integer val = preferenceService.get(player.getUniqueId());
    return val != null ? val : -1;
  }

  @Override
  public void setPlayerViewDistance(Player player, int viewDistance) {
    if (player == null) return;
    preferenceService.set(player.getUniqueId(), viewDistance);
  }

  @Override
  public void resetPlayerViewDistance(Player player) {
    if (player == null) return;
    preferenceService.remove(player.getUniqueId());
  }

  @Override
  public int getServerMaxViewDistance() {
    Config config = configService.get();
    if (config == null) return 32;
    return config.fakeTargetViewDistance();
  }

  @Override
  public boolean isFakeChunksEnabled(String worldName) {
    Config config = configService.get();
    if (config == null) return false;
    return config.fakeChunksEnabledForWorld(worldName);
  }

  @Override
  public boolean isFarPlayersEnabled() {
    Config config = configService.get();
    if (config == null) return false;
    return config.farPlayersEnabled();
  }
}
