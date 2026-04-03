package me.mapacheee.extendedhorizons.hooks.placeholderapi;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PlaceholderApiHookService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlaceholderApiHookService.class);
  private static final String PLACEHOLDER_API_PLUGIN = "PlaceholderAPI";
  private static final String PLACEHOLDER_EXPANSION_CLASS =
      "me.mapacheee.extendedhorizons.hooks.placeholderapi.ExtendedHorizonsPlaceholderExpansion";

  private final FakeChunkService fakeChunkService;
  private Object expansion;

  @Inject
  public PlaceholderApiHookService(FakeChunkService fakeChunkService) {
    this.fakeChunkService = fakeChunkService;
  }

  @OnEnable
  public void onEnable() {
    if (!Bukkit.getPluginManager().isPluginEnabled(PLACEHOLDER_API_PLUGIN)) return;
    try {
      Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
      Class<?> expansionClass = Class.forName(PLACEHOLDER_EXPANSION_CLASS);
      Object instance = expansionClass.getConstructor(FakeChunkService.class).newInstance(fakeChunkService);
      expansionClass.getMethod("registerExpansion").invoke(instance);
      this.expansion = instance;
    } catch (Throwable t) {
      LOGGER.error("Failed to initialize PlaceholderAPI integration", t);
    }
  }

  @OnDisable
  public void onDisable() {
    if (expansion == null) return;
    try {
      expansion.getClass().getMethod("unregisterExpansion").invoke(expansion);
    } catch (Throwable t) {
      LOGGER.error("Failed to shutdown PlaceholderAPI integration", t);
    } finally {
      expansion = null;
    }
  }
}
